package dev.snorelocal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ModelLabelsTest {
    @Test public void readsActualBundledFlatBufferAssociatedLabels() throws Exception {
        byte[] model = Files.readAllBytes(Path.of("src/main/assets/yamnet.tflite"));
        String[] labels = ModelLabels.read(model);
        assertEquals(521, labels.length);
        assertEquals("Speech", labels[0]);
        assertEquals("Snoring", labels[38]);
    }
    @Test public void readsArchiveWithOffsetsRelativeToItsOwnStart() throws Exception {
        byte[] model = Files.readAllBytes(Path.of("src/main/assets/yamnet.tflite"));
        java.nio.ByteBuffer bytes = java.nio.ByteBuffer.wrap(model).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int end = model.length - 22;
        int central = end - bytes.getInt(end + 12);
        int local = bytes.getInt(central + 42);
        bytes.putInt(central + 42, 0);
        bytes.putInt(end + 16, central - local);
        assertEquals("Snoring", ModelLabels.read(model)[38]);
    }
    @Test public void rejectsMissingArchive() {
        assertThrows(IOException.class, () -> ModelLabels.read(new byte[100]));
    }
    @Test public void rejectsCorruptArchiveOffsets() throws Exception {
        byte[] model = Files.readAllBytes(Path.of("src/main/assets/yamnet.tflite"));
        int end = model.length - 22;
        for (int i = 12; i < 20; i++) model[end + i] = (byte) 0xff;
        assertThrows(IOException.class, () -> ModelLabels.read(model));
    }
}
