#include <jni.h>
#include <string>
#include <vector>
#include <atomic>
#include <stdexcept>
#include <android/log.h>
#include "llama.h"
#include "json-schema-to-grammar.h"

#define TAG "LokiLlamaBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

struct LokiLlamaContext {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    const llama_vocab* vocab = nullptr;
    std::atomic<bool> cancelled{false};
};

static void llama_log_callback(ggml_log_level level, const char * text, void * /* user_data */) {
    if (level == GGML_LOG_LEVEL_ERROR) {
        LOGE("llama: %s", text);
    } else if (level == GGML_LOG_LEVEL_WARN) {
        LOGI("llama warn: %s", text);
    } else if (level == GGML_LOG_LEVEL_INFO) {
        LOGI("llama info: %s", text);
    }
}

static jstring json_schema_to_grammar_impl(JNIEnv* env, jstring json_schema_j) {
    const char* schema_str = env->GetStringUTFChars(json_schema_j, nullptr);
    try {
        common_json schema = common_json::parse(schema_str);
        std::string grammar = json_schema_to_grammar(schema, true);
        LOGI("Converted JSON schema to GBNF grammar (%zu bytes)", grammar.size());
        env->ReleaseStringUTFChars(json_schema_j, schema_str);
        return env->NewStringUTF(grammar.c_str());
    } catch (const std::exception& e) {
        LOGE("Failed to convert JSON schema to grammar: %s", e.what());
        env->ReleaseStringUTFChars(json_schema_j, schema_str);
        return env->NewStringUTF("");
    }
}

static jlong init_model_impl(JNIEnv* env, jstring model_path_j, jint n_ctx, jint n_threads) {
    llama_log_set(llama_log_callback, nullptr);

    const char* model_path = env->GetStringUTFChars(model_path_j, nullptr);
    LOGI("Loading model from %s (n_ctx=%d, n_threads=%d)", model_path, n_ctx, n_threads);

    llama_backend_init();

    llama_model_params mparams = llama_model_default_params();
    llama_model* model = llama_model_load_from_file(model_path, mparams);
    env->ReleaseStringUTFChars(model_path_j, model_path);

    if (!model) {
        LOGE("Failed to load model from path");
        return 0;
    }

    const llama_vocab* vocab = llama_model_get_vocab(model);

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = n_ctx > 0 ? n_ctx : 2048;
    cparams.n_threads = n_threads > 0 ? n_threads : 6;
    cparams.n_threads_batch = cparams.n_threads;

    llama_context* ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        LOGE("Failed to initialize llama_context");
        llama_model_free(model);
        return 0;
    }

    auto* lctx = new LokiLlamaContext();
    lctx->model = model;
    lctx->ctx = ctx;
    lctx->vocab = vocab;

    LOGI("Model initialized successfully at handle %p", lctx);
    return reinterpret_cast<jlong>(lctx);
}

static void free_model_impl(jlong handle) {
    if (!handle) return;
    auto* lctx = reinterpret_cast<LokiLlamaContext*>(handle);
    LOGI("Freeing model context at %p", lctx);

    if (lctx->ctx) {
        llama_free(lctx->ctx);
    }
    if (lctx->model) {
        llama_model_free(lctx->model);
    }
    delete lctx;
}

static void cancel_impl(jlong handle) {
    if (!handle) return;
    auto* lctx = reinterpret_cast<LokiLlamaContext*>(handle);
    lctx->cancelled.store(true);
    LOGI("Generation cancelled for handle %p", lctx);
}

static jstring generate_impl(
        JNIEnv* env,
        jlong handle,
        jstring prompt_j,
        jstring grammar_str_j,
        jint max_tokens,
        jobject token_callback_j) {

    if (!handle) {
        LOGE("Invalid handle passed to nativeGenerate");
        return env->NewStringUTF("");
    }

    auto* lctx = reinterpret_cast<LokiLlamaContext*>(handle);
    lctx->cancelled.store(false);

    // Reset KV cache memory before each new independent generation turn
    llama_memory_t mem = llama_get_memory(lctx->ctx);
    if (mem) {
        llama_memory_clear(mem, true);
    }

    const char* prompt = env->GetStringUTFChars(prompt_j, nullptr);
    int prompt_len = static_cast<int>(strlen(prompt));

    // Tokenize prompt
    std::vector<llama_token> tokens(prompt_len + 16);
    int n_tokens = llama_tokenize(
            lctx->vocab,
            prompt,
            prompt_len,
            tokens.data(),
            static_cast<int32_t>(tokens.size()),
            true, // add_special
            true  // parse_special
    );

    if (n_tokens < 0) {
        tokens.resize(-n_tokens);
        n_tokens = llama_tokenize(
                lctx->vocab,
                prompt,
                prompt_len,
                tokens.data(),
                static_cast<int32_t>(tokens.size()),
                true,
                true
        );
    }
    tokens.resize(n_tokens);
    env->ReleaseStringUTFChars(prompt_j, prompt);

    LOGI("Tokenized prompt into %d tokens", n_tokens);

    // Prepare sampler chain
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    llama_sampler* smpl = llama_sampler_chain_init(sparams);

    // Apply GBNF grammar if provided
    if (grammar_str_j != nullptr) {
        const char* grammar_str = env->GetStringUTFChars(grammar_str_j, nullptr);
        if (strlen(grammar_str) > 0) {
            try {
                llama_sampler* g_smpl = llama_sampler_init_grammar(lctx->vocab, grammar_str, "root");
                if (g_smpl) {
                    llama_sampler_chain_add(smpl, g_smpl);
                    LOGI("Grammar sampler added successfully");
                } else {
                    LOGE("llama_sampler_init_grammar returned NULL for grammar root 'root'");
                }
            } catch (const std::exception& e) {
                LOGE("Exception parsing grammar: %s", e.what());
            } catch (...) {
                LOGE("Unknown exception while parsing grammar");
            }
        }
        env->ReleaseStringUTFChars(grammar_str_j, grammar_str);
    }

    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.1f));
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());

    // Evaluate prompt batch
    llama_batch batch = llama_batch_get_one(tokens.data(), n_tokens);
    if (llama_decode(lctx->ctx, batch) != 0) {
        LOGE("Failed to decode initial prompt batch");
        llama_sampler_free(smpl);
        return env->NewStringUTF("");
    }

    std::string response_text;
    int gen_tokens = 0;
    int limit = max_tokens > 0 ? max_tokens : 256;

    jclass callback_class = nullptr;
    jmethodID callback_method = nullptr;
    if (token_callback_j != nullptr) {
        callback_class = env->GetObjectClass(token_callback_j);
        callback_method = env->GetMethodID(callback_class, "onToken", "(Ljava/lang/String;)V");
    }

    try {
        while (gen_tokens < limit && !lctx->cancelled.load()) {
            llama_token new_token = llama_sampler_sample(smpl, lctx->ctx, -1);

            if (llama_vocab_is_eog(lctx->vocab, new_token)) {
                LOGI("End of generation token encountered");
                break;
            }

            char piece[256];
            int n_piece = llama_token_to_piece(lctx->vocab, new_token, piece, sizeof(piece), 0, true);
            if (n_piece > 0) {
                std::string piece_str(piece, n_piece);
                response_text += piece_str;

                if (token_callback_j != nullptr && callback_method != nullptr) {
                    jstring piece_j = env->NewStringUTF(piece_str.c_str());
                    env->CallVoidMethod(token_callback_j, callback_method, piece_j);
                    env->DeleteLocalRef(piece_j);
                }
            }

            gen_tokens++;
            llama_batch next_batch = llama_batch_get_one(&new_token, 1);
            if (llama_decode(lctx->ctx, next_batch) != 0) {
                LOGE("Failed to decode token");
                break;
            }
        }
    } catch (const std::exception& e) {
        LOGE("Exception during token generation: %s", e.what());
    } catch (...) {
        LOGE("Unknown exception during token generation");
    }

    llama_sampler_free(smpl);
    LOGI("Generated %d tokens: %s", gen_tokens, response_text.c_str());

    return env->NewStringUTF(response_text.c_str());
}

extern "C" {

// Support core.llm package
JNIEXPORT jstring JNICALL
Java_dev_loki_android_core_llm_LlamaBridge_nativeJsonSchemaToGrammar(
        JNIEnv* env, jobject /* this */, jstring json_schema_j) {
    return json_schema_to_grammar_impl(env, json_schema_j);
}

JNIEXPORT jlong JNICALL
Java_dev_loki_android_core_llm_LlamaBridge_nativeInitModel(
        JNIEnv* env, jobject /* this */, jstring model_path_j, jint n_ctx, jint n_threads) {
    return init_model_impl(env, model_path_j, n_ctx, n_threads);
}

JNIEXPORT void JNICALL
Java_dev_loki_android_core_llm_LlamaBridge_nativeFreeModel(
        JNIEnv* /* env */, jobject /* this */, jlong handle) {
    free_model_impl(handle);
}

JNIEXPORT void JNICALL
Java_dev_loki_android_core_llm_LlamaBridge_nativeCancel(
        JNIEnv* /* env */, jobject /* this */, jlong handle) {
    cancel_impl(handle);
}

JNIEXPORT jstring JNICALL
Java_dev_loki_android_core_llm_LlamaBridge_nativeGenerate(
        JNIEnv* env, jobject /* this */, jlong handle, jstring prompt_j, jstring grammar_str_j, jint max_tokens, jobject token_callback_j) {
    return generate_impl(env, handle, prompt_j, grammar_str_j, max_tokens, token_callback_j);
}

// Support legacy llm package
JNIEXPORT jstring JNICALL
Java_dev_loki_android_llm_LlamaBridge_nativeJsonSchemaToGrammar(
        JNIEnv* env, jobject /* this */, jstring json_schema_j) {
    return json_schema_to_grammar_impl(env, json_schema_j);
}

JNIEXPORT jlong JNICALL
Java_dev_loki_android_llm_LlamaBridge_nativeInitModel(
        JNIEnv* env, jobject /* this */, jstring model_path_j, jint n_ctx, jint n_threads) {
    return init_model_impl(env, model_path_j, n_ctx, n_threads);
}

JNIEXPORT void JNICALL
Java_dev_loki_android_llm_LlamaBridge_nativeFreeModel(
        JNIEnv* /* env */, jobject /* this */, jlong handle) {
    free_model_impl(handle);
}

JNIEXPORT void JNICALL
Java_dev_loki_android_llm_LlamaBridge_nativeCancel(
        JNIEnv* /* env */, jobject /* this */, jlong handle) {
    cancel_impl(handle);
}

JNIEXPORT jstring JNICALL
Java_dev_loki_android_llm_LlamaBridge_nativeGenerate(
        JNIEnv* env, jobject /* this */, jlong handle, jstring prompt_j, jstring grammar_str_j, jint max_tokens, jobject token_callback_j) {
    return generate_impl(env, handle, prompt_j, grammar_str_j, max_tokens, token_callback_j);
}

} // extern "C"
