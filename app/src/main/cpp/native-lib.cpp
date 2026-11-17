#include <jni.h>
#include <android/log.h>
#include <mutex>
#include <string>
#include "llama.h"

#define LOG_TAG "NEXUS-LLAMA"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
std::mutex g_mutex;
llama_model *g_model = nullptr;
bool g_backend_initialized = false;
}

extern "C" JNIEXPORT void JNICALL
Java_ai_nexus_mobile_NativeLlamaEngine_nativeInitialize(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_backend_initialized) {
        llama_backend_init();
        g_backend_initialized = true;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_ai_nexus_mobile_NativeLlamaEngine_nativeLoadModel(JNIEnv *env, jobject, jstring jpath) {
    if (jpath == nullptr) {
        jclass error = env->FindClass("java/lang/IllegalArgumentException");
        env->ThrowNew(error, "Model path is empty");
        return nullptr;
    }
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    if (path == nullptr) return nullptr;

    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_backend_initialized) {
        llama_backend_init();
        g_backend_initialized = true;
    }
    if (g_model != nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
    }

    llama_model_params params = llama_model_default_params();
    params.load_mode = LLAMA_LOAD_MODE_MMAP;
    g_model = llama_model_load_from_file(path, params);
    env->ReleaseStringUTFChars(jpath, path);

    if (g_model == nullptr) {
        jclass error = env->FindClass("java/lang/IllegalStateException");
        env->ThrowNew(error, "llama.cpp could not load this GGUF model");
        return nullptr;
    }

    char description[256] = {};
    llama_model_desc(g_model, description, sizeof(description));
    const double billions = static_cast<double>(llama_model_n_params(g_model)) / 1.0e9;
    const std::string result = std::string(description) + " | " +
        std::to_string(billions).substr(0, 5) + "B parameters";
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_ai_nexus_mobile_NativeLlamaEngine_nativeUnloadModel(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_model != nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_ai_nexus_mobile_NativeLlamaEngine_nativeShutdown(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_model != nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    if (g_backend_initialized) {
        llama_backend_free();
        g_backend_initialized = false;
    }
}
