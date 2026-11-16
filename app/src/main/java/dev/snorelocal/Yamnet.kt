package dev.snorelocal

import android.content.Context
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** CPU-only bundled LiteRT; no Play Services or runtime downloads. */
class Yamnet(context: Context) : AutoCloseable {
    private val interpreter: Interpreter
    private val input = ByteBuffer.allocateDirect(WINDOW * 4).order(ByteOrder.nativeOrder())
    private val output = ByteBuffer.allocateDirect(521 * 4).order(ByteOrder.nativeOrder())
    init {
        val bytes = context.assets.open("yamnet.tflite").use { it.readBytes() }
        val expected = context.assets.open("yamnet.sha256").bufferedReader().use { it.readText().trim() }
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(expected == actual) { "Bundled classifier integrity check failed" }
        // Android ZipFile rejects this model/archive combination; read its local ZIP header directly.
        ModelLabels.read(bytes)
        val model = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); rewind() }
        val instance = Interpreter(model, Interpreter.Options().setNumThreads(1).setUseXNNPACK(true))
        try {
            val i = instance.getInputTensor(0)
            val o = instance.getOutputTensor(0)
            check(instance.inputTensorCount == 1 && instance.outputTensorCount == 1 &&
                i.dataType() == DataType.FLOAT32 && i.numElements() == WINDOW &&
                o.dataType() == DataType.FLOAT32 && o.numElements() == 521) { "Unsupported YAMNet tensor contract" }
            interpreter = instance
            score(ShortArray(WINDOW)) // Fail before microphone capture if native operators cannot run.
        } catch (t: Throwable) { instance.close(); throw t }
    }
    fun score(pcm: ShortArray): Float {
        require(pcm.size == WINDOW)
        input.rewind()
        for (s in pcm) input.putFloat(s / 32768f)
        input.rewind(); output.rewind()
        interpreter.run(input, output)
        val score = output.getFloat(38 * 4)
        check(score.isFinite() && score in 0f..1f) { "Classifier returned invalid Snoring score" }
        return score
    }
    override fun close() = interpreter.close()
    companion object { const val RATE = 16_000; const val WINDOW = 15_600; const val HOP = 7_800 }
}

/** Gate only saves inference work. Periodic ungated probes preserve quieter events. */
class SoundGate(private val sensitivity: Float) {
    private var floor = -80.0
    private var windows = 0
    fun allow(pcm: ShortArray, active: Boolean): Boolean {
        var energy = 0.0
        for (s in pcm) energy += s.toDouble() * s
        val db = 10 * kotlin.math.log10(energy / pcm.size / (32768.0 * 32768.0) + 1e-12)
        if (!active) floor += (if (db < floor) .1 else .002) * (db - floor)
        val threshold = minOf(-62.0 - 12 * sensitivity, floor + 2.0)
        return active || (++windows % 4 == 0) || db >= threshold
    }
}
