package info.jnlm.thermal_camera;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.Manifest;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Button;
import android.view.View;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDeviceConnection;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.widget.Toast;
import android.util.Log;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.HashMap;
import java.io.FileNotFoundException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ByteOrder;

import android.net.Uri;
import android.content.ContentValues;
import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.graphics.Bitmap;
import java.io.File;
import java.io.InputStream;
import java.io.FileOutputStream;


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
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;

public class MainActivity extends Activity {
	static {
		System.loadLibrary("usb-1.0");
		System.loadLibrary("uvc");
		System.loadLibrary("thermalcamera");
	}
    public native long initializeStream(int fd);
    public native byte[] grabFrame(long stream);
    public native void sendCtrl(int fd, int color);

	private static final String ACTION_USB_PERMISSION =
            "info.jnlm.thermal_camera.USB_PERMISSION";
	private static final String kLutFileName = "colormap_lut.cube";
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

	// Overlay settings
	private boolean showCenterCrosshair = true;
	private boolean showMinMaxOverlay = true;

	// Video encoding
	private static final int VIDEO_WIDTH = 192;  // Rotated dimensions
	private static final int VIDEO_HEIGHT = 256;
	private static final int VIDEO_FRAME_RATE = 25;
	private static final int VIDEO_BITRATE = 2000000;  // 2 Mbps
	private MediaCodec mediaCodec;
	private MediaMuxer mediaMuxer;
	private int videoTrackIndex = -1;
	private boolean muxerStarted = false;
	private long presentationTimeUs = 0;
	private int minPixelRaw = 0, maxPixelRaw = 0;
	private int minPixelX = 0, minPixelY = 0;
	private int maxPixelX = 0, maxPixelY = 0;
	private TextView temperatureText;

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
			}
			return native_stream != 0;
		}
		Log.d("ThermalCamera", "No matching camera device found in list");
		return false;
	}

	public Bitmap bitmapARGBFromByte(byte[] data){
		int[] pixels = new int[kFrameWidth * kFrameHeight];
		final int kNumPixels = kFrameWidth * kFrameHeight;

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
		if (!isRecording || mediaCodec == null) return;

		try {
			// Get input buffer
			int inputBufferIndex = mediaCodec.dequeueInputBuffer(10000);
			if (inputBufferIndex < 0) {
				Log.w("ThermalCamera", "No input buffer available");
				return;
			}

			ByteBuffer inputBuffer = mediaCodec.getInputBuffer(inputBufferIndex);
			if (inputBuffer == null) return;

			// Convert ARGB bitmap to YUV420 (NV12 format)
			inputBuffer.clear();
			int[] pixels = new int[VIDEO_WIDTH * VIDEO_HEIGHT];
			bitmap.getPixels(pixels, 0, VIDEO_WIDTH, 0, 0, VIDEO_WIDTH, VIDEO_HEIGHT);

			// Y plane
			for (int i = 0; i < VIDEO_WIDTH * VIDEO_HEIGHT; i++) {
				int pixel = pixels[i];
				int r = (pixel >> 16) & 0xFF;
				int g = (pixel >> 8) & 0xFF;
				int b = pixel & 0xFF;
				int y = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
				inputBuffer.put((byte) Math.max(0, Math.min(255, y)));
			}

			// UV plane (NV12: interleaved U and V, subsampled 2x2)
			for (int j = 0; j < VIDEO_HEIGHT / 2; j++) {
				for (int i = 0; i < VIDEO_WIDTH / 2; i++) {
					int pixel = pixels[(j * 2) * VIDEO_WIDTH + (i * 2)];
					int r = (pixel >> 16) & 0xFF;
					int g = (pixel >> 8) & 0xFF;
					int b = pixel & 0xFF;
					int u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
					int v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
					inputBuffer.put((byte) Math.max(0, Math.min(255, u)));
					inputBuffer.put((byte) Math.max(0, Math.min(255, v)));
				}
			}

			// Queue the input buffer
			mediaCodec.queueInputBuffer(inputBufferIndex, 0, inputBuffer.position(), presentationTimeUs, 0);
			presentationTimeUs += 1000000L / VIDEO_FRAME_RATE;

			// Drain output buffers
			drainEncoder(false);

		} catch (Exception e) {
			Log.e("ThermalCamera", "Error encoding frame", e);
		}
	}

	private void drainEncoder(boolean endOfStream) {
		if (endOfStream) {
			// Signal EOS by sending an empty buffer with EOS flag
			int inputBufferIndex = mediaCodec.dequeueInputBuffer(10000);
			if (inputBufferIndex >= 0) {
				mediaCodec.queueInputBuffer(inputBufferIndex, 0, 0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
			}
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

	private Uri createFileInDownloads(String fileName) {
		ContentValues contentValues = new ContentValues();
		contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
		contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
		contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
		return getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues);
	}

	// ffmpeg only deals in file paths, not URI's, so it is copied here to somewhere that corresponds to a file path.
	public void prepareLUTFile() {
		File lutFile = new File(getExternalFilesDir(null), kLutFileName);

		try (InputStream is = getAssets().open(kLutFileName);
				OutputStream os = new FileOutputStream(lutFile)) {
			byte[] buffer = new byte[1024];
			int length;
			while ((length = is.read(buffer)) != -1) {
				os.write(buffer, 0, length);
			}
			Log.i("ThermalCamera", "LUT file copied to app-specific storage.");
		} catch (IOException e) {
			Log.e("ThermalCamera", "Error copying LUT file", e);
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
			// Set up MediaCodec for H.264 encoding
			MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, VIDEO_WIDTH, VIDEO_HEIGHT);
			format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
			format.setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE);
			format.setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FRAME_RATE);
			format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

			mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
			mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
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
			Bitmap bitmap = bitmapARGBFromByte(last_frame);

			// Create unscaled rotated bitmap for video encoding (192x256)
			if (isRecording) {
				Matrix videoMatrix = new Matrix();
				videoMatrix.postRotate(90);
				Bitmap videoBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), videoMatrix, false);
				encodeFrame(videoBitmap);
			}

			// Create scaled bitmap for display
			Matrix matrix = new Matrix();
			matrix.postRotate(90);
			matrix.postScale(scale,scale);
			bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, false);

			// Draw crosshairs on mutable copy
			Bitmap mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true);
			Canvas canvas = new Canvas(mutableBitmap);
			Paint paint = new Paint();
			paint.setColor(Color.WHITE);
			paint.setStrokeWidth(2 * scale);
			paint.setStyle(Paint.Style.STROKE);

			int centerX = mutableBitmap.getWidth() / 2;
			int centerY = mutableBitmap.getHeight() / 2;
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

			ImageView imageView = findViewById(R.id.imageView);
			imageView.setImageBitmap(mutableBitmap);

			// Update temperature display (convert raw to Celsius)
			// Empirically derived from temperature references: 0°C = 16708 raw, 37°C = 19812 raw
			if (temperatureText != null) {
				final float kSlope = 37.0f / (19812 - 16708);
				float tempCenter = (centerPixelRaw - 16708) * kSlope;
				float tempMin = (minPixelRaw - 16708) * kSlope;
				float tempMax = (maxPixelRaw - 16708) * kSlope;
				temperatureText.setText(String.format("%.1f°C\nMin: %.1f°C\nMax: %.1f°C", tempCenter, tempMin, tempMax));
			}

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

	private void showSettingsDialog() {
		LinearLayout layout = new LinearLayout(this);
		layout.setOrientation(LinearLayout.VERTICAL);
		int padding = (int) (16 * getResources().getDisplayMetrics().density);
		layout.setPadding(padding, padding, padding, padding);

		CheckBox centerCrosshairCheckbox = new CheckBox(this);
		centerCrosshairCheckbox.setText("Center crosshair");
		centerCrosshairCheckbox.setChecked(showCenterCrosshair);
		layout.addView(centerCrosshairCheckbox);

		CheckBox minMaxCheckbox = new CheckBox(this);
		minMaxCheckbox.setText("Min/Max tracking");
		minMaxCheckbox.setChecked(showMinMaxOverlay);
		layout.addView(minMaxCheckbox);

		new AlertDialog.Builder(this)
			.setTitle("Settings")
			.setView(layout)
			.setPositiveButton("OK", (dialog, which) -> {
				showCenterCrosshair = centerCrosshairCheckbox.isChecked();
				showMinMaxOverlay = minMaxCheckbox.isChecked();
				Log.d("ThermalCamera", "Settings updated: centerCrosshair=" + showCenterCrosshair + ", minMax=" + showMinMaxOverlay);
			})
			.setNegativeButton("Cancel", null)
			.show();
	}

	private void handleControlAction(String action) {
		Log.d("ThermalCamera", "handleControlAction: " + action);
		switch (action) {
			case "capture_image":
				if (last_frame == null) {
					Log.w("ThermalCamera", "capture_image: no frame available");
					return;
				}
				Bitmap bitmap = bitmapARGBFromByte(last_frame);
				Matrix matrix = new Matrix();
				matrix.postRotate(90);
				bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, false);
				SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.getDefault());
				Date now = new Date();
				String dateTimeString = dateFormat.format(now);
				saveImageToGallery(getApplicationContext(), bitmap, "thermal_camera", dateTimeString + ".png");
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
				break;
			case "toggle_center_crosshair":
				showCenterCrosshair = !showCenterCrosshair;
				Log.i("ThermalCamera", "Center crosshair: " + showCenterCrosshair);
				break;
			case "toggle_minmax":
				showMinMaxOverlay = !showMinMaxOverlay;
				Log.i("ThermalCamera", "Min/Max overlay: " + showMinMaxOverlay);
				break;
			case "status":
				Log.i("ThermalCamera", "STATUS: isRecording=" + isRecording +
						", native_stream=" + native_stream +
						", fd=" + fd +
						", color=" + color +
						", hasFrame=" + (last_frame != null) +
						", centerCrosshair=" + showCenterCrosshair +
						", minMaxOverlay=" + showMinMaxOverlay);
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
			handleControlAction(controlAction);
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
		temperatureText = findViewById(R.id.temperatureText);

		// Register USB attach/detach receiver
		IntentFilter filter = new IntentFilter();
		filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
		filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
		filter.addAction(ACTION_USB_PERMISSION);
		registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED);

		if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
			!= PackageManager.PERMISSION_GRANTED) {
			ActivityCompat.requestPermissions(this,
				new String[]{Manifest.permission.CAMERA}, 0);
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
				if (last_frame == null) return;
				Bitmap bitmap = bitmapARGBFromByte(last_frame);
				// Rotate 90 degrees clockwise to match display orientation
				Matrix matrix = new Matrix();
				matrix.postRotate(90);
				bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, false);
				SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.getDefault());
				Date now = new Date();
				String dateTimeString = dateFormat.format(now);
				saveImageToGallery(getApplicationContext(), bitmap, "thermal_camera",  dateTimeString + ".png");
			}
        });


		Button ctrlButton = findViewById(R.id.ctrlButton);

        ctrlButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
				sendCtrl(fd, color + 1);
				color = (color + 1) % 11;
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


		WindowManager windowManager = (WindowManager) this.getSystemService(Context.WINDOW_SERVICE);
		DisplayMetrics metrics = new DisplayMetrics();
		windowManager.getDefaultDisplay().getMetrics(metrics);
		scale = metrics.widthPixels / 192.0f;
	}

    @Override
    protected void onDestroy() {
        super.onDestroy();
		unregisterReceiver(usbReceiver);
    }

}
