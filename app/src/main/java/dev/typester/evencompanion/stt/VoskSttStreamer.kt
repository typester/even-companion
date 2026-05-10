package dev.typester.evencompanion.stt

import android.content.Context
import dev.typester.evencompanion.core.EvenCore
import dev.typester.evencompanion.core.uniffi.Language
import dev.typester.evencompanion.core.uniffi.SttStreamer
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class VoskSttStreamer(private val context: Context) : SttStreamer {

    private data class ModelKey(val language: Language, val size: VoskModelSize)

    private data class SessionEntry(
        val recognizer: Recognizer,
        val executor: ExecutorService,
    )

    private val modelLock = Any()
    private val models = ConcurrentHashMap<ModelKey, Model>()
    private val sessions = ConcurrentHashMap<String, SessionEntry>()

    fun preload() {
        val manager = VoskModelManager.get(context)
        for (language in Language.values()) {
            if (manager.isAnyReady(language)) {
                getOrLoadModel(language)
            }
        }
    }

    override fun isLanguageReady(language: Language): Boolean =
        VoskModelManager.get(context).isAnyReady(language)

    override fun startSession(sessionId: String, language: Language) {
        val model = getOrLoadModel(language)
        val recognizer = Recognizer(model, 16000.0f)
        val executor = Executors.newSingleThreadExecutor()
        sessions[sessionId] = SessionEntry(recognizer, executor)
    }

    override fun endSession(sessionId: String) {
        sessions.remove(sessionId)?.let { entry ->
            entry.executor.shutdown()
            entry.recognizer.close()
        }
    }

    override fun feedAudio(sessionId: String, pcm: ByteArray) {
        val entry = sessions[sessionId] ?: return
        entry.executor.execute {
            val complete = entry.recognizer.acceptWaveForm(pcm, pcm.size)
            if (complete) {
                val text = JSONObject(entry.recognizer.result).optString("text")
                if (text.isNotEmpty()) EvenCore.instance.pushTranscript(sessionId, text, true)
            } else {
                val text = JSONObject(entry.recognizer.partialResult).optString("partial")
                if (text.isNotEmpty()) EvenCore.instance.pushTranscript(sessionId, text, false)
            }
        }
    }

    fun cleanup() {
        sessions.keys.toList().forEach { endSession(it) }
        models.values.forEach { it.close() }
        models.clear()
    }

    private fun getOrLoadModel(language: Language): Model {
        val manager = VoskModelManager.get(context)
        val actualSize = manager.effectiveSize(language)
            ?: throw IllegalStateException("model not ready: $language")
        val key = ModelKey(language, actualSize)

        models[key]?.let { return it }
        synchronized(modelLock) {
            models[key]?.let { return it }
            val path = manager.modelPath(language, actualSize)
                ?: throw IllegalStateException("model path missing: $language $actualSize")
            val model = Model(path)
            models[key] = model
            return model
        }
    }
}
