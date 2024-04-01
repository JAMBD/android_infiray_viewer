#include <jni.h>
#include <libusb.h>
#include <libuvc.h>
#include <android/log.h>
#include "thermal_camera.h"

#define  LOG_TAG    "ThermalCamera"
#define  LOGD(...)  __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

JNIEXPORT void JNICALL Java_info_jnlm_thermal_1camera_MainActivity_initializeLibUsb(JNIEnv *env, jobject thiz, jint fd) {

	libusb_context *ctx = NULL;
    libusb_device_handle *devh = NULL;
    int r = 0;
    r = libusb_set_option(NULL, LIBUSB_OPTION_NO_DEVICE_DISCOVERY, NULL);
    if (r != LIBUSB_SUCCESS) {
        LOGD("libusb_set_option failed: %d\n", r);
        return;
    }
    r = libusb_init(&ctx);
	if (r < 0) {
        LOGD("libusb_init failed: %d\n", r);
        return;
    }
	r = libusb_wrap_sys_device(ctx, (intptr_t)fd, &devh);
    if (r < 0) {
        LOGD("libusb_wrap_sys_device failed: %d\n", r);
        return;
    } else if (devh == NULL) {
        LOGD("libusb_wrap_sys_device returned invalid handle\n");
        return;
    }
	
	libusb_device *dev = libusb_get_device(devh);
	struct libusb_device_descriptor desc;
	
	r = libusb_get_device_descriptor(dev, &desc);
    if (r < 0) {
        LOGD("failed to get device descriptor");
        return;
    }

	LOGD("%04X:%04X", desc.idVendor, desc.idProduct);

	libusb_close(devh);

	uvc_context_t *uvc_ctx = NULL;
	r = uvc_init(&uvc_ctx, ctx);
	
	if (r < 0) {
		LOGD("failed uvc_init: %s (%d)", uvc_strerror((uvc_error)r), r);
		return;
	}

	LOGD("SUCESS!");
}
