package info.jnlm.thermal_camera;

import android.app.Activity;
import android.os.Bundle;
import android.Manifest;
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

import com.arthenica.mobileffmpeg.FFmpeg;

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
    private Uri fileUri;
    private OutputStream outputStream;
	private String rawVideoFilename = "invalid";


	public Bitmap bitmapARGBFromByte(byte[] data){
		int[] pixels = new int[kFrameWidth * kFrameHeight];
		final int kNumPixels = kFrameWidth * kFrameHeight;

		float min = 1; float max = 0;
		for (int i = 0; i < kNumPixels; i++) {
			int offset = 2 * i; // 2 bytes per uint16_t
			int val_uint16_t = (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
			float val_float = val_uint16_t / 65535.0f;
			if (val_float < min) min = val_float;
			if (val_float > max) max = val_float;
		}

		for (int i = 0; i < kNumPixels; i++) {
			int offset = 2 * i; // 2 bytes per uint16_t
			int val_uint16_t = (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);

			float val_float = (val_uint16_t / 65535.0f - min) / (max - min);
			assert val_float >= 0.0f && val_float <= 1.0f;
			val_uint16_t = (int) (val_float * 65535.0f);


			if (i == 10000) {
				Log.d("ThermalCamera", i + ", " + LUT[i % 65536] + ", " + String.format("0x%08X", LUT[i % 65536]));
			}

			pixels[i] = LUT[val_uint16_t]; // Lookup the ARGB value from LUT
		}

		return Bitmap.createBitmap(pixels, kFrameWidth, kFrameHeight, Bitmap.Config.ARGB_8888);

	}

	private void MaybeEncodeFrame(byte[] frame) {
		if (!isRecording || outputStream == null) return;

		try {
			final int offset = kFrameWidth * kFrameHeight * kPixelSize;
			assert frame.length == 2 * offset;
			outputStream.write(frame, offset, frame.length - offset);
		} catch (IOException e) {
			e.printStackTrace();
			Log.d("ThermalCamera", "Error writing frame");
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

	public void ConvertRawToMp4(String inputFilePath, String outputFilePath) {
		File lutFile = new File(getExternalFilesDir(null), kLutFileName);
		String cmd = String.format(
				"-y -f rawvideo -pixel_format gray16le -video_size 256x192 -framerate 25 -i %s -vf \"normalize=blackpt=black:whitept=white, lut3d=%s\" %s",
				inputFilePath, lutFile.getAbsolutePath(), outputFilePath);

		int rc = FFmpeg.execute(cmd);
		if (rc != 0) {
			Toast.makeText(this, "Conversion failed", Toast.LENGTH_SHORT).show();
			Log.e("ThermalCamera", "FFmpeg conversion failed with exit code: " + rc);
		}
	}

	public void startRecording(View view) {
		// prepareLUTFile();

		String fileName = "thermal_camera_"
				+ new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".bin";
		fileUri = createFileInDownloads(fileName);
		if (fileUri == null) {
			Toast.makeText(this, "Failed to create file in downloads", Toast.LENGTH_SHORT).show();
			return;
		}
		try {
			outputStream = getContentResolver().openOutputStream(fileUri);
			if (outputStream == null) {
				Toast.makeText(this, "Cannot open file output stream", Toast.LENGTH_SHORT).show();
				return;
			}
		} catch (FileNotFoundException e) {
			e.printStackTrace();
			Toast.makeText(this, "File not found for recording", Toast.LENGTH_SHORT).show();
			return;
		}

		findViewById(R.id.startVideoButton).setEnabled(false);
		findViewById(R.id.stopVideoButton).setEnabled(true);
		rawVideoFilename = fileName;
		Toast.makeText(this, "Started recording", Toast.LENGTH_SHORT).show();
		isRecording = true;
	}

	public void stopRecording(View view) {
		isRecording = false;

		if (outputStream != null) {
			try {
				outputStream.close();
			} catch (IOException e) {
				e.printStackTrace();
				Toast.makeText(this, "Error closing file output stream", Toast.LENGTH_SHORT).show();
			}
		}

		findViewById(R.id.startVideoButton).setEnabled(true);
		findViewById(R.id.stopVideoButton).setEnabled(false);

		// These direct file paths are very poor practice and should not even in fact work after some version of Android. But, they do work.
		String fileIn =  "/storage/emulated/0/Download/" + rawVideoFilename;
		// The thermal_camera dir is actually created by saveImageToGallery.
		String fileOut = "/storage/emulated/0/Pictures/thermal_camera/" + rawVideoFilename.replace(".bin", ".mp4");
		Log.v("ThermalCamera", "Converting " + fileIn + " to " + fileOut);
		ConvertRawToMp4(fileIn, fileOut);
		// Make it show up in google photos straightaway:
		File file = new File(fileOut);
		Intent intent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
		intent.setData(Uri.fromFile(file));
		this.sendBroadcast(intent);

		Toast.makeText(this, "Stopped recording", Toast.LENGTH_SHORT).show();
	}



	private final Handler handler = new Handler();
    private final Runnable runnable = new Runnable() {
        @Override
        public void run() {
            handler.postDelayed(this, 50); // Schedule the task to run again in 1 second
			if (native_stream != 0){
				last_frame = grabFrame(native_stream);
				Bitmap bitmap = bitmapARGBFromByte(last_frame);
				Matrix matrix = new Matrix();
				matrix.postRotate(90);
				matrix.postScale(scale,scale);
				bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, false);
				ImageView imageView = findViewById(R.id.imageView);
				imageView.setImageBitmap(bitmap);
				MaybeEncodeFrame(last_frame) ;
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
		PendingIntent permissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
		if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
			!= PackageManager.PERMISSION_GRANTED) {
			ActivityCompat.requestPermissions(this,
				new String[]{Manifest.permission.CAMERA}, 0);
		}

		UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
		HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
		for (UsbDevice device : deviceList.values()){
			Log.v("ThermalCamera", device.getVendorId() + ":" + device.getProductId() + ":" + device.getProductName());
			if (device.getVendorId() != 0x0BDA){
				continue;
			}
			if (device.getProductId() != 0x5830){
				continue;
			}
			if (!usbManager.hasPermission(device)){
				usbManager.requestPermission(device, permissionIntent);
			}
			
			UsbDeviceConnection usbDeviceConnection = usbManager.openDevice(device);
			fd = usbDeviceConnection.getFileDescriptor();
			native_stream = initializeStream(fd);
		}


		Button frameButton = findViewById(R.id.getFrameButton);

        frameButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
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
				Bitmap bitmap = bitmapARGBFromByte(last_frame);
				SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.getDefault());
				Date now = new Date();
				String dateTimeString = dateFormat.format(now);
				saveImageToGallery(getApplicationContext(), bitmapARGBFromByte(last_frame), "thermal_camera",  dateTimeString + ".png");
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
    }

}
