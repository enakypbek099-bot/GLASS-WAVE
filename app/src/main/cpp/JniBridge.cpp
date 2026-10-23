#include <jni.h>

#include <string>

#include "GwAudioEngine.h"

namespace {

jstring jstr(
    JNIEnv* env,
    const std::string& value
) {
    return env->NewStringUTF(
        value.c_str()
    );
}

}

extern "C"
JNIEXPORT jstring JNICALL
Java_kz_glasswave_player_GwNativeAudio_nativeLoadWav(
    JNIEnv* env,
    jobject,
    jint fd,
    jlong offset,
    jlong length,
    jint device,
    jboolean exclusive,
    jlong startMs,
    jboolean autoStart
) {
    return jstr(
        env,
        GwAudioEngine::instance().loadWav(
            fd,
            offset,
            length,
            device,
            exclusive == JNI_TRUE,
            startMs,
            autoStart == JNI_TRUE
        )
    );
}

extern "C"
JNIEXPORT jstring JNICALL
Java_kz_glasswave_player_GwNativeAudio_nativeSeek(
    JNIEnv* env,
    jobject,
    jlong targetMs,
    jboolean resume
) {
    return jstr(
        env,
        GwAudioEngine::instance().seek(
            targetMs,
            resume == JNI_TRUE
        )
    );
}

extern "C"
JNIEXPORT jstring JNICALL
Java_kz_glasswave_player_GwNativeAudio_nativeStart(
    JNIEnv* env,
    jobject
) {
    return jstr(
        env,
        GwAudioEngine::instance().start()
    );
}

extern "C"
JNIEXPORT jstring JNICALL
Java_kz_glasswave_player_GwNativeAudio_nativeStop(
    JNIEnv* env,
    jobject
) {
    return jstr(
        env,
        GwAudioEngine::instance().stop()
    );
}

extern "C"
JNIEXPORT jstring JNICALL
Java_kz_glasswave_player_GwNativeAudio_nativeClose(
    JNIEnv* env,
    jobject
) {
    return jstr(
        env,
        GwAudioEngine::instance().close()
    );
}

extern "C"
JNIEXPORT jstring JNICALL
Java_kz_glasswave_player_GwNativeAudio_nativeStatus(
    JNIEnv* env,
    jobject
) {
    return jstr(
        env,
        GwAudioEngine::instance().status()
    );
}
