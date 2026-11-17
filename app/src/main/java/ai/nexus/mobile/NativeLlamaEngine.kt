package ai.nexus.mobile

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Thin Android bridge to the pinned llama.cpp native runtime.
 * Model loading is intentionally explicit: loading a multi-GB GGUF consumes substantial RAM.
 */
class NativeLlamaEngine private constructor(context: Context) {
    private val appContext = context.applicationContext
    @Volatile private var initialized = false
    @Volatile private var loadedModelPath: String? = null

    private external fun nativeInitialize()
    private external fun nativeLoadModel(path: String): String
    private external fun nativeUnloadModel()
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

    suspend fun loadModel(file: File): String = withContext(Dispatchers.IO) {
        check(file.isFile && file.canRead()) { "GGUF-файл недоступен для чтения" }
        initialize()
        val info = nativeLoadModel(file.absolutePath)
        loadedModelPath = file.absolutePath
        info
    }

    suspend fun unloadModel() = withContext(Dispatchers.IO) {
        if (initialized && loadedModelPath != null) {
            nativeUnloadModel()
            loadedModelPath = null
        }
    }

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
