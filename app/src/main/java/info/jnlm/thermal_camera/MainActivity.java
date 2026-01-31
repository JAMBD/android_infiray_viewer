package info.jnlm.thermal_camera;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.Manifest;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDeviceConnection;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.widget.Toast;
import android.util.Log;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.HashMap;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ByteOrder;

import android.net.Uri;
import android.content.ContentValues;
import android.os.Environment;
import android.provider.MediaStore;
import android.graphics.Bitmap;
import java.io.File;
import java.io.InputStream;

import android.view.WindowManager;
import android.widget.ImageView;
import java.io.IOException;
import java.io.OutputStream;
import android.util.DisplayMetrics;
import android.os.Handler;
import android.graphics.Matrix;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.view.ViewGroup;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.view.Surface;

public class MainActivity extends Activity {
	static {
		System.loadLibrary("usb-1.0");
		System.loadLibrary("uvc");
		System.loadLibrary("thermalcamera");
	}
    public native long initializeStream(int fd);
    public native byte[] grabFrame(long stream);
    public native void sendCtrl(int fd, int color);
    public native int setGainMode(int fd, int gain);
    public native int getGainMode(int fd);
    public native int triggerShutter(int fd);

	private static final String ACTION_USB_PERMISSION =
            "info.jnlm.thermal_camera.USB_PERMISSION";
	private static final int kFrameWidth = 256;
	private static final int kFrameHeight = 192;
	private static final int kPixelSize = 2;

	private int[] LUT = new int[65536];
	private long native_stream = 0;
	private byte[] last_frame;
	private float scale = 1.0f;
	private int color = 1;
	private int fd;

	private boolean isRecording = false;
	private String videoFilename = "invalid";
	private int centerPixelRaw = 0;

	// Color scale mode: 0=Auto, 1=Manual Center ±Range, 2=Manual Top/Bottom
	private int colorScaleMode = 0;
	private float manualRangeDegrees = 2.0f;
	private float manualTopDegrees = 40.0f;
	private float manualBottomDegrees = 20.0f;

	// Overlay settings
	private boolean showCenterCrosshair = true;
	private boolean showMinMaxOverlay = true;

	// Video encoding - raw thermal (low res)
	private static final int VIDEO_WIDTH_RAW = 192;  // Rotated dimensions
	private static final int VIDEO_HEIGHT_RAW = 256;
	private static final int VIDEO_BITRATE_RAW = 2000000;  // 2 Mbps
	// Video encoding - with overlay (high res, includes banner height)
	private static final int VIDEO_WIDTH_OVERLAY = 1080;
	private static final int VIDEO_HEIGHT_OVERLAY = 1504;  // 1440 + ~64 for text banner
	private static final int VIDEO_BITRATE_OVERLAY = 10000000;  // 10 Mbps
	private static final int VIDEO_FRAME_RATE = 25;
	// Current recording settings (set at start of recording)
	private int currentVideoWidth = VIDEO_WIDTH_RAW;
	private int currentVideoHeight = VIDEO_HEIGHT_RAW;
	private MediaCodec mediaCodec;
	private MediaMuxer mediaMuxer;
	private Surface inputSurface;
	private int videoTrackIndex = -1;
	private boolean muxerStarted = false;
	private long presentationTimeUs = 0;

	// Performance instrumentation
	private long perfTotalFrameTimeUs = 0;
	private long perfBitmapTimeUs = 0;
	private long perfEncodeTimeUs = 0;
	private int perfFrameCount = 0;

	private int minPixelRaw = 0, maxPixelRaw = 0;
	private int minPixelX = 0, minPixelY = 0;
	private int maxPixelX = 0, maxPixelY = 0;

	// ROI settings
	private boolean showROIOverlay = false;
	private int roiX1 = 64, roiY1 = 48;   // Top-left in frame coords (default: center quarter)
	private int roiX2 = 192, roiY2 = 144; // Bottom-right in frame coords
	private int roiMinPixelRaw = 0, roiMaxPixelRaw = 0;
	private int roiMinPixelX = 0, roiMinPixelY = 0;
	private int roiMaxPixelX = 0, roiMaxPixelY = 0;

	// Gain mode: false=high gain (narrow range, default), true=low gain (wide range)
	private boolean isLowGain = false;
	private boolean isSwitchingGain = false;

	// Overlay in saves setting
	private boolean includeOverlayInSaves = false;
	private Bitmap displayBitmapWithOverlays = null;

	// Preferences
	private static final String PREFS_NAME = "ThermalCameraPrefs";

	private void saveSettings() {
		SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
		SharedPreferences.Editor editor = prefs.edit();
		editor.putInt("color", color);
		editor.putBoolean("showCenterCrosshair", showCenterCrosshair);
		editor.putBoolean("showMinMaxOverlay", showMinMaxOverlay);
		editor.putBoolean("showROIOverlay", showROIOverlay);
		editor.putBoolean("includeOverlayInSaves", includeOverlayInSaves);
		editor.putInt("roiX1", roiX1);
		editor.putInt("roiY1", roiY1);
		editor.putInt("roiX2", roiX2);
		editor.putInt("roiY2", roiY2);
		editor.putInt("colorScaleMode", colorScaleMode);
		editor.putFloat("manualRangeDegrees", manualRangeDegrees);
		editor.putFloat("manualTopDegrees", manualTopDegrees);
		editor.putFloat("manualBottomDegrees", manualBottomDegrees);
		editor.putBoolean("isLowGain", isLowGain);
		editor.apply();
	}

	private void loadSettings() {
		SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
		color = prefs.getInt("color", 1);
		showCenterCrosshair = prefs.getBoolean("showCenterCrosshair", true);
		showMinMaxOverlay = prefs.getBoolean("showMinMaxOverlay", true);
		showROIOverlay = prefs.getBoolean("showROIOverlay", false);
		includeOverlayInSaves = prefs.getBoolean("includeOverlayInSaves", false);
		roiX1 = prefs.getInt("roiX1", 64);
		roiY1 = prefs.getInt("roiY1", 48);
		roiX2 = prefs.getInt("roiX2", 192);
		roiY2 = prefs.getInt("roiY2", 144);
		colorScaleMode = prefs.getInt("colorScaleMode", 0);
		manualRangeDegrees = prefs.getFloat("manualRangeDegrees", 2.0f);
		manualTopDegrees = prefs.getFloat("manualTopDegrees", 40.0f);
		manualBottomDegrees = prefs.getFloat("manualBottomDegrees", 20.0f);
		isLowGain = prefs.getBoolean("isLowGain", false);
	}

	private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			String action = intent.getAction();
			Log.d("ThermalCamera", "usbReceiver onReceive: " + action);
			if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
				Log.d("ThermalCamera", "Device detached, setting native_stream=0");
				native_stream = 0;
				Toast.makeText(context, "Camera disconnected", Toast.LENGTH_SHORT).show();
			} else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
				Log.d("ThermalCamera", "Device attached, calling connectCamera");
				if (connectCamera()) {
					Toast.makeText(context, "Camera connected", Toast.LENGTH_SHORT).show();
				} else {
					Log.d("ThermalCamera", "connectCamera returned false");
				}
			} else if (ACTION_USB_PERMISSION.equals(action)) {
				Log.d("ThermalCamera", "Permission callback received");
				// Permission granted callback - try connecting again
				if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
					Log.d("ThermalCamera", "Permission granted, calling connectCamera");
					if (connectCamera()) {
						Toast.makeText(context, "Camera connected", Toast.LENGTH_SHORT).show();
					}
				} else {
					Toast.makeText(context, "USB permission denied", Toast.LENGTH_SHORT).show();
				}
			}
		}
	};

	private boolean connectCamera() {
		Log.d("ThermalCamera", "connectCamera called, current native_stream=" + native_stream + ", fd=" + fd);
		// Skip if we already have an active stream (prevents double-connection from BroadcastReceiver + onNewIntent)
		if (native_stream != 0) {
			Log.d("ThermalCamera", "Already have active stream, skipping connection");
			return true;
		}
		UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
		PendingIntent permissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
		HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
		Log.d("ThermalCamera", "Device list size: " + deviceList.size());
		for (UsbDevice device : deviceList.values()) {
			Log.d("ThermalCamera", "Device: " + device.getVendorId() + ":" + device.getProductId());
			if (device.getVendorId() != 0x0BDA) continue;
			if (device.getProductId() != 0x5830) continue;

			Log.d("ThermalCamera", "Found camera, hasPermission: " + usbManager.hasPermission(device));
			if (!usbManager.hasPermission(device)) {
				Log.d("ThermalCamera", "Requesting permission...");
				usbManager.requestPermission(device, permissionIntent);
				return false;
			}

			UsbDeviceConnection usbDeviceConnection = usbManager.openDevice(device);
			Log.d("ThermalCamera", "openDevice: " + usbDeviceConnection);
			if (usbDeviceConnection == null) {
				Log.d("ThermalCamera", "openDevice returned null!");
				return false;
			}
			fd = usbDeviceConnection.getFileDescriptor();
			Log.d("ThermalCamera", "fd: " + fd);
			native_stream = initializeStream(fd);
			Log.d("ThermalCamera", "initializeStream returned: " + native_stream);
			if (native_stream == 0) {
				Log.d("ThermalCamera", "initializeStream FAILED - stream is 0");
			} else {
				Log.d("ThermalCamera", "initializeStream SUCCESS - stream is " + native_stream);
				// Apply saved color setting
				if (color > 0) {
					sendCtrl(fd, color);
				}
				// Trigger shutter (NUC) and restore gain mode on background thread
				final int connFd = fd;
				new Thread(() -> {
					Log.d("ThermalCamera", "Triggering shutter on connect...");
					triggerShutter(connFd);
					if (isLowGain) {
						Log.d("ThermalCamera", "Restoring low gain mode...");
						setGainMode(connFd, 0);
					}
				}).start();
			}
			return native_stream != 0;
		}
		Log.d("ThermalCamera", "No matching camera device found in list");
		return false;
	}

	public Bitmap bitmapARGBFromByte(byte[] data){
		int[] pixels = new int[kFrameWidth * kFrameHeight];
		final int kNumPixels = kFrameWidth * kFrameHeight;
		final int kExpectedSize = kNumPixels * kPixelSize * 2; // first half + second half
		if (data.length < kExpectedSize) {
			Log.w("ThermalCamera", "Short frame: " + data.length + " < " + kExpectedSize);
			return Bitmap.createBitmap(pixels, kFrameWidth, kFrameHeight, Bitmap.Config.ARGB_8888);
		}

		// Center pixel index (128, 96) in 256x192 frame
		final int centerX = kFrameWidth / 2;
		final int centerY = kFrameHeight / 2;
		final int centerIndex = centerY * kFrameWidth + centerX;

		float min = 1; float max = 0;
		for (int i = 0; i < kNumPixels; i++) {
			int offset = 2 * i; // 2 bytes per uint16_t
			int val_uint16_t = (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
			float val_float = val_uint16_t / 65535.0f;
			if (val_float < min) min = val_float;
			if (val_float > max) max = val_float;
		}

		// Extract values from second half of frame (where calibrated thermal data lives)
		int secondHalfOffset = kFrameWidth * kFrameHeight * kPixelSize;
		int centerOffset = secondHalfOffset + 2 * centerIndex;
		centerPixelRaw = (data[centerOffset] & 0xFF) | ((data[centerOffset + 1] & 0xFF) << 8);

		// Find min/max in calibrated thermal data
		minPixelRaw = Integer.MAX_VALUE;
		maxPixelRaw = Integer.MIN_VALUE;
		for (int i = 0; i < kNumPixels; i++) {
			int offset = secondHalfOffset + 2 * i;
			int val = (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
			if (val < minPixelRaw) {
				minPixelRaw = val;
				minPixelX = i % kFrameWidth;
				minPixelY = i / kFrameWidth;
			}
			if (val > maxPixelRaw) {
				maxPixelRaw = val;
				maxPixelX = i % kFrameWidth;
				maxPixelY = i / kFrameWidth;
			}
		}

		// Calculate ROI min/max if enabled
		if (showROIOverlay) {
			int rx1 = Math.min(roiX1, roiX2), rx2 = Math.max(roiX1, roiX2);
			int ry1 = Math.min(roiY1, roiY2), ry2 = Math.max(roiY1, roiY2);

			roiMinPixelRaw = Integer.MAX_VALUE;
			roiMaxPixelRaw = Integer.MIN_VALUE;

			for (int y = ry1; y <= ry2; y++) {
				for (int x = rx1; x <= rx2; x++) {
					int i = y * kFrameWidth + x;
					int offset = secondHalfOffset + 2 * i;
					int val = (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
					if (val < roiMinPixelRaw) {
						roiMinPixelRaw = val;
						roiMinPixelX = x;
						roiMinPixelY = y;
					}
					if (val > roiMaxPixelRaw) {
						roiMaxPixelRaw = val;
						roiMaxPixelX = x;
						roiMaxPixelY = y;
					}
				}
			}
		}

		// In manual mode, map color palette to ±manualRangeDegrees around center pixel
		if (colorScaleMode == 1) {
			float autoRange = max - min;
			if (autoRange > 0.0001f) {
				// Temperature uses raw/64-273.15, so 1 degree = 64 raw units in calibrated half
				float tempRange = (maxPixelRaw - minPixelRaw) / 64.0f;
				float degreesPerUnit = tempRange / autoRange;
				if (Math.abs(degreesPerUnit) > 0.0001f) {
					int centerVal = (data[2 * centerIndex] & 0xFF) | ((data[2 * centerIndex + 1] & 0xFF) << 8);
					float delta = manualRangeDegrees / degreesPerUnit;
					min = centerVal / 65535.0f - delta;
					max = centerVal / 65535.0f + delta;
				}
			}
		} else if (colorScaleMode == 2) {
			// Manual Top/Bottom mode: map color palette to absolute temperature range
			float autoRange = max - min;
			if (autoRange > 0.0001f) {
				float tempRange = (maxPixelRaw - minPixelRaw) / 64.0f;
				float degreesPerUnit = tempRange / autoRange;
				if (Math.abs(degreesPerUnit) > 0.0001f) {
					int centerFirstHalf = (data[2 * centerIndex] & 0xFF) | ((data[2 * centerIndex + 1] & 0xFF) << 8);
					float centerCalibDeg = centerPixelRaw / 64.0f - 273.15f;
					float bottomDelta = (manualBottomDegrees - centerCalibDeg) / degreesPerUnit;
					float topDelta = (manualTopDegrees - centerCalibDeg) / degreesPerUnit;
					min = centerFirstHalf / 65535.0f + bottomDelta;
					max = centerFirstHalf / 65535.0f + topDelta;
				}
			}
		}

		// Handle edge case where all pixels have same value (e.g. during camera init)
		float range = max - min;
		if (range < 0.0001f) {
			range = 1.0f; // Avoid division by zero
		}

		for (int i = 0; i < kNumPixels; i++) {
			int offset = 2 * i; // 2 bytes per uint16_t
			int val_uint16_t = (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);

			float val_float = (val_uint16_t / 65535.0f - min) / range;
			// Clamp to valid range in case of floating point edge cases
			if (val_float < 0.0f) val_float = 0.0f;
			if (val_float > 1.0f) val_float = 1.0f;
			val_uint16_t = (int) (val_float * 65535.0f);

			pixels[i] = LUT[val_uint16_t]; // Lookup the ARGB value from LUT
		}

		return Bitmap.createBitmap(pixels, kFrameWidth, kFrameHeight, Bitmap.Config.ARGB_8888);

	}

	private void encodeFrame(Bitmap bitmap) {
		if (!isRecording || mediaCodec == null || inputSurface == null) return;

		try {
			Canvas canvas = inputSurface.lockCanvas(null);
			canvas.drawBitmap(bitmap, 0, 0, null);
			inputSurface.unlockCanvasAndPost(canvas);

			presentationTimeUs += 1000000L / VIDEO_FRAME_RATE;

			// Drain output buffers
			drainEncoder(false);

		} catch (Exception e) {
			Log.e("ThermalCamera", "Error encoding frame", e);
		}
	}

	private void drainEncoder(boolean endOfStream) {
		if (endOfStream) {
			mediaCodec.signalEndOfInputStream();
		}

		MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
		while (true) {
			int outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, 10000);

			if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
				if (!endOfStream) break;
			} else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
				if (muxerStarted) {
					Log.w("ThermalCamera", "Format changed after muxer started");
				}
				MediaFormat newFormat = mediaCodec.getOutputFormat();
				videoTrackIndex = mediaMuxer.addTrack(newFormat);
				mediaMuxer.start();
				muxerStarted = true;
			} else if (outputBufferIndex >= 0) {
				ByteBuffer outputBuffer = mediaCodec.getOutputBuffer(outputBufferIndex);
				if (outputBuffer == null) {
					Log.e("ThermalCamera", "Output buffer is null");
					continue;
				}

				if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
					bufferInfo.size = 0;
				}

				if (bufferInfo.size > 0 && muxerStarted) {
					outputBuffer.position(bufferInfo.offset);
					outputBuffer.limit(bufferInfo.offset + bufferInfo.size);
					mediaMuxer.writeSampleData(videoTrackIndex, outputBuffer, bufferInfo);
				}

				mediaCodec.releaseOutputBuffer(outputBufferIndex, false);

				if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
					break;
				}
			}
		}
	}

	public void loadLUT() {
		int kLutSize = 65536 * 4;

		try (InputStream is = getAssets().open("colormap_lut.bin")) {
			byte[] buffer = new byte[kLutSize];
			int length = is.read(buffer);
			Log.e("ThermalCamera", "loaded this many bytes: " + length);
			assert length == kLutSize;
			ByteBuffer byteBuffer = ByteBuffer.wrap(buffer);
			byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
			IntBuffer intBuffer = byteBuffer.asIntBuffer();
			intBuffer.get(LUT); // Read the buffer into LUT directly

			for (int i = 0; i < 65536; i++) {
				if(i % 1337 == 0) {
					Log.i("ThermalCamera", i + ", " + LUT[i] + ", " + String.format("0x%08X", LUT[i]));
				}
			}

			Log.i("ThermalCamera", "LUT file copied to local memory.");
		} catch (IOException e) {
			Log.e("ThermalCamera", "Error copying LUT file", e);
		}
	}

	public void startRecording(View view) {
		String fileName = "thermal_camera_"
				+ new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".mp4";

		// Create file in Pictures/thermal_camera directory
		File outputDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "thermal_camera");
		if (!outputDir.exists()) {
			outputDir.mkdirs();
		}
		File outputFile = new File(outputDir, fileName);
		videoFilename = outputFile.getAbsolutePath();

		try {
			// Set video dimensions and bitrate based on overlay setting
			if (includeOverlayInSaves) {
				currentVideoWidth = VIDEO_WIDTH_OVERLAY;
				currentVideoHeight = VIDEO_HEIGHT_OVERLAY;
			} else {
				currentVideoWidth = VIDEO_WIDTH_RAW;
				currentVideoHeight = VIDEO_HEIGHT_RAW;
			}
			int bitrate = includeOverlayInSaves ? VIDEO_BITRATE_OVERLAY : VIDEO_BITRATE_RAW;

			// Set up MediaCodec for H.264 encoding
			MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, currentVideoWidth, currentVideoHeight);
			format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
			format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
			format.setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FRAME_RATE);
			format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

			mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
			mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
			inputSurface = mediaCodec.createInputSurface();
			mediaCodec.start();

			// Set up MediaMuxer
			mediaMuxer = new MediaMuxer(videoFilename, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
			videoTrackIndex = -1;
			muxerStarted = false;
			presentationTimeUs = 0;

			findViewById(R.id.startVideoButton).setEnabled(false);
			findViewById(R.id.stopVideoButton).setEnabled(true);
			Toast.makeText(this, "Started recording", Toast.LENGTH_SHORT).show();
			isRecording = true;

		} catch (IOException e) {
			Log.e("ThermalCamera", "Failed to start recording", e);
			Toast.makeText(this, "Failed to start recording: " + e.getMessage(), Toast.LENGTH_SHORT).show();
		}
	}

	public void stopRecording(View view) {
		isRecording = false;
		try {
			// Signal end of stream and drain remaining data
			if (mediaCodec != null) {
				drainEncoder(true);
				mediaCodec.stop();
				mediaCodec.release();
				mediaCodec = null;
			}

			if (inputSurface != null) {
				inputSurface.release();
				inputSurface = null;
			}

			if (mediaMuxer != null) {
				if (muxerStarted) {
					mediaMuxer.stop();
				}
				mediaMuxer.release();
				mediaMuxer = null;
			}

			// Make it show up in gallery
			File file = new File(videoFilename);
			Intent intent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
			intent.setData(Uri.fromFile(file));
			this.sendBroadcast(intent);

			Toast.makeText(this, "Video saved: " + videoFilename, Toast.LENGTH_SHORT).show();

		} catch (Exception e) {
			Log.e("ThermalCamera", "Error stopping recording", e);
			Toast.makeText(this, "Error stopping recording: " + e.getMessage(), Toast.LENGTH_SHORT).show();
		}

		findViewById(R.id.startVideoButton).setEnabled(true);
		findViewById(R.id.stopVideoButton).setEnabled(false);
	}

	private final Handler handler = new Handler();
	private int frameCount = 0;
    private final Runnable runnable = new Runnable() {
        @Override
        public void run() {
            handler.postDelayed(this, 50); // Schedule the task to run again in 1 second
			if (native_stream == 0) {
				if (frameCount % 100 == 0) {
					Log.d("ThermalCamera", "runnable: native_stream is 0, skipping frame grab");
				}
				frameCount++;
				return;
			}
			last_frame = grabFrame(native_stream);
			if (last_frame == null) {
				Log.d("ThermalCamera", "runnable: grabFrame returned null for stream " + native_stream);
				return;
			}
			if (frameCount % 100 == 0) {
				Log.d("ThermalCamera", "runnable: got frame, length=" + last_frame.length + ", stream=" + native_stream);
			}
			frameCount++;
			long frameStartUs = System.nanoTime() / 1000;
			long bitmapStartUs = frameStartUs;
			Bitmap bitmap = bitmapARGBFromByte(last_frame);
			Bitmap rawBitmap = bitmap; // Save pre-transform bitmap for video encoding
			long bitmapEndUs = System.nanoTime() / 1000;

			// Create scaled bitmap for display
			Matrix matrix = new Matrix();
			matrix.postRotate(90);
			matrix.postScale(scale,scale);
			bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, false);

			// Draw crosshairs on mutable copy of thermal image
			Bitmap thermalWithCrosshairs = bitmap.copy(Bitmap.Config.ARGB_8888, true);
			Canvas canvas = new Canvas(thermalWithCrosshairs);
			Paint paint = new Paint();
			paint.setColor(Color.WHITE);
			paint.setStrokeWidth(2 * scale);
			paint.setStyle(Paint.Style.STROKE);

			int centerX = thermalWithCrosshairs.getWidth() / 2;
			int centerY = thermalWithCrosshairs.getHeight() / 2;
			int crosshairSize = (int)(20 * scale);
			int miniCrosshairSize = (int)(8 * scale);

			// Draw center crosshair
			if (showCenterCrosshair) {
				canvas.drawLine(centerX - crosshairSize, centerY, centerX + crosshairSize, centerY, paint);
				canvas.drawLine(centerX, centerY - crosshairSize, centerX, centerY + crosshairSize, paint);
				canvas.drawCircle(centerX, centerY, crosshairSize / 2, paint);
			}

			// Draw min/max crosshairs
			if (showMinMaxOverlay) {
				// Transform original coords to rotated/scaled coords: (origX, origY) -> ((191-origY)*scale, origX*scale)
				int minScreenX = (int)((191 - minPixelY) * scale);
				int minScreenY = (int)(minPixelX * scale);
				int maxScreenX = (int)((191 - maxPixelY) * scale);
				int maxScreenY = (int)(maxPixelX * scale);

				// Draw black outlines first for contrast
				paint.setColor(Color.BLACK);
				paint.setStrokeWidth(4 * scale);
				canvas.drawLine(minScreenX - miniCrosshairSize, minScreenY, minScreenX + miniCrosshairSize, minScreenY, paint);
				canvas.drawLine(minScreenX, minScreenY - miniCrosshairSize, minScreenX, minScreenY + miniCrosshairSize, paint);
				canvas.drawLine(maxScreenX - miniCrosshairSize, maxScreenY, maxScreenX + miniCrosshairSize, maxScreenY, paint);
				canvas.drawLine(maxScreenX, maxScreenY - miniCrosshairSize, maxScreenX, maxScreenY + miniCrosshairSize, paint);

				// Draw min crosshair (blue) on top
				paint.setStrokeWidth(2 * scale);
				paint.setColor(Color.CYAN);
				canvas.drawLine(minScreenX - miniCrosshairSize, minScreenY, minScreenX + miniCrosshairSize, minScreenY, paint);
				canvas.drawLine(minScreenX, minScreenY - miniCrosshairSize, minScreenX, minScreenY + miniCrosshairSize, paint);

				// Draw max crosshair (yellow) on top
				paint.setColor(Color.YELLOW);
				canvas.drawLine(maxScreenX - miniCrosshairSize, maxScreenY, maxScreenX + miniCrosshairSize, maxScreenY, paint);
				canvas.drawLine(maxScreenX, maxScreenY - miniCrosshairSize, maxScreenX, maxScreenY + miniCrosshairSize, paint);
			}

			// Draw ROI rectangle and crosshairs
			if (showROIOverlay) {
				// Normalize ROI bounds
				int rx1 = Math.min(roiX1, roiX2), rx2 = Math.max(roiX1, roiX2);
				int ry1 = Math.min(roiY1, roiY2), ry2 = Math.max(roiY1, roiY2);

				// Convert to screen coords (with rotation)
				int screenLeft = (int)((191 - ry2) * scale);
				int screenTop = (int)(rx1 * scale);
				int screenRight = (int)((191 - ry1) * scale);
				int screenBottom = (int)(rx2 * scale);

				// Draw rectangle outline (black outer, white inner for contrast)
				paint.setStyle(Paint.Style.STROKE);
				paint.setColor(Color.BLACK);
				paint.setStrokeWidth(2 * scale);
				canvas.drawRect(screenLeft, screenTop, screenRight, screenBottom, paint);
				paint.setColor(Color.WHITE);
				paint.setStrokeWidth(1 * scale);
				canvas.drawRect(screenLeft, screenTop, screenRight, screenBottom, paint);

				// Draw ROI min/max crosshairs with black outlines for contrast
				int roiMinScreenX = (int)((191 - roiMinPixelY) * scale);
				int roiMinScreenY = (int)(roiMinPixelX * scale);
				int roiMaxScreenX = (int)((191 - roiMaxPixelY) * scale);
				int roiMaxScreenY = (int)(roiMaxPixelX * scale);

				// Black outlines first
				paint.setColor(Color.BLACK);
				paint.setStrokeWidth(3 * scale);
				canvas.drawLine(roiMinScreenX - miniCrosshairSize, roiMinScreenY,
								roiMinScreenX + miniCrosshairSize, roiMinScreenY, paint);
				canvas.drawLine(roiMinScreenX, roiMinScreenY - miniCrosshairSize,
								roiMinScreenX, roiMinScreenY + miniCrosshairSize, paint);
				canvas.drawLine(roiMaxScreenX - miniCrosshairSize, roiMaxScreenY,
								roiMaxScreenX + miniCrosshairSize, roiMaxScreenY, paint);
				canvas.drawLine(roiMaxScreenX, roiMaxScreenY - miniCrosshairSize,
								roiMaxScreenX, roiMaxScreenY + miniCrosshairSize, paint);

				// ROI min crosshair (magenta)
				paint.setStrokeWidth(1 * scale);
				paint.setColor(Color.MAGENTA);
				canvas.drawLine(roiMinScreenX - miniCrosshairSize, roiMinScreenY,
								roiMinScreenX + miniCrosshairSize, roiMinScreenY, paint);
				canvas.drawLine(roiMinScreenX, roiMinScreenY - miniCrosshairSize,
								roiMinScreenX, roiMinScreenY + miniCrosshairSize, paint);

				// ROI max crosshair (white)
				paint.setColor(Color.WHITE);
				canvas.drawLine(roiMaxScreenX - miniCrosshairSize, roiMaxScreenY,
								roiMaxScreenX + miniCrosshairSize, roiMaxScreenY, paint);
				canvas.drawLine(roiMaxScreenX, roiMaxScreenY - miniCrosshairSize,
								roiMaxScreenX, roiMaxScreenY + miniCrosshairSize, paint);
			}

			// Calculate temperature values (convert raw to Celsius)
			// raw/64 - 273.15 works for both high and low gain modes
			float tempCenter = centerPixelRaw / 64.0f - 273.15f;
			float tempMin = minPixelRaw / 64.0f - 273.15f;
			float tempMax = maxPixelRaw / 64.0f - 273.15f;

			String tempText = String.format("%.1f°C  Min: %.1f°C  Max: %.1f°C", tempCenter, tempMin, tempMax);
			if (showROIOverlay) {
				float roiTempMin = roiMinPixelRaw / 64.0f - 273.15f;
				float roiTempMax = roiMaxPixelRaw / 64.0f - 273.15f;
				tempText += String.format("  ROI: %.1f-%.1f°C", roiTempMin, roiTempMax);
			}

			// Create composite bitmap with text banner ABOVE thermal image
			paint.setStyle(Paint.Style.FILL);
			float textSize = 42 * scale / 5.625f;  // Scale text appropriately
			paint.setTextSize(textSize);
			int bannerHeight = (int)(textSize * 1.5f);

			// Create new bitmap: banner height + thermal image height
			int thermalWidth = thermalWithCrosshairs.getWidth();
			int thermalHeight = thermalWithCrosshairs.getHeight();
			displayBitmapWithOverlays = Bitmap.createBitmap(thermalWidth, bannerHeight + thermalHeight, Bitmap.Config.ARGB_8888);
			Canvas compositeCanvas = new Canvas(displayBitmapWithOverlays);

			// Draw black banner background at top
			paint.setColor(Color.BLACK);
			compositeCanvas.drawRect(0, 0, thermalWidth, bannerHeight, paint);

			// Draw white temperature text in banner
			paint.setColor(Color.WHITE);
			compositeCanvas.drawText(tempText, 10 * scale / 5.625f, textSize * 1.1f, paint);

			// Draw thermal image (with crosshairs) below banner
			compositeCanvas.drawBitmap(thermalWithCrosshairs, 0, bannerHeight, null);

			// Encode video frame AFTER overlay drawing
			long encodeStartUs = System.nanoTime() / 1000;
			if (isRecording) {
				Bitmap videoBitmap;
				if (includeOverlayInSaves) {
					// Use display bitmap (with overlays) at high resolution
					videoBitmap = Bitmap.createScaledBitmap(displayBitmapWithOverlays, currentVideoWidth, currentVideoHeight, true);
				} else {
					// Reuse saved rawBitmap with rotate-only (no redundant bitmapARGBFromByte call)
					Matrix videoMatrix = new Matrix();
					videoMatrix.postRotate(90);
					videoBitmap = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.getWidth(), rawBitmap.getHeight(), videoMatrix, false);
				}
				encodeFrame(videoBitmap);
			}
			long encodeEndUs = System.nanoTime() / 1000;
			long frameEndUs = encodeEndUs;

			// Performance instrumentation
			perfBitmapTimeUs += (bitmapEndUs - bitmapStartUs);
			perfEncodeTimeUs += (encodeEndUs - encodeStartUs);
			perfTotalFrameTimeUs += (frameEndUs - frameStartUs);
			perfFrameCount++;
			if (perfFrameCount >= 100) {
				Log.i("ThermalCamera", String.format("PERF: avg frame=%.1fms bitmap=%.1fms encode=%.1fms (over %d frames)",
					perfTotalFrameTimeUs / 1000.0 / perfFrameCount,
					perfBitmapTimeUs / 1000.0 / perfFrameCount,
					perfEncodeTimeUs / 1000.0 / perfFrameCount,
					perfFrameCount));
				perfTotalFrameTimeUs = 0;
				perfBitmapTimeUs = 0;
				perfEncodeTimeUs = 0;
				perfFrameCount = 0;
			}

			ImageView imageView = findViewById(R.id.imageView);
			imageView.setImageBitmap(displayBitmapWithOverlays);

        }
    };

    @Override
    protected void onStart() {
        super.onStart();
		loadLUT();
        handler.post(runnable); // Start the periodic task
    }

    @Override
    protected void onStop() {
        super.onStop();
        handler.removeCallbacks(runnable); // Stop the task when the activity is not visible
    }

	@Override
	protected void onResume() {
		super.onResume();
		// Try to reconnect if camera was disconnected
		if (native_stream == 0) {
			Log.d("ThermalCamera", "onResume: native_stream is 0, trying to connect");
			connectCamera();
		}
	}

	// Nonlinear SeekBar mapping: more sensitivity at low end
	// progress 0-1000 -> degrees 0.5-170.0 via quadratic curve
	private static final float RANGE_MIN = 0.5f;
	private static final float RANGE_MAX = 170.0f;
	private static final int RANGE_SEEKBAR_MAX = 1000;

	// Linear SeekBar mapping for absolute temperature (mode 2)
	private static final float TEMP_ABS_MIN = -40.0f;
	private static final float TEMP_ABS_MAX = 400.0f;
	private static final int TEMP_SEEKBAR_MAX = 4400;  // 0.1° resolution over -40 to 400

	private float tempSeekBarToDegrees(int progress) {
		return TEMP_ABS_MIN + (TEMP_ABS_MAX - TEMP_ABS_MIN) * progress / (float) TEMP_SEEKBAR_MAX;
	}

	private int degreesToTempSeekBar(float degrees) {
		return Math.round((degrees - TEMP_ABS_MIN) / (TEMP_ABS_MAX - TEMP_ABS_MIN) * TEMP_SEEKBAR_MAX);
	}

	private float seekBarToDegrees(int progress) {
		float t = progress / (float) RANGE_SEEKBAR_MAX;
		return RANGE_MIN + (RANGE_MAX - RANGE_MIN) * t * t;
	}

	private int degreesToSeekBar(float degrees) {
		float t = (float) Math.sqrt((degrees - RANGE_MIN) / (RANGE_MAX - RANGE_MIN));
		return Math.round(t * RANGE_SEEKBAR_MAX);
	}

	private void initTopBottomFromCurrentImage() {
		if (maxPixelRaw > minPixelRaw) {
			manualBottomDegrees = minPixelRaw / 64.0f - 273.15f;
			manualTopDegrees = maxPixelRaw / 64.0f - 273.15f;
		} else {
			manualBottomDegrees = 20.0f;
			manualTopDegrees = 40.0f;
		}
		// Clamp
		manualBottomDegrees = Math.max(TEMP_ABS_MIN, Math.min(TEMP_ABS_MAX, manualBottomDegrees));
		manualTopDegrees = Math.max(TEMP_ABS_MIN, Math.min(TEMP_ABS_MAX, manualTopDegrees));
		if (manualTopDegrees - manualBottomDegrees < 0.5f) {
			manualTopDegrees = manualBottomDegrees + 0.5f;
		}
		updateTopBottomUI();
	}

	private void updateTopBottomUI() {
		SeekBar topSeekBar = findViewById(R.id.topTempSeekBar);
		SeekBar bottomSeekBar = findViewById(R.id.bottomTempSeekBar);
		EditText topLabel = findViewById(R.id.topTempLabel);
		EditText bottomLabel = findViewById(R.id.bottomTempLabel);
		if (topSeekBar != null) topSeekBar.setProgress(degreesToTempSeekBar(manualTopDegrees));
		if (bottomSeekBar != null) bottomSeekBar.setProgress(degreesToTempSeekBar(manualBottomDegrees));
		if (topLabel != null) topLabel.setText(String.format("%.1f", manualTopDegrees));
		if (bottomLabel != null) bottomLabel.setText(String.format("%.1f", manualBottomDegrees));
	}

	private void updateRangeOverlayVisibility() {
		View overlay = findViewById(R.id.manualRangeOverlay);
		if (overlay != null) {
			overlay.setVisibility(colorScaleMode == 1 ? View.VISIBLE : View.GONE);
		}
		View topBottomOverlay = findViewById(R.id.manualTopBottomOverlay);
		if (topBottomOverlay != null) {
			topBottomOverlay.setVisibility(colorScaleMode == 2 ? View.VISIBLE : View.GONE);
		}
	}

	private void showSettingsDialog() {
		LinearLayout layout = new LinearLayout(this);
		layout.setOrientation(LinearLayout.VERTICAL);
		int padding = (int) (16 * getResources().getDisplayMetrics().density);
		layout.setPadding(padding, padding, padding, padding);

		// Color scale mode spinner
		TextView scaleLabel = new TextView(this);
		scaleLabel.setText("Color scale mode:");
		layout.addView(scaleLabel);

		Spinner scaleSpinner = new Spinner(this);
		ArrayAdapter<String> scaleAdapter = new ArrayAdapter<>(this,
			android.R.layout.simple_spinner_item,
			new String[]{"Auto", "Center ±Range", "Manual Top/Bottom"});
		scaleAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
		scaleSpinner.setAdapter(scaleAdapter);
		scaleSpinner.setSelection(colorScaleMode);
		layout.addView(scaleSpinner);

		CheckBox centerCrosshairCheckbox = new CheckBox(this);
		centerCrosshairCheckbox.setText("Center crosshair");
		centerCrosshairCheckbox.setChecked(showCenterCrosshair);
		layout.addView(centerCrosshairCheckbox);

		CheckBox minMaxCheckbox = new CheckBox(this);
		minMaxCheckbox.setText("Min/Max tracking");
		minMaxCheckbox.setChecked(showMinMaxOverlay);
		layout.addView(minMaxCheckbox);

		CheckBox roiCheckbox = new CheckBox(this);
		roiCheckbox.setText("ROI tracking");
		roiCheckbox.setChecked(showROIOverlay);
		layout.addView(roiCheckbox);

		CheckBox overlayInSavesCheckbox = new CheckBox(this);
		overlayInSavesCheckbox.setText("Include overlay when saving");
		overlayInSavesCheckbox.setChecked(includeOverlayInSaves);
		layout.addView(overlayInSavesCheckbox);

		new AlertDialog.Builder(this)
			.setTitle("Settings")
			.setView(layout)
			.setPositiveButton("OK", (dialog, which) -> {
				int newMode = scaleSpinner.getSelectedItemPosition();
				if (newMode == 2 && colorScaleMode != 2) {
					initTopBottomFromCurrentImage();
				}
				colorScaleMode = newMode;
				showCenterCrosshair = centerCrosshairCheckbox.isChecked();
				showMinMaxOverlay = minMaxCheckbox.isChecked();
				boolean wasROIEnabled = showROIOverlay;
				showROIOverlay = roiCheckbox.isChecked();
				if (showROIOverlay && !wasROIEnabled) {
					// Initialize ROI to center quarter when first enabled
					roiX1 = 64; roiY1 = 48;
					roiX2 = 192; roiY2 = 144;
				}
				includeOverlayInSaves = overlayInSavesCheckbox.isChecked();
				updateRangeOverlayVisibility();
				saveSettings();
				Log.d("ThermalCamera", "Settings updated: colorScaleMode=" + colorScaleMode + ", centerCrosshair=" + showCenterCrosshair + ", minMax=" + showMinMaxOverlay + ", roi=" + showROIOverlay + ", overlayInSaves=" + includeOverlayInSaves);
			})
			.setNegativeButton("Cancel", null)
			.show();
	}

	private Bitmap getBitmapForSaving() {
		if (includeOverlayInSaves && displayBitmapWithOverlays != null) {
			return displayBitmapWithOverlays;
		}
		if (last_frame == null) return null;
		Bitmap bitmap = bitmapARGBFromByte(last_frame);
		Matrix matrix = new Matrix();
		matrix.postRotate(90);
		return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, false);
	}

	private void handleControlAction(String action) {
		Log.d("ThermalCamera", "handleControlAction: " + action);
		switch (action) {
			case "capture_image":
				Bitmap bitmapToSave = getBitmapForSaving();
				if (bitmapToSave == null) {
					Log.w("ThermalCamera", "capture_image: no frame available");
					return;
				}
				SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.getDefault());
				Date now = new Date();
				String dateTimeString = dateFormat.format(now);
				saveImageToGallery(getApplicationContext(), bitmapToSave, "thermal_camera", dateTimeString + ".png");
				break;
			case "capture_raw":
				if (last_frame == null) {
					Log.w("ThermalCamera", "capture_raw: no frame available");
					return;
				}
				SimpleDateFormat rawDateFormat = new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.getDefault());
				Date rawNow = new Date();
				String rawDateTimeString = rawDateFormat.format(rawNow);
				saveBytesToFileInDownloads(getApplicationContext(), last_frame, "thermal_camera_" + rawDateTimeString + ".bin");
				break;
			case "start_video":
				if (!isRecording) {
					startRecording(null);
				} else {
					Log.d("ThermalCamera", "start_video: already recording");
				}
				break;
			case "stop_video":
				if (isRecording) {
					stopRecording(null);
				} else {
					Log.d("ThermalCamera", "stop_video: not recording");
				}
				break;
			case "cycle_color":
				sendCtrl(fd, color + 1);
				color = (color + 1) % 11;
				saveSettings();
				break;
			case "toggle_center_crosshair":
				showCenterCrosshair = !showCenterCrosshair;
				saveSettings();
				Log.i("ThermalCamera", "Center crosshair: " + showCenterCrosshair);
				break;
			case "toggle_minmax":
				showMinMaxOverlay = !showMinMaxOverlay;
				saveSettings();
				Log.i("ThermalCamera", "Min/Max overlay: " + showMinMaxOverlay);
				break;
			case "toggle_roi":
				showROIOverlay = !showROIOverlay;
				if (showROIOverlay) {
					// Initialize to center quarter
					roiX1 = 64; roiY1 = 48;
					roiX2 = 192; roiY2 = 144;
				}
				saveSettings();
				Log.i("ThermalCamera", "ROI overlay: " + showROIOverlay);
				break;
			case "toggle_overlay_in_saves":
				includeOverlayInSaves = !includeOverlayInSaves;
				saveSettings();
				Log.i("ThermalCamera", "Include overlay in saves: " + includeOverlayInSaves);
				break;
			case "set_scale_auto":
				colorScaleMode = 0;
				updateRangeOverlayVisibility();
				saveSettings();
				Log.i("ThermalCamera", "Color scale mode: Auto");
				break;
			case "set_scale_manual":
				colorScaleMode = 1;
				updateRangeOverlayVisibility();
				saveSettings();
				Log.i("ThermalCamera", "Color scale mode: Manual Center ±" + manualRangeDegrees + "°C");
				break;
			case "set_scale_topbottom":
				colorScaleMode = 2;
				initTopBottomFromCurrentImage();
				updateRangeOverlayVisibility();
				saveSettings();
				Log.i("ThermalCamera", "Color scale mode: Manual Top/Bottom " + manualBottomDegrees + "-" + manualTopDegrees + "°C");
				break;
			case "toggle_gain":
			case "set_gain_low":
			case "set_gain_high": {
				if (isSwitchingGain) {
					Log.w("ThermalCamera", "Gain switch already in progress");
					break;
				}
				boolean wantLow;
				if (action.equals("toggle_gain")) {
					wantLow = !isLowGain;
				} else {
					wantLow = action.equals("set_gain_low");
				}
				if (wantLow == isLowGain) {
					Log.i("ThermalCamera", "Already in " + (isLowGain ? "low" : "high") + " gain mode");
					break;
				}
				isSwitchingGain = true;
				Button gb = findViewById(R.id.gainButton);
				if (gb != null) { gb.setText("..."); gb.setEnabled(false); }
				final int targetGain = wantLow ? 0 : 1;
				new Thread(() -> {
					int result = setGainMode(fd, targetGain);
					runOnUiThread(() -> {
						if (result == 0) {
							isLowGain = wantLow;
							saveSettings();
						}
						if (gb != null) { gb.setText(isLowGain ? "Low" : "High"); gb.setEnabled(true); }
						isSwitchingGain = false;
						Log.i("ThermalCamera", "Gain mode: " + (isLowGain ? "Low (wide)" : "High (narrow)"));
					});
				}).start();
				break;
			}
			case "status":
				Log.i("ThermalCamera", "STATUS: isRecording=" + isRecording +
						", native_stream=" + native_stream +
						", fd=" + fd +
						", color=" + color +
						", hasFrame=" + (last_frame != null) +
						", centerCrosshair=" + showCenterCrosshair +
						", minMaxOverlay=" + showMinMaxOverlay +
						", roiOverlay=" + showROIOverlay +
						", overlayInSaves=" + includeOverlayInSaves +
						", colorScaleMode=" + colorScaleMode +
						", manualRangeDegrees=" + manualRangeDegrees +
						", manualTopDegrees=" + manualTopDegrees +
						", manualBottomDegrees=" + manualBottomDegrees +
						", isLowGain=" + isLowGain +
						", isSwitchingGain=" + isSwitchingGain);
				break;
			default:
				Log.w("ThermalCamera", "Unknown control action: " + action);
		}
	}

	@Override
	protected void onNewIntent(Intent intent) {
		super.onNewIntent(intent);
		Log.d("ThermalCamera", "onNewIntent: " + intent.getAction());
		// Handle USB_DEVICE_ATTACHED delivered via manifest intent filter
		if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
			Log.d("ThermalCamera", "Device attached via manifest intent, calling connectCamera");
			if (connectCamera()) {
				Toast.makeText(this, "Camera connected", Toast.LENGTH_SHORT).show();
			}
		}

		// Handle control commands from ADB
		String controlAction = intent.getStringExtra("action");
		if (controlAction != null) {
			if (controlAction.equals("set_manual_range")) {
				float range = intent.getFloatExtra("range", -1.0f);
				if (range >= RANGE_MIN && range <= RANGE_MAX) {
					manualRangeDegrees = range;
					SeekBar seekBar = findViewById(R.id.rangeSeekBar);
					EditText label = findViewById(R.id.rangeLabel);
					if (seekBar != null) {
						seekBar.setProgress(degreesToSeekBar(range));
					}
					if (label != null) {
						label.setText(String.format("±%.1f", manualRangeDegrees));
					}
					saveSettings();
					Log.i("ThermalCamera", "Manual range set to ±" + manualRangeDegrees + "°C");
				} else {
					Log.w("ThermalCamera", "Invalid range value: " + range + " (must be 0.5-170.0)");
				}
			} else if (controlAction.equals("set_manual_top")) {
				float temp = intent.getFloatExtra("temp", Float.NaN);
				if (!Float.isNaN(temp) && temp >= TEMP_ABS_MIN && temp <= TEMP_ABS_MAX) {
					manualTopDegrees = Math.max(temp, manualBottomDegrees + 0.5f);
					updateTopBottomUI();
					saveSettings();
					Log.i("ThermalCamera", "Manual top set to " + manualTopDegrees + "°C");
				} else {
					Log.w("ThermalCamera", "Invalid temp value: " + temp);
				}
			} else if (controlAction.equals("set_manual_bottom")) {
				float temp = intent.getFloatExtra("temp", Float.NaN);
				if (!Float.isNaN(temp) && temp >= TEMP_ABS_MIN && temp <= TEMP_ABS_MAX) {
					manualBottomDegrees = Math.min(temp, manualTopDegrees - 0.5f);
					updateTopBottomUI();
					saveSettings();
					Log.i("ThermalCamera", "Manual bottom set to " + manualBottomDegrees + "°C");
				} else {
					Log.w("ThermalCamera", "Invalid temp value: " + temp);
				}
			} else {
				handleControlAction(controlAction);
			}
		}
	}

	public static void saveBytesToFileInDownloads(Context context, byte[] data, String fileName) {
        // ContentValues to hold metadata about the file
        ContentValues contentValues = new ContentValues();
        contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream"); // or another more specific MIME type.
        contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);

        // Inserting a placeholder for the file using the ContentResolver
        Uri uri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues);
        
        if (uri != null) {
            try (OutputStream outputStream = context.getContentResolver().openOutputStream(uri)) {
                if (outputStream != null) {
                    outputStream.write(data);
                    outputStream.flush();
					Toast.makeText(context, "Saved raw.", Toast.LENGTH_SHORT).show();
                }
            } catch (IOException e) {
				Log.v("ThermalCamera", "File failed");
            }
        }
    }
	
	public void saveImageToGallery(final Context context, final Bitmap bitmap, final String albumName, final String fileName) {
		final ContentValues contentValues = new ContentValues();
		contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
		contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "image/png");
		
		contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + albumName);

		Uri uri = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues);

		try (OutputStream stream = context.getContentResolver().openOutputStream(uri)) {
			bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
			Toast.makeText(context, "Saved image.", Toast.LENGTH_SHORT).show();
		} catch (Exception e) {
			Log.v("ThermalCamera", "Image failed");
		}
	}

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		// Load saved settings
		loadSettings();

		// Register USB attach/detach receiver
		IntentFilter filter = new IntentFilter();
		filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
		filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
		filter.addAction(ACTION_USB_PERMISSION);
		registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED);

		// Check both CAMERA and storage permissions
		if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
				!= PackageManager.PERMISSION_GRANTED ||
			ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
				!= PackageManager.PERMISSION_GRANTED) {
			ActivityCompat.requestPermissions(this,
				new String[]{
					Manifest.permission.CAMERA,
					Manifest.permission.WRITE_EXTERNAL_STORAGE
				}, 0);
		}

		connectCamera();

		Button frameButton = findViewById(R.id.getFrameButton);

        frameButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
				if (last_frame == null) return;
				SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.getDefault());
				Date now = new Date();
				String dateTimeString = dateFormat.format(now);
				saveBytesToFileInDownloads(getApplicationContext(), last_frame, "thermal_camera_" + dateTimeString + ".bin");
			}
        });

		Button imageButton = findViewById(R.id.getImageButton);

        imageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
				Bitmap bitmapToSave = getBitmapForSaving();
				if (bitmapToSave == null) return;
				SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.getDefault());
				Date now = new Date();
				String dateTimeString = dateFormat.format(now);
				saveImageToGallery(getApplicationContext(), bitmapToSave, "thermal_camera", dateTimeString + ".png");
			}
        });

		Button gainButton = findViewById(R.id.gainButton);
		gainButton.setText(isLowGain ? "Low" : "High");

        gainButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
				if (isSwitchingGain) return;
				isSwitchingGain = true;
				gainButton.setText("...");
				gainButton.setEnabled(false);
				final int targetGain = isLowGain ? 1 : 0;  // 0=low/wide, 1=high/narrow
				new Thread(() -> {
					int result = setGainMode(fd, targetGain);
					runOnUiThread(() -> {
						if (result == 0) {
							isLowGain = !isLowGain;
							saveSettings();
						}
						gainButton.setText(isLowGain ? "Low" : "High");
						gainButton.setEnabled(true);
						isSwitchingGain = false;
					});
				}).start();
			}
        });

		findViewById(R.id.settingsButton).setOnClickListener(v -> showSettingsDialog());

		findViewById(R.id.startVideoButton).setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				startRecording(v);
			}
		});

		findViewById(R.id.stopVideoButton).setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				stopRecording(v);
			}
		});

		// Set up manual range SeekBar
		SeekBar rangeSeekBar = findViewById(R.id.rangeSeekBar);
		EditText rangeLabel = findViewById(R.id.rangeLabel);

		// Bright magenta thumb so it's visible against any colormap
		Drawable thumb = rangeSeekBar.getThumb();
		if (thumb != null) thumb.setColorFilter(0xFFFF00FF, PorterDuff.Mode.SRC_IN);
		rangeSeekBar.getProgressDrawable().setColorFilter(0xFFFF00FF, PorterDuff.Mode.SRC_IN);

		// IMPORTANT: Use addOnLayoutChangeListener (not post()) to size rotated SeekBars.
		// post() only runs once at startup — if the overlay is GONE at that time, the container
		// height is 0 and the SeekBar gets width=0 permanently. The layout listener re-fires
		// whenever the overlay becomes visible again, keeping the SeekBar correctly sized.
		View seekBarContainer = findViewById(R.id.seekBarContainer);
		seekBarContainer.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
			int h = bottom - top;
			if (h > 0 && rangeSeekBar.getLayoutParams().width != h) {
				ViewGroup.LayoutParams lp = rangeSeekBar.getLayoutParams();
				lp.width = h;
				rangeSeekBar.setLayoutParams(lp);
			}
		});

		// Nonlinear SeekBar: 0-1000 -> 0.5-170°C via quadratic curve
		rangeSeekBar.setMax(RANGE_SEEKBAR_MAX);
		rangeSeekBar.setProgress(degreesToSeekBar(manualRangeDegrees));
		rangeLabel.setText(String.format("±%.1f", manualRangeDegrees));
		rangeSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			@Override
			public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
				manualRangeDegrees = seekBarToDegrees(progress);
				rangeLabel.setText(String.format("±%.1f", manualRangeDegrees));
			}
			@Override
			public void onStartTrackingTouch(SeekBar seekBar) {}
			@Override
			public void onStopTrackingTouch(SeekBar seekBar) {
				saveSettings();
			}
		});

		// Apply typed value from EditText
		Runnable applyRangeFromLabel = () -> {
			try {
				String text = rangeLabel.getText().toString().replace("±", "").replace("°C", "").trim();
				float val = Float.parseFloat(text);
				val = Math.max(RANGE_MIN, Math.min(RANGE_MAX, val));
				manualRangeDegrees = val;
				rangeSeekBar.setProgress(degreesToSeekBar(val));
				saveSettings();
			} catch (NumberFormatException e) {
				// ignore invalid input
			}
			rangeLabel.setText(String.format("±%.1f", manualRangeDegrees));
		};

		rangeLabel.setOnEditorActionListener((v, actionId, event) -> {
			if (actionId == EditorInfo.IME_ACTION_DONE || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
				applyRangeFromLabel.run();
				rangeLabel.clearFocus();
				return true;
			}
			return false;
		});

		rangeLabel.setOnFocusChangeListener((v, hasFocus) -> {
			if (!hasFocus) {
				applyRangeFromLabel.run();
			}
		});

		// Set up Manual Top/Bottom SeekBars (mode 2)
		SeekBar topTempSeekBar = findViewById(R.id.topTempSeekBar);
		SeekBar bottomTempSeekBar = findViewById(R.id.bottomTempSeekBar);
		EditText topTempLabel = findViewById(R.id.topTempLabel);
		EditText bottomTempLabel = findViewById(R.id.bottomTempLabel);

		// Red thumb for top
		Drawable topThumb = topTempSeekBar.getThumb();
		if (topThumb != null) topThumb.setColorFilter(0xFFFF4444, PorterDuff.Mode.SRC_IN);
		// Transparent track for top (it overlaps bottom)
		topTempSeekBar.getProgressDrawable().setAlpha(0);

		// Blue thumb for bottom
		Drawable bottomThumb = bottomTempSeekBar.getThumb();
		if (bottomThumb != null) bottomThumb.setColorFilter(0xFF4488FF, PorterDuff.Mode.SRC_IN);
		bottomTempSeekBar.getProgressDrawable().setColorFilter(0xFF4488FF, PorterDuff.Mode.SRC_IN);

		// IMPORTANT: Use addOnLayoutChangeListener (not post()) — see comment above on seekBarContainer.
		View topBottomContainer = findViewById(R.id.topBottomSeekBarContainer);
		topBottomContainer.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
			int h = bottom - top;
			if (h > 0) {
				if (topTempSeekBar.getLayoutParams().width != h) {
					ViewGroup.LayoutParams lp1 = topTempSeekBar.getLayoutParams();
					lp1.width = h;
					topTempSeekBar.setLayoutParams(lp1);
				}
				if (bottomTempSeekBar.getLayoutParams().width != h) {
					ViewGroup.LayoutParams lp2 = bottomTempSeekBar.getLayoutParams();
					lp2.width = h;
					bottomTempSeekBar.setLayoutParams(lp2);
				}
			}
		});

		topTempSeekBar.setMax(TEMP_SEEKBAR_MAX);
		bottomTempSeekBar.setMax(TEMP_SEEKBAR_MAX);
		topTempSeekBar.setProgress(degreesToTempSeekBar(manualTopDegrees));
		bottomTempSeekBar.setProgress(degreesToTempSeekBar(manualBottomDegrees));
		topTempLabel.setText(String.format("%.1f", manualTopDegrees));
		bottomTempLabel.setText(String.format("%.1f", manualBottomDegrees));

		topTempSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			@Override
			public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
				float deg = tempSeekBarToDegrees(progress);
				// Clamp: top can't go below bottom + 0.5
				float minAllowed = manualBottomDegrees + 0.5f;
				if (deg < minAllowed) {
					deg = minAllowed;
					seekBar.setProgress(degreesToTempSeekBar(deg));
				}
				manualTopDegrees = deg;
				topTempLabel.setText(String.format("%.1f", manualTopDegrees));
			}
			@Override public void onStartTrackingTouch(SeekBar seekBar) {}
			@Override public void onStopTrackingTouch(SeekBar seekBar) { saveSettings(); }
		});

		bottomTempSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			@Override
			public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
				float deg = tempSeekBarToDegrees(progress);
				// Clamp: bottom can't go above top - 0.5
				float maxAllowed = manualTopDegrees - 0.5f;
				if (deg > maxAllowed) {
					deg = maxAllowed;
					seekBar.setProgress(degreesToTempSeekBar(deg));
				}
				manualBottomDegrees = deg;
				bottomTempLabel.setText(String.format("%.1f", manualBottomDegrees));
			}
			@Override public void onStartTrackingTouch(SeekBar seekBar) {}
			@Override public void onStopTrackingTouch(SeekBar seekBar) { saveSettings(); }
		});

		// Touch dispatch: route touches to nearest SeekBar thumb.
		// IMPORTANT: A ViewGroup's OnTouchListener does NOT fire when a child consumes the
		// event. Android dispatches to children (topmost Z-order first) before the parent.
		// Since topTempSeekBar is on top, it always steals touches — even ones meant for the
		// bottom SeekBar. Fix: add a transparent View on top of both SeekBars to intercept
		// ALL touches, then set progress directly on the appropriate SeekBar.
		View touchInterceptor = new View(this);
		touchInterceptor.setLayoutParams(new FrameLayout.LayoutParams(
			FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
		((FrameLayout) topBottomContainer).addView(touchInterceptor);

		final SeekBar[] activeSeekBar = {null};
		touchInterceptor.setOnTouchListener((v, event) -> {
			float y = event.getY();
			float containerHeight = v.getHeight();
			if (containerHeight <= 0) return false;
			// 270° rotation: progress increases from bottom to top
			int touchProgress = (int)(TEMP_SEEKBAR_MAX * (1.0f - y / containerHeight));
			touchProgress = Math.max(0, Math.min(TEMP_SEEKBAR_MAX, touchProgress));

			switch (event.getAction()) {
				case MotionEvent.ACTION_DOWN:
					// Pick the closer thumb on initial touch
					int distToTop = Math.abs(touchProgress - topTempSeekBar.getProgress());
					int distToBottom = Math.abs(touchProgress - bottomTempSeekBar.getProgress());
					activeSeekBar[0] = (distToTop <= distToBottom) ? topTempSeekBar : bottomTempSeekBar;
					activeSeekBar[0].setProgress(touchProgress);
					break;
				case MotionEvent.ACTION_MOVE:
					if (activeSeekBar[0] != null) {
						activeSeekBar[0].setProgress(touchProgress);
					}
					break;
				case MotionEvent.ACTION_UP:
				case MotionEvent.ACTION_CANCEL:
					if (activeSeekBar[0] != null) {
						activeSeekBar[0].setProgress(touchProgress);
						saveSettings();
						activeSeekBar[0] = null;
					}
					break;
			}
			return true;
		});

		// EditText apply for top temp
		Runnable applyTopFromLabel = () -> {
			try {
				String text = topTempLabel.getText().toString().replace("°C", "").trim();
				float val = Float.parseFloat(text);
				val = Math.max(TEMP_ABS_MIN, Math.min(TEMP_ABS_MAX, val));
				val = Math.max(manualBottomDegrees + 0.5f, val);
				manualTopDegrees = val;
				topTempSeekBar.setProgress(degreesToTempSeekBar(val));
				saveSettings();
			} catch (NumberFormatException e) { }
			topTempLabel.setText(String.format("%.1f", manualTopDegrees));
		};

		topTempLabel.setOnEditorActionListener((v, actionId, event) -> {
			if (actionId == EditorInfo.IME_ACTION_DONE || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
				applyTopFromLabel.run();
				topTempLabel.clearFocus();
				return true;
			}
			return false;
		});
		topTempLabel.setOnFocusChangeListener((v, hasFocus) -> {
			if (!hasFocus) applyTopFromLabel.run();
		});

		// EditText apply for bottom temp
		Runnable applyBottomFromLabel = () -> {
			try {
				String text = bottomTempLabel.getText().toString().replace("°C", "").trim();
				float val = Float.parseFloat(text);
				val = Math.max(TEMP_ABS_MIN, Math.min(TEMP_ABS_MAX, val));
				val = Math.min(manualTopDegrees - 0.5f, val);
				manualBottomDegrees = val;
				bottomTempSeekBar.setProgress(degreesToTempSeekBar(val));
				saveSettings();
			} catch (NumberFormatException e) { }
			bottomTempLabel.setText(String.format("%.1f", manualBottomDegrees));
		};

		bottomTempLabel.setOnEditorActionListener((v, actionId, event) -> {
			if (actionId == EditorInfo.IME_ACTION_DONE || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
				applyBottomFromLabel.run();
				bottomTempLabel.clearFocus();
				return true;
			}
			return false;
		});
		bottomTempLabel.setOnFocusChangeListener((v, hasFocus) -> {
			if (!hasFocus) applyBottomFromLabel.run();
		});

		updateRangeOverlayVisibility();

		WindowManager windowManager = (WindowManager) this.getSystemService(Context.WINDOW_SERVICE);
		DisplayMetrics metrics = new DisplayMetrics();
		windowManager.getDefaultDisplay().getMetrics(metrics);
		scale = metrics.widthPixels / 192.0f;

		// Touch listener for ROI drawing
		ImageView imageView = findViewById(R.id.imageView);
		imageView.setOnTouchListener((v, event) -> {
			if (!showROIOverlay) return false;

			// Convert screen coords to frame coords
			int frameX = Math.min(255, Math.max(0, (int)(event.getY() / scale)));
			int frameY = Math.min(191, Math.max(0, (int)(191 - event.getX() / scale)));

			switch (event.getAction()) {
				case MotionEvent.ACTION_DOWN:
					roiX1 = roiX2 = frameX;
					roiY1 = roiY2 = frameY;
					break;
				case MotionEvent.ACTION_MOVE:
				case MotionEvent.ACTION_UP:
					roiX2 = frameX;
					roiY2 = frameY;
					break;
			}
			return true;
		});
	}

    @Override
    protected void onDestroy() {
        super.onDestroy();
		unregisterReceiver(usbReceiver);
    }

}
