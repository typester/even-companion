package dev.typester.evencompanion.stt

import android.content.Context
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import dev.typester.evencompanion.core.EvenCore
import dev.typester.evencompanion.core.uniffi.Language
import dev.typester.evencompanion.core.uniffi.SttStreamer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class SherpaOnnxSttStreamer(private val context: Context) : SttStreamer {

    private data class SessionEntry(
        val stream: OnlineStream,
        val executor: ExecutorService,
        var lastPartial: String = "",
    )

    private val modelLock = Any()
    private var recognizer: OnlineRecognizer? = null
    private val sessions = ConcurrentHashMap<String, SessionEntry>()

    fun preload() {
        if (SherpaModelManager.get(context).isReady(Language.EN)) {
            getOrLoadRecognizer()
        }
    }

    override fun isLanguageReady(language: Language): Boolean =
        language == Language.EN && SherpaModelManager.get(context).isReady(Language.EN)

    override fun startSession(sessionId: String, language: Language) {
        if (language != Language.EN) return
        val rec = getOrLoadRecognizer() ?: return
        val stream = rec.createStream("")
        val executor = Executors.newSingleThreadExecutor()
        sessions[sessionId] = SessionEntry(stream, executor)
    }

    override fun endSession(sessionId: String) {
        sessions.remove(sessionId)?.let { entry ->
            entry.executor.shutdown()
            entry.stream.release()
        }
    }

    override fun feedAudio(sessionId: String, pcm: ByteArray) {
        val entry = sessions[sessionId] ?: return
        val rec = recognizer ?: return
        entry.executor.execute {
            val samples = pcmToFloats(pcm)
            entry.stream.acceptWaveform(samples, 16000)
            while (rec.isReady(entry.stream)) {
                rec.decode(entry.stream)
            }
            val text = rec.getResult(entry.stream).text.trim()
            if (rec.isEndpoint(entry.stream)) {
                if (text.isNotEmpty()) {
                    EvenCore.instance.pushTranscript(sessionId, text, true)
                }
                rec.reset(entry.stream)
                entry.lastPartial = ""
            } else if (text.isNotEmpty() && text != entry.lastPartial) {
                EvenCore.instance.pushTranscript(sessionId, text, false)
                entry.lastPartial = text
            }
        }
    }

    fun cleanup() {
        sessions.keys.toList().forEach { endSession(it) }
        synchronized(modelLock) {
            recognizer?.release()
            recognizer = null
        }
    }

    private fun getOrLoadRecognizer(): OnlineRecognizer? {
        recognizer?.let { return it }
        synchronized(modelLock) {
            recognizer?.let { return it }
            val paths = SherpaModelManager.get(context).modelPaths(Language.EN) ?: return null
            val config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = paths.encoder,
                        decoder = paths.decoder,
                        joiner = paths.joiner,
                    ),
                    tokens = paths.tokens,
                    numThreads = 2,
                ),
                endpointConfig = EndpointConfig(
                    rule1 = EndpointRule(false, 2.4f, 0.0f),
                    rule2 = EndpointRule(false, 1.2f, 0.0f),
                    rule3 = EndpointRule(false, 0.0f, 20.0f),
                ),
                enableEndpoint = true,
                decodingMethod = "greedy_search",
                maxActivePaths = 4,
            )
            // null AssetManager = use filesystem paths directly
            val r = OnlineRecognizer(null, config)
            recognizer = r
            return r
        }
    }

    private fun pcmToFloats(pcm: ByteArray): FloatArray {
        val out = FloatArray(pcm.size / 2)
        for (i in out.indices) {
            val lo = pcm[i * 2].toInt() and 0xFF
            val hi = pcm[i * 2 + 1].toInt()
            out[i] = ((hi shl 8) or lo).toShort() / 32768f
        }
        return out
    }
}
