#include <jni.h>
#include <libusb.h>
#include <libuvc.h>
#include <android/log.h>
#include <unistd.h>
#include "thermal_camera.h"

#define  LOG_TAG    "ThermalCamera"
#define  LOGD(...)  __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// Helper: open libusb device from fd, returns 0 on success
static int open_usb_device(int fd, libusb_context **ctx, libusb_device_handle **devh) {
    *ctx = NULL;
    *devh = NULL;
    int r = libusb_init(ctx);
    if (r != LIBUSB_SUCCESS) {
        LOGD("libusb_init failed: %d", r);
        return -1;
    }
    r = libusb_wrap_sys_device(*ctx, fd, devh);
    if (r != LIBUSB_SUCCESS) {
        LOGD("libusb_wrap failed: %d", r);
        libusb_exit(*ctx);
        *ctx = NULL;
        return -1;
    }
    return 0;
}

// Helper: check if camera is ready (status register bits 0-1 == 0)
static int check_camera_ready(libusb_device_handle *devh) {
    uint8_t status = 0;
    int r = libusb_control_transfer(devh, 0xC1, 0x44, 0x78, 0x200, &status, 1, 1000);
    if (r < 0) return -1;
    return (status & 0x03) == 0 ? 1 : 0;
}

// Helper: block until camera is ready, with timeout in ms. Returns 0 on ready, -1 on timeout/error.
static int block_until_ready(libusb_device_handle *devh, int timeout_ms) {
    int elapsed = 0;
    while (elapsed < timeout_ms) {
        int ready = check_camera_ready(devh);
        if (ready < 0) return -1;
        if (ready) return 0;
        usleep(1000); // 1ms
        elapsed++;
    }
    LOGD("block_until_ready: timed out after %dms", timeout_ms);
    return -1;
}

// Helper: long command write (8-byte header to 0x9D00, then data to 0x1D08, then poll ready)
static int long_cmd_write(libusb_device_handle *devh, uint8_t *header, int header_len,
                          uint8_t *data, int data_len, int timeout_ms) {
    int r = libusb_control_transfer(devh, 0x41, 0x45, 0x78, 0x9D00, header, header_len, 1000);
    if (r < 0) {
        LOGD("long_cmd_write: header transfer failed: %d", r);
        return -1;
    }
    r = libusb_control_transfer(devh, 0x41, 0x45, 0x78, 0x1D08, data, data_len, 1000);
    if (r < 0) {
        LOGD("long_cmd_write: data transfer failed: %d", r);
        return -1;
    }
    return block_until_ready(devh, timeout_ms);
}

// Helper: long command read (8 bytes to 0x9D00, 8 bytes to 0x1D08, poll ready, read from 0x1D10)
static int long_cmd_read(libusb_device_handle *devh, uint8_t *header, int header_len,
                         uint8_t *params, int params_len,
                         uint8_t *result, int result_len, int timeout_ms) {
    int r = libusb_control_transfer(devh, 0x41, 0x45, 0x78, 0x9D00, header, header_len, 1000);
    if (r < 0) {
        LOGD("long_cmd_read: header transfer failed: %d", r);
        return -1;
    }
    r = libusb_control_transfer(devh, 0x41, 0x45, 0x78, 0x1D08, params, params_len, 1000);
    if (r < 0) {
        LOGD("long_cmd_read: params transfer failed: %d", r);
        return -1;
    }
    r = block_until_ready(devh, timeout_ms);
    if (r < 0) return -1;
    r = libusb_control_transfer(devh, 0xC1, 0x44, 0x78, 0x1D10, result, result_len, 1000);
    if (r < 0) {
        LOGD("long_cmd_read: result read failed: %d", r);
        return -1;
    }
    return 0;
}


JNIEXPORT jlong JNICALL Java_info_jnlm_thermal_1camera_MainActivity_initializeStream(JNIEnv *env, jobject thiz, jint fd) {
    int r = 0;
    r = libusb_set_option(NULL, LIBUSB_OPTION_NO_DEVICE_DISCOVERY, NULL);
    if (r != LIBUSB_SUCCESS) {
        LOGD("libusb_set_option failed: %d\n", r);
        return 0;
    }
	uvc_context_t *uvc_ctx = NULL;
	r = uvc_init(&uvc_ctx, NULL);
	
	if (r < 0) {
		LOGD("failed uvc_init: %s (%d)", uvc_strerror((uvc_error)r), r);
		return 0;
	}

	uvc_device_handle_t *uvc_devh;
	r = uvc_wrap(fd, uvc_ctx, &uvc_devh);
	if (r < 0) {
		LOGD("failed uvc_wrap: %s (%d)", uvc_strerror((uvc_error)r), r);
		return 0;
	}

	uvc_stream_ctrl_t ctrl;

	r = uvc_get_stream_ctrl_format_size(uvc_devh, &ctrl, UVC_FRAME_FORMAT_ANY, 256, 384, 25);

	
	if (r < 0) {
		LOGD("failed stream negotiation: %s (%d)", uvc_strerror((uvc_error)r), r);
		return 0;
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
		return 0;
	}

	r = uvc_stream_start(strmh, NULL, NULL, 0);
	if (r < 0) {
		LOGD("failed to start stream: %s (%d)", uvc_strerror((uvc_error)r), r);
		return 0;
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
	
	jbyteArray result = env->NewByteArray(frame->data_bytes);
    if (result == nullptr) {
        return nullptr;
    }
	
	env->SetByteArrayRegion(result, 0, frame->data_bytes, (jbyte*)frame->data);

	return result;
}


JNIEXPORT void JNICALL Java_info_jnlm_thermal_1camera_MainActivity_sendCtrl(JNIEnv *env, jobject thiz, jint fd, jint color){ 
	libusb_context *ctx = NULL;
    libusb_device_handle *devh = NULL;

    int r = 0;
	r = libusb_init(&ctx);
    if (r != LIBUSB_SUCCESS) {
        LOGD("libusb_init failed: %d\n", r);
    }

	r = libusb_wrap_sys_device(ctx, fd, &devh);
    if (r != LIBUSB_SUCCESS) {
        LOGD("libusb_wrap failed: %d\n", r);
    }
	
	uint8_t data_0[] = {0x09,0xc4,0x00,0x00,0x00,0x00,0x00,0x01};	
	r = libusb_control_transfer(devh, 0x41, 0x45, 0x78, 0x9d00, data_0, sizeof(data_0), 1000);
    if (r < LIBUSB_SUCCESS) {
        LOGD("libusb ctrl_xfr1 failed: %d\n", r);
    }
	uint8_t data_1[1];	
	data_1[0] = color;
	r = libusb_control_transfer(devh, 0x41, 0x45, 0x78, 0x1d08, data_1, sizeof(data_1), 1000);
    if (r < LIBUSB_SUCCESS) {
        LOGD("libusb ctrl_xfr1 failed: %d\n", r);
    }

	uint8_t data[] = {0x09,0x84,0x00,0x00,0x00,0x00,0x00,0x01};
	r = libusb_control_transfer(devh, 0x41, 0x45, 0x78, 0x1d00, data, sizeof(data), 1000);
    if (r < LIBUSB_SUCCESS) {
        LOGD("libusb ctrl_xfr1 failed: %d\n", r);
    }

	uint8_t data_read[26] = {0};
	r = libusb_control_transfer(devh, 0xC1, 0x44, 0x78, 0x200, data_read, 1, 1000);
	if (r < LIBUSB_SUCCESS) {
		LOGD("libusb ctrl_xfr1 failed: %d\n", r);
	}
	LOGD("read status %d", data_read[0]);

	r = libusb_control_transfer(devh, 0xC1, 0x44, 0x78, 0x1d08, data_read, 1, 1000);
	if (r < LIBUSB_SUCCESS) {
		LOGD("libusb ctrl_xfr1 failed: %d\n", r);
	}
	for (int i=0;i<26;i++){
		LOGD("read %02x", data_read[i]);
	}

}

JNIEXPORT jint JNICALL Java_info_jnlm_thermal_1camera_MainActivity_setGainMode(JNIEnv *env, jobject thiz, jint fd, jint gain) {
    libusb_context *ctx = NULL;
    libusb_device_handle *devh = NULL;
    if (open_usb_device(fd, &ctx, &devh) < 0) return -1;

    // Long command write: SET prop_tpd_params (0x8514 | 0x4000 = 0xC514), param_idx=5 (GAIN_SEL), value=gain
    // data1 to 0x9D00: cmd as LE u16, param_idx as BE u16, value as BE u32
    uint8_t header[] = {0x14, 0xC5, 0x00, 0x05, 0x00, 0x00, 0x00, (uint8_t)gain};
    // data2 to 0x1D08: p3=0, p4=0
    uint8_t params[] = {0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};

    LOGD("setGainMode: setting gain to %d", gain);
    int r = long_cmd_write(devh, header, sizeof(header), params, sizeof(params), 5000);
    if (r < 0) {
        LOGD("setGainMode: failed");
        libusb_exit(ctx);
        return -1;
    }
    LOGD("setGainMode: success, gain=%d", gain);
    libusb_exit(ctx);
    return 0;
}

JNIEXPORT jint JNICALL Java_info_jnlm_thermal_1camera_MainActivity_getGainMode(JNIEnv *env, jobject thiz, jint fd) {
    libusb_context *ctx = NULL;
    libusb_device_handle *devh = NULL;
    if (open_usb_device(fd, &ctx, &devh) < 0) return -1;

    // Long command read: GET prop_tpd_params (0x8514), param_idx=5 (GAIN_SEL)
    // data1 to 0x9D00: cmd as LE u16, param_idx as BE u16, value as BE u32 (0)
    uint8_t header[] = {0x14, 0x85, 0x00, 0x05, 0x00, 0x00, 0x00, 0x00};
    // data2 to 0x1D08: p3=0, data_len=2
    uint8_t params[] = {0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x02};
    uint8_t result[2] = {0};

    LOGD("getGainMode: reading current gain");
    int r = long_cmd_read(devh, header, sizeof(header), params, sizeof(params),
                          result, sizeof(result), 5000);
    if (r < 0) {
        LOGD("getGainMode: failed");
        libusb_exit(ctx);
        return -1;
    }
    // Result is big-endian u16
    int gain = (result[0] << 8) | result[1];
    LOGD("getGainMode: current gain=%d", gain);
    libusb_exit(ctx);
    return gain;
}

JNIEXPORT jint JNICALL Java_info_jnlm_thermal_1camera_MainActivity_triggerShutter(JNIEnv *env, jobject thiz, jint fd) {
    libusb_context *ctx = NULL;
    libusb_device_handle *devh = NULL;
    if (open_usb_device(fd, &ctx, &devh) < 0) return -1;

    // Read current gain, then set it again to trigger NUC
    uint8_t read_header[] = {0x14, 0x85, 0x00, 0x05, 0x00, 0x00, 0x00, 0x00};
    uint8_t read_params[] = {0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x02};
    uint8_t result[2] = {0};

    LOGD("triggerShutter: reading current gain for re-set");
    int r = long_cmd_read(devh, read_header, sizeof(read_header), read_params, sizeof(read_params),
                          result, sizeof(result), 5000);
    if (r < 0) {
        LOGD("triggerShutter: failed to read gain");
        libusb_exit(ctx);
        return -1;
    }
    int gain = (result[0] << 8) | result[1];
    LOGD("triggerShutter: current gain=%d, re-setting to trigger NUC", gain);

    // Set same gain value to trigger NUC
    uint8_t write_header[] = {0x14, 0xC5, 0x00, 0x05, 0x00, 0x00, 0x00, (uint8_t)gain};
    uint8_t write_params[] = {0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
    r = long_cmd_write(devh, write_header, sizeof(write_header), write_params, sizeof(write_params), 5000);
    if (r < 0) {
        LOGD("triggerShutter: failed to re-set gain");
        libusb_exit(ctx);
        return -1;
    }
    LOGD("triggerShutter: NUC triggered successfully");
    libusb_exit(ctx);
    return 0;
}
