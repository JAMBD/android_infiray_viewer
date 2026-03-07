#include <jni.h>
#ifndef _Included_info_jnlm_thermal_camera_MyActivity
#define _Included_info_jnlm_thermal_camera_MyActivity
#ifdef __cplusplus
extern "C" {
#endif
JNIEXPORT jlong JNICALL Java_info_jnlm_thermal_1camera_MainActivity_initializeStream(JNIEnv *env, jobject thiz, jint fd); 

JNIEXPORT jbyteArray JNICALL Java_info_jnlm_thermal_1camera_MainActivity_grabFrame(JNIEnv *env, jobject thiz, jlong stream);

JNIEXPORT void JNICALL Java_info_jnlm_thermal_1camera_MainActivity_sendCtrl(JNIEnv *env, jobject thiz, jint fd, jint color);

JNIEXPORT jint JNICALL Java_info_jnlm_thermal_1camera_MainActivity_setGainMode(JNIEnv *env, jobject thiz, jint fd, jint gain);

JNIEXPORT jint JNICALL Java_info_jnlm_thermal_1camera_MainActivity_getGainMode(JNIEnv *env, jobject thiz, jint fd);

JNIEXPORT jint JNICALL Java_info_jnlm_thermal_1camera_MainActivity_triggerShutter(JNIEnv *env, jobject thiz, jint fd);

#ifdef __cplusplus
}
#endif
#endif

