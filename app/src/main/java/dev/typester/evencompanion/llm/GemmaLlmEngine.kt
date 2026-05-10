package dev.typester.evencompanion.llm

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import dev.typester.evencompanion.core.uniffi.LlmEngine
import dev.typester.evencompanion.core.uniffi.LlmException

class GemmaLlmEngine(private val context: Context) : LlmEngine {

    private val manager = GemmaModelManager.get(context)

    @Volatile private var engine: Engine? = null
    private val lock = Any()
    private val callLock = Any()

    override fun isReady(): Boolean = manager.isReady()

    override fun prompt(prompt: String): String {
        val path = manager.modelPath() ?: throw LlmException.NotReady()
        val eng = ensureLoaded(path)
        return try {
            synchronized(callLock) {
                eng.createConversation(
                    ConversationConfig(
                        samplerConfig = SamplerConfig(
                            topK = 40,
                            topP = 0.95,
                            temperature = 0.7,
                        ),
                    )
                ).use { conv ->
                    val msg = conv.sendMessage(prompt)
                    val text = msg.contents.contents
                        .filterIsInstance<Content.Text>()
                        .joinToString("") { it.text }
                    if (text.isEmpty()) throw LlmException.Inference("empty response")
                    text
                }
            }
        } catch (e: LlmException) {
            throw e
        } catch (e: Exception) {
            throw LlmException.Inference(e.message ?: "unknown error")
        }
    }

    fun cleanup() {
        synchronized(lock) { engine?.close(); engine = null }
    }

    private fun ensureLoaded(modelPath: String): Engine {
        engine?.let { return it }
        return synchronized(lock) {
            engine?.let { return it }
            val cfg = EngineConfig(
                modelPath = modelPath,
                backend = Backend.GPU(),
                cacheDir = context.cacheDir.path,
            )
            Engine(cfg).also {
                it.initialize()
                engine = it
            }
        }
    }
}
