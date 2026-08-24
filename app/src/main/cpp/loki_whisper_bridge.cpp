#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include "whisper.h"

#define TAG "LokiWhisperBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

struct LokiWhisperContext {
    whisper_context* ctx = nullptr;
    int n_threads = 4;
};

extern "C" {

JNIEXPORT jlong JNICALL
Java_dev_loki_android_voice_WhisperBridge_nativeInitWhisper(
        JNIEnv* env,
        jobject /* this */,
        jstring model_path_j,
        jint n_threads) {

    const char* model_path = env->GetStringUTFChars(model_path_j, nullptr);
    LOGI("Loading Whisper model from %s (threads=%d)", model_path, n_threads);

    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;

    whisper_context* ctx = whisper_init_from_file_with_params(model_path, cparams);
    env->ReleaseStringUTFChars(model_path_j, model_path);

    if (!ctx) {
        LOGE("Failed to initialize whisper_context");
        return 0;
    }

    auto* wctx = new LokiWhisperContext();
    wctx->ctx = ctx;
    wctx->n_threads = n_threads > 0 ? n_threads : 4;

    LOGI("Whisper model initialized successfully at handle %p", wctx);
    return reinterpret_cast<jlong>(wctx);
}

JNIEXPORT void JNICALL
Java_dev_loki_android_voice_WhisperBridge_nativeFreeWhisper(
        JNIEnv* /* env */,
        jobject /* this */,
        jlong handle) {

    if (!handle) return;
    auto* wctx = reinterpret_cast<LokiWhisperContext*>(handle);
    LOGI("Freeing Whisper context at %p", wctx);

    if (wctx->ctx) {
        whisper_free(wctx->ctx);
    }
    delete wctx;
}

JNIEXPORT jstring JNICALL
Java_dev_loki_android_voice_WhisperBridge_nativeTranscribe(
        JNIEnv* env,
        jobject /* this */,
        jlong handle,
        jfloatArray pcm_floats_j,
        jint n_samples,
        jstring language_j) {

    if (!handle) {
        LOGE("Invalid handle passed to nativeTranscribe");
        return env->NewStringUTF("");
    }

    auto* wctx = reinterpret_cast<LokiWhisperContext*>(handle);

    jfloat* pcm_data = env->GetFloatArrayElements(pcm_floats_j, nullptr);
    if (!pcm_data || n_samples <= 0) {
        if (pcm_data) env->ReleaseFloatArrayElements(pcm_floats_j, pcm_data, JNI_ABORT);
        return env->NewStringUTF("");
    }

    const char* lang = language_j ? env->GetStringUTFChars(language_j, nullptr) : "en";

    whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.print_progress = false;
    wparams.print_realtime = false;
    wparams.print_timestamps = false;
    wparams.translate = false;
    wparams.language = lang;
    wparams.n_threads = wctx->n_threads;
    wparams.no_context = true;
    wparams.single_segment = true;

    LOGI("Running Whisper transcription on %d samples (%.2f seconds)", n_samples, (float)n_samples / 16000.0f);

    int ret = whisper_full(wctx->ctx, wparams, pcm_data, n_samples);

    env->ReleaseFloatArrayElements(pcm_floats_j, pcm_data, JNI_ABORT);
    if (language_j) env->ReleaseStringUTFChars(language_j, lang);

    if (ret != 0) {
        LOGE("whisper_full failed with code %d", ret);
        return env->NewStringUTF("");
    }

    std::string transcript;
    const int n_segments = whisper_full_n_segments(wctx->ctx);
    for (int i = 0; i < n_segments; ++i) {
        const char* text = whisper_full_get_segment_text(wctx->ctx, i);
        if (text) {
            transcript += text;
        }
    }

    LOGI("Whisper transcription result (%d segments): %s", n_segments, transcript.c_str());
    return env->NewStringUTF(transcript.c_str());
}

} // extern "C"
