package dev.snorelocal

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream

/** Reads the standalone ZIP appended to a TFLite FlatBuffer, including relative ZIP offsets. */
object ModelLabels {
    fun read(model: ByteArray): Array<String> {
        // Support both absolute (append-mode) offsets and offsets relative to an appended ZIP.
        // ZipInputStream starts at the first local header and ignores central-directory offsets.
        var end = -1
        for (p in (model.size - 22) downTo maxOf(0, model.size - 22 - 65535)) {
            if (u32(model, p) == 0x06054b50L && p + 22L + u16(model, p + 20) == model.size.toLong()) {
                end = p
                break
            }
        }
        if (end < 0) throw IOException("Model associated-file archive is missing")
        val central = end - u32(model, end + 12)
        val archiveBase = central - u32(model, end + 16)
        if (archiveBase < 0 || central < 0 || central + 46 > end || u32(model, central.toInt()) != 0x02014b50L) {
            throw IOException("Invalid model associated-file central directory")
        }
        val start = archiveBase + u32(model, central.toInt() + 42)
        if (start < 0 || start + 30 > central || u32(model, start.toInt()) != 0x04034b50L) {
            throw IOException("Invalid model associated-file archive offsets")
        }
        ZipInputStream(ByteArrayInputStream(model, start.toInt(), model.size - start.toInt())).use { zip ->
            var entries = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++entries > 32) throw IOException("Too many model associated files")
                val data = ByteArrayOutputStream()
                val block = ByteArray(4096)
                while (true) {
                    val n = zip.read(block)
                    if (n == -1) break
                    if (data.size() + n > 65536) throw IOException("Model associated file exceeds size limit")
                    data.write(block, 0, n)
                }
                if (!entry.isDirectory && entry.name.endsWith(".txt")) {
                    val labels = data.toByteArray().toString(Charsets.UTF_8)
                        .split(Regex("\\r?\\n")).dropLastWhile { it.isEmpty() }.toTypedArray()
                    if (labels.size == 521 && labels[38].trim() == "Snoring") return labels
                }
            }
        }
        throw IOException("Model Snoring label at index 38 was not verified")
    }

    private fun u16(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 255) or ((bytes[at + 1].toInt() and 255) shl 8)

    private fun u32(bytes: ByteArray, at: Int): Long =
        u16(bytes, at).toLong() or (u16(bytes, at + 2).toLong() shl 16)
}
