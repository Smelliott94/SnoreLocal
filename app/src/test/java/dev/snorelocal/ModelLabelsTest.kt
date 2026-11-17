package dev.snorelocal

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.*
import org.junit.Test

class ModelLabelsTest {
    @Test fun readsActualBundledFlatBufferAssociatedLabels() {
        val model = Files.readAllBytes(Path.of("src/main/assets/yamnet.tflite"))
        val labels = ModelLabels.read(model)
        assertEquals(521, labels.size)
        assertEquals("Speech", labels[0])
        assertEquals("Snoring", labels[38])
    }

    @Test fun readsArchiveWithOffsetsRelativeToItsOwnStart() {
        val model = Files.readAllBytes(Path.of("src/main/assets/yamnet.tflite"))
        val bytes = ByteBuffer.wrap(model).order(ByteOrder.LITTLE_ENDIAN)
        val end = model.size - 22
        val central = end - bytes.getInt(end + 12)
        val local = bytes.getInt(central + 42)
        bytes.putInt(central + 42, 0)
        bytes.putInt(end + 16, central - local)
        assertEquals("Snoring", ModelLabels.read(model)[38])
    }

    @Test fun rejectsMissingArchive() {
        assertThrows(IOException::class.java) { ModelLabels.read(ByteArray(100)) }
    }

    @Test fun rejectsCorruptArchiveOffsets() {
        val model = Files.readAllBytes(Path.of("src/main/assets/yamnet.tflite"))
        val end = model.size - 22
        for (i in 12 until 20) model[end + i] = 0xff.toByte()
        assertThrows(IOException::class.java) { ModelLabels.read(model) }
    }
}
