package ai.nexus.mobile

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File

/**
 * Thin Android bridge to the pinned llama.cpp runtime.
 * Model loading is explicit because a multi-GB GGUF consumes substantial RAM.
 */
class NativeLlamaEngine private constructor(context: Context) {
    private val appContext = context.applicationContext
    @Volatile private var initialized = false
    @Volatile private var loadedModelPath: String? = null

    private external fun nativeInitialize()
    private external fun nativeLoadModel(path: String): String
    private external fun nativeGenerate(prompt: String, maxTokens: Int): String
    private external fun nativeUnloadModel()
    private external fun nativeCancel()
    private external fun nativeLoadProgress(): Float
    private external fun nativeShutdown()

    suspend fun initialize() = withContext(Dispatchers.IO) {
        if (!initialized) {
            synchronized(this@NativeLlamaEngine) {
                if (!initialized) {
                    System.loadLibrary("nexus-llama")
                    nativeInitialize()
                    initialized = true
                }
            }
        }
    }

    suspend fun loadModel(file: File, onProgress: (Float) -> Unit = {}): String = coroutineScope {
        check(file.isFile && file.canRead()) { "GGUF-файл недоступен для чтения" }
        initialize()
        loadedModelPath = null
        onProgress(0f)
        val monitor = launch {
            while (isActive) {
                onProgress(nativeLoadProgress().coerceIn(0f, 1f))
                delay(200)
            }
        }
        try {
            val info = withContext(Dispatchers.IO) { nativeLoadModel(file.absolutePath) }
            loadedModelPath = file.absolutePath
            onProgress(1f)
            info
        } finally {
            monitor.cancel()
        }
    }

    /**
     * Runs inference entirely on-device. No network client or remote endpoint is used.
     * Output is deliberately capped because the agent expects a compact structured plan.
     */
    suspend fun generate(prompt: String, maxTokens: Int = 384): String = withContext(Dispatchers.IO) {
        require(prompt.isNotBlank()) { "Пустой запрос к модели" }
        require(prompt.length <= 8_000) { "Запрос слишком длинный для локального контекста (лимит 8000 символов)" }
        check(loadedModelPath != null) { "Сначала загрузи локальную GGUF-модель" }
        nativeGenerate(prompt, maxTokens.coerceIn(16, 160)).trim()
    }

    /**
     * Interrupts model loading or token generation before waiting for the native
     * mutex, then frees the context and mapped model weights.
     */
    suspend fun cancelAndUnload() = withContext(Dispatchers.IO) {
        if (initialized) {
            nativeCancel()
            nativeUnloadModel()
        }
        loadedModelPath = null
    }

    suspend fun unloadModel() = cancelAndUnload()

    suspend fun shutdown() = withContext(Dispatchers.IO) {
        if (initialized) {
            nativeShutdown()
            loadedModelPath = null
            initialized = false
        }
    }

    fun isModelLoaded(): Boolean = loadedModelPath != null

    companion object {
        @Volatile private var instance: NativeLlamaEngine? = null

        fun getInstance(context: Context): NativeLlamaEngine =
            instance ?: synchronized(this) {
                instance ?: NativeLlamaEngine(context).also { instance = it }
            }
    }
}
