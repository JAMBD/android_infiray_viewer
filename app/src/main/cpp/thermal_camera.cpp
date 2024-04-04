#include <jni.h>
#include <libusb.h>
#include <libuvc.h>
#include <android/log.h>
#include "thermal_camera.h"

#define  LOG_TAG    "ThermalCamera"
#define  LOGD(...)  __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)


JNIEXPORT jlong JNICALL Java_info_jnlm_thermal_1camera_MainActivity_initializeStream(JNIEnv *env, jobject thiz, jint fd) {
	libusb_context *ctx = NULL;
    libusb_device_handle *devh = NULL;
    int r = 0;
    r = libusb_set_option(NULL, LIBUSB_OPTION_NO_DEVICE_DISCOVERY, NULL);
    if (r != LIBUSB_SUCCESS) {
        LOGD("libusb_set_option failed: %d\n", r);
        return -1;
    }
	uvc_context_t *uvc_ctx = NULL;
	r = uvc_init(&uvc_ctx, NULL);
	
	if (r < 0) {
		LOGD("failed uvc_init: %s (%d)", uvc_strerror((uvc_error)r), r);
		return -1;
	}

	uvc_device_handle_t *uvc_devh;
	r = uvc_wrap(fd, uvc_ctx, &uvc_devh);
	if (r < 0) {
		LOGD("failed uvc_wrap: %s (%d)", uvc_strerror((uvc_error)r), r);
		return -1;
	}

	uvc_stream_ctrl_t ctrl;

	r = uvc_get_stream_ctrl_format_size(uvc_devh, &ctrl, UVC_FRAME_FORMAT_ANY, 256, 384, 25);

	
	if (r < 0) {
		LOGD("failed stream negotiation: %s (%d)", uvc_strerror((uvc_error)r), r);
		return -1;
	}
	
	LOGD("bmHint: %u", ctrl.bmHint);
	LOGD("bFormatIndex: %u", static_cast<unsigned>(ctrl.bFormatIndex));
	LOGD("bFrameIndex: %u", static_cast<unsigned>(ctrl.bFrameIndex));
	LOGD("dwFrameInterval: %u", ctrl.dwFrameInterval);
	LOGD("wKeyFrameRate: %u", ctrl.wKeyFrameRate);
	LOGD("wPFrameRate: %u", ctrl.wPFrameRate);
	LOGD("wCompQuality: %u", ctrl.wCompQuality);
	LOGD("wCompWindowSize: %u", ctrl.wCompWindowSize);
	LOGD("wDelay: %u", ctrl.wDelay);
	LOGD("dwMaxVideoFrameSize: %u", ctrl.dwMaxVideoFrameSize);
	LOGD("dwMaxPayloadTransferSize: %u", ctrl.dwMaxPayloadTransferSize);
	LOGD("dwClockFrequency: %u", ctrl.dwClockFrequency);
	LOGD("bmFramingInfo: %u", static_cast<unsigned>(ctrl.bmFramingInfo));
	LOGD("bPreferredVersion: %u", static_cast<unsigned>(ctrl.bPreferredVersion));
	LOGD("bMinVersion: %u", static_cast<unsigned>(ctrl.bMinVersion));
	LOGD("bMaxVersion: %u", static_cast<unsigned>(ctrl.bMaxVersion));
	LOGD("bInterfaceNumber: %u", static_cast<unsigned>(ctrl.bInterfaceNumber));

	uvc_stream_handle_t *strmh;

	r = uvc_stream_open_ctrl(uvc_devh, &strmh, &ctrl);
	if (r < 0) {
		LOGD("failed to open stream: %s (%d)", uvc_strerror((uvc_error)r), r);
		return -1;
	}

	r = uvc_stream_start(strmh, NULL, NULL, 0);
	if (r < 0) {
		LOGD("failed to start stream: %s (%d)", uvc_strerror((uvc_error)r), r);
		return -1;
	}
	LOGD("Stream started");
	return reinterpret_cast<jlong>(strmh);
}

JNIEXPORT jbyteArray JNICALL Java_info_jnlm_thermal_1camera_MainActivity_grabFrame(JNIEnv *env, jobject thiz, jlong stream){
	uvc_error r;
	uvc_stream_handle_t *strmh = reinterpret_cast<uvc_stream_handle_t*>(stream);
	uvc_frame_t *frame;
	r = uvc_stream_get_frame(strmh, &frame, 1000000);
	if (r < 0) {
		LOGD("failed to get frame: %s (%d)", uvc_strerror(r), r);
		return nullptr;
	}
	
	LOGD("Frame size is %dx%d %zu %zu", frame->width, frame->height, frame->data_bytes, frame->step);

	jbyteArray result = env->NewByteArray(frame->data_bytes);
    if (result == nullptr) {
        return nullptr;
    }
	
	env->SetByteArrayRegion(result, 0, frame->data_bytes, (jbyte*)frame->data);

	return result;
}
