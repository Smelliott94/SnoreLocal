package dev.snorelocal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Reads the standalone ZIP appended to a TFLite FlatBuffer, including relative ZIP offsets. */
public final class ModelLabels {
    private ModelLabels() {}
    public static String[] read(byte[] model) throws IOException {
        // Support both absolute (append-mode) offsets and offsets relative to an appended ZIP.
        // ZipInputStream starts at the first local header and ignores central-directory offsets.
        int end = -1;
        for (int p = model.length - 22; p >= Math.max(0, model.length - 22 - 65535); p--) {
            if (u32(model, p) == 0x06054b50L && p + 22L + u16(model, p + 20) == model.length) {
                end = p;
                break;
            }
        }
        if (end < 0) throw new IOException("Model associated-file archive is missing");
        long central = end - u32(model, end + 12);
        long archiveBase = central - u32(model, end + 16);
        if (archiveBase < 0 || central < 0 || central + 46 > end || u32(model, (int) central) != 0x02014b50L)
            throw new IOException("Invalid model associated-file central directory");
        long start = archiveBase + u32(model, (int) central + 42);
        if (start < 0 || start + 30 > central || u32(model, (int) start) != 0x04034b50L)
            throw new IOException("Invalid model associated-file archive offsets");
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(model, (int) start, model.length - (int) start))) {
            ZipEntry entry;
            int entries = 0;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > 32) throw new IOException("Too many model associated files");
                ByteArrayOutputStream data = new ByteArrayOutputStream();
                byte[] block = new byte[4096];
                int n;
                while ((n = zip.read(block)) != -1) {
                    if (data.size() + n > 65536) throw new IOException("Model associated file exceeds size limit");
                    data.write(block, 0, n);
                }
                if (!entry.isDirectory() && entry.getName().endsWith(".txt")) {
                    String[] labels = new String(data.toByteArray(), StandardCharsets.UTF_8).split("\\r?\\n");
                    if (labels.length == 521 && labels[38].trim().equals("Snoring")) return labels;
                }
            }
        }
        throw new IOException("Model Snoring label at index 38 was not verified");
    }
    private static int u16(byte[] bytes, int at) { return (bytes[at] & 255) | ((bytes[at + 1] & 255) << 8); }
    private static long u32(byte[] bytes, int at) { return u16(bytes, at) | ((long) u16(bytes, at + 2) << 16); }
}
