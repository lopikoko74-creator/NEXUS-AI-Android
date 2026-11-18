#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <mutex>
#include <string>
#include <vector>
#include "llama.h"

#define LOG_TAG "NEXUS-LLAMA"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
std::mutex g_mutex;
llama_model *g_model = nullptr;
llama_context *g_context = nullptr;
bool g_backend_initialized = false;

void free_context() {
    if (g_context != nullptr) {
        llama_free(g_context);
        g_context = nullptr;
    }
}

void free_model() {
    free_context();
    if (g_model != nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
}

void throw_java(JNIEnv *env, const char *class_name, const std::string &message) {
    jclass error = env->FindClass(class_name);
    if (error != nullptr) env->ThrowNew(error, message.c_str());
}
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
        throw_java(env, "java/lang/IllegalArgumentException", "Model path is empty");
        return nullptr;
    }
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    if (path == nullptr) return nullptr;

    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_backend_initialized) {
        llama_backend_init();
        g_backend_initialized = true;
    }
    free_model();

    llama_model_params model_params = llama_model_default_params();
    model_params.load_mode = LLAMA_LOAD_MODE_MMAP;
    g_model = llama_model_load_from_file(path, model_params);
    env->ReleaseStringUTFChars(jpath, path);

    if (g_model == nullptr) {
        throw_java(env, "java/lang/IllegalStateException", "llama.cpp could not load this GGUF model");
        return nullptr;
    }

    llama_context_params context_params = llama_context_default_params();
    context_params.n_ctx = 2048;
    context_params.n_batch = 2048;
    context_params.n_ubatch = 512;
    context_params.n_threads = 4;
    context_params.n_threads_batch = 4;
    context_params.no_perf = true;
    g_context = llama_init_from_model(g_model, context_params);
    if (g_context == nullptr) {
        free_model();
        throw_java(env, "java/lang/IllegalStateException", "Could not create the inference context");
        return nullptr;
    }

    char description[256] = {};
    llama_model_desc(g_model, description, sizeof(description));
    const double billions = static_cast<double>(llama_model_n_params(g_model)) / 1.0e9;
    const std::string result = std::string(description) + " | " +
        std::to_string(billions).substr(0, 5) + "B parameters | context 2048";
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_ai_nexus_mobile_NativeLlamaEngine_nativeGenerate(JNIEnv *env, jobject, jstring jprompt, jint max_tokens) {
    if (jprompt == nullptr) {
        throw_java(env, "java/lang/IllegalArgumentException", "Prompt is empty");
        return nullptr;
    }
    const char *prompt_chars = env->GetStringUTFChars(jprompt, nullptr);
    if (prompt_chars == nullptr) return nullptr;
    const std::string prompt(prompt_chars);
    env->ReleaseStringUTFChars(jprompt, prompt_chars);

    if (prompt.empty() || prompt.size() > 24000) {
        throw_java(env, "java/lang/IllegalArgumentException", "Prompt must contain 1..24000 UTF-8 bytes");
        return nullptr;
    }
    const int token_limit = std::max(16, std::min(static_cast<int>(max_tokens), 512));

    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_model == nullptr || g_context == nullptr) {
        throw_java(env, "java/lang/IllegalStateException", "Load a GGUF model before generation");
        return nullptr;
    }

    const llama_vocab *vocab = llama_model_get_vocab(g_model);
    std::vector<llama_token> prompt_tokens(prompt.size() + 32);
    int32_t prompt_count = llama_tokenize(
        vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
        prompt_tokens.data(), static_cast<int32_t>(prompt_tokens.size()), true, false
    );
    if (prompt_count < 0) {
        prompt_tokens.resize(static_cast<size_t>(-prompt_count));
        prompt_count = llama_tokenize(
            vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
            prompt_tokens.data(), static_cast<int32_t>(prompt_tokens.size()), true, false
        );
    }
    if (prompt_count <= 0 || prompt_count > 1900) {
        throw_java(env, "java/lang/IllegalArgumentException", "Prompt exceeds the 2048-token context budget");
        return nullptr;
    }
    prompt_tokens.resize(static_cast<size_t>(prompt_count));
    llama_memory_clear(llama_get_memory(g_context), true);

    llama_batch_ext *batch = llama_batch_ext_init(g_context);
    if (batch == nullptr) {
        throw_java(env, "java/lang/IllegalStateException", "Could not allocate inference batch");
        return nullptr;
    }

    bool input_ok = true;
    for (int32_t i = 0; i < prompt_count; ++i) {
        const int32_t index = llama_batch_ext_add_token(batch, 0, prompt_tokens[static_cast<size_t>(i)]);
        if (index < 0 || !llama_batch_ext_set_pos(batch, index, reinterpret_cast<const llama_pos *>(&i))) {
            input_ok = false;
            break;
        }
        if (i == prompt_count - 1) llama_batch_ext_set_output_logits(batch, index, true);
    }
    int32_t decode_result = input_ok ? llama_process(g_context, LLAMA_PROCESS_TYPE_DECODE, batch) : -1;
    llama_batch_ext_free(batch);
    if (!input_ok || decode_result != 0) {
        throw_java(env, "java/lang/IllegalStateException", "Prompt evaluation failed in llama.cpp");
        return nullptr;
    }

    llama_sampler_chain_params sampler_params = llama_sampler_chain_default_params();
    llama_sampler *sampler = llama_sampler_chain_init(sampler_params);
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.90f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.15f));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    std::string output;
    output.reserve(static_cast<size_t>(token_limit) * 8);
    llama_token token = LLAMA_TOKEN_NULL;
    int32_t position = prompt_count;
    for (int step = 0; step < token_limit; ++step) {
        token = llama_sampler_sample(sampler, g_context, -1);
        if (llama_vocab_is_eog(vocab, token)) break;

        char piece[8192];
        int32_t piece_size = llama_token_to_piece(vocab, token, piece, sizeof(piece), 0, false);
        if (piece_size < 0) {
            std::vector<char> larger(static_cast<size_t>(-piece_size) + 1);
            piece_size = llama_token_to_piece(vocab, token, larger.data(), static_cast<int32_t>(larger.size()), 0, false);
            if (piece_size > 0) output.append(larger.data(), static_cast<size_t>(piece_size));
        } else if (piece_size > 0) {
            output.append(piece, static_cast<size_t>(piece_size));
        }

        if (position >= 2047) break;
        batch = llama_batch_ext_init(g_context);
        if (batch == nullptr) break;
        const int32_t index = llama_batch_ext_add_token(batch, 0, token);
        const llama_pos pos = position;
        const bool added = index >= 0 && llama_batch_ext_set_pos(batch, index, &pos)
            && llama_batch_ext_set_output_logits(batch, index, true);
        decode_result = added ? llama_process(g_context, LLAMA_PROCESS_TYPE_DECODE, batch) : -1;
        llama_batch_ext_free(batch);
        if (decode_result != 0) break;
        ++position;
    }

    llama_sampler_free(sampler);
    if (output.empty()) {
        throw_java(env, "java/lang/IllegalStateException", "The model generated an empty response");
        return nullptr;
    }
    return env->NewStringUTF(output.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_ai_nexus_mobile_NativeLlamaEngine_nativeUnloadModel(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    free_model();
}

extern "C" JNIEXPORT void JNICALL
Java_ai_nexus_mobile_NativeLlamaEngine_nativeShutdown(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    free_model();
    if (g_backend_initialized) {
        llama_backend_free();
        g_backend_initialized = false;
    }
}
