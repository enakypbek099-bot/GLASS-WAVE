#include <jni.h>
#include <string>

#include "GwAudioEngine.h"

namespace {
jstring toJava(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

std::string fromJava(JNIEnv* env, jstring value) {
    if (!value) return {};

    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result = chars ? chars : "";

    if (chars) env->ReleaseStringUTFChars(value, chars);
    return result;
}
}

extern "C"
JNIEXPORT jstring JNICALL
Java_kz_glasswave_player_GwNativeAudio_nativeOpen(
        JNIEnv* env,
        jobject,
        jstring format,
        jint sampleRate,
        jint deviceId,
        jboolean exclusive) {
    return toJava(
        env,
        GwAudioEngine::instance().open(
            fromJava(env, format),
            sampleRate,
            deviceId,
            exclusive == JNI_TRUE
        )
    );
}

extern "C"
JNIEXPORT jstring JNICALL
Java_kz_glasswave_player_GwNativeAudio_nativeStart(
        JNIEnv* env,
        jobject) {
    return toJava(env, GwAudioEngine::instance().start());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_kz_glasswave_player_GwNativeAudio_nativeStop(
        JNIEnv* env,
        jobject) {
    return toJava(env, GwAudioEngine::instance().stop());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_kz_glasswave_player_GwNativeAudio_nativeClose(
        JNIEnv* env,
        jobject) {
    return toJava(env, GwAudioEngine::instance().close());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_kz_glasswave_player_GwNativeAudio_nativeStatus(
        JNIEnv* env,
        jobject) {
    return toJava(env, GwAudioEngine::instance().status());
}
