#include <jni.h>
#include <string>
#include "GwAudioEngine.h"
namespace { jstring jstr(JNIEnv* e,const std::string& s){return e->NewStringUTF(s.c_str());} }
extern "C" JNIEXPORT jstring JNICALL Java_kz_glasswave_player_GwNativeAudio_nativeLoadWav(JNIEnv* e,jobject,jint fd,jlong offset,jlong length,jint device,jboolean exclusive,jlong startMs,jboolean autoStart){return jstr(e,GwAudioEngine::instance().loadWav(fd,offset,length,device,exclusive==JNI_TRUE,startMs,autoStart==JNI_TRUE));}
extern "C" JNIEXPORT jstring JNICALL Java_kz_glasswave_player_GwNativeAudio_nativeSeek(JNIEnv* e,jobject,jlong targetMs,jboolean resume){return jstr(e,GwAudioEngine::instance().seek(targetMs,resume==JNI_TRUE));}
extern "C" JNIEXPORT jstring JNICALL Java_kz_glasswave_player_GwNativeAudio_nativeStart(JNIEnv* e,jobject){return jstr(e,GwAudioEngine::instance().start());}
extern "C" JNIEXPORT jstring JNICALL Java_kz_glasswave_player_GwNativeAudio_nativeStop(JNIEnv* e,jobject){return jstr(e,GwAudioEngine::instance().stop());}
extern "C" JNIEXPORT jstring JNICALL Java_kz_glasswave_player_GwNativeAudio_nativeClose(JNIEnv* e,jobject){return jstr(e,GwAudioEngine::instance().close());}
extern "C" JNIEXPORT jstring JNICALL Java_kz_glasswave_player_GwNativeAudio_nativeStatus(JNIEnv* e,jobject){return jstr(e,GwAudioEngine::instance().status());}
