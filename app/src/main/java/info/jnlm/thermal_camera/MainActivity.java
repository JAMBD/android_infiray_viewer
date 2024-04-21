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
import java.io.FileNotFoundException;

import android.net.Uri;
import android.content.ContentValues;
import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.graphics.Bitmap;


import android.view.WindowManager;
import android.widget.ImageView;
import java.io.IOException;
import java.io.OutputStream;
import android.util.DisplayMetrics;
import android.os.Handler;
import android.graphics.Matrix;

import java.util.HashMap;

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
	
	private long native_stream = 0;
	private byte[] last_frame;
	private float scale = 1.0f;
	private int color = 1;
	private int fd;

	private boolean isRecording = false;
    private Uri fileUri;
    private OutputStream outputStream;



	private static final int kFrameWidth = 256;
	private static final int kFrameHeight = 192;
	private static final int kPixelSize = 2;

	private final int[] r_v = {-175, -174, -172, -171, -169, -168, -167, -165, -164, -163, -161, -160, -159, -157, -156, -154, -153, -152, -150, -149, -148, -146, -145, -143, -142, -141, -139, -138, -137, -135, -134, -132, -131, -130, -128, -127, -126, -124, -123, -121, -120, -119, -117, -116, -115, -113, -112, -111, -109, -108, -106, -105, -104, -102, -101, -100, -98, -97, -95, -94, -93, -91, -90, -89, -87, -86, -84, -83, -82, -80, -79, -78, -76, -75, -74, -72, -71, -69, -68, -67, -65, -64, -63, -61, -60, -58, -57, -56, -54, -53, -52, -50, -49, -47, -46, -45, -43, -42, -41, -39, -38, -37, -35, -34, -32, -31, -30, -28, -27, -26, -24, -23, -21, -20, -19, -17, -16, -15, -13, -12, -10, -9, -8, -6, -5, -4, -2, -1, 0, 1, 2, 4, 5, 6, 8, 9, 10, 12, 13, 15, 16, 17, 19, 20, 21, 23, 24, 26, 27, 28, 30, 31, 32, 34, 35, 37, 38, 39, 41, 42, 43, 45, 46, 47, 49, 50, 52, 53, 54, 56, 57, 58, 60, 61, 63, 64, 65, 67, 68, 69, 71, 72, 74, 75, 76, 78, 79, 80, 82, 83, 84, 86, 87, 89, 90, 91, 93, 94, 95, 97, 98, 100, 101, 102, 104, 105, 106, 108, 109, 111, 112, 113, 115, 116, 117, 119, 120, 121, 123, 124, 126, 127, 128, 130, 131, 132, 134, 135, 137, 138, 139, 141, 142, 143, 145, 146, 148, 149, 150, 152, 153, 154, 156, 157, 159, 160, 161, 163, 164, 165, 167, 168, 169, 171, 172, 174};
	private final int[] g_v = {89, 88, 87, 87, 86, 85, 85, 84, 83, 83, 82, 81, 80, 80, 79, 78, 78, 77, 76, 76, 75, 74, 73, 73, 72, 71, 71, 70, 69, 69, 68, 67, 67, 66, 65, 64, 64, 63, 62, 62, 61, 60, 60, 59, 58, 57, 57, 56, 55, 55, 54, 53, 53, 52, 51, 50, 50, 49, 48, 48, 47, 46, 46, 45, 44, 43, 43, 42, 41, 41, 40, 39, 39, 38, 37, 36, 36, 35, 34, 34, 33, 32, 32, 31, 30, 30, 29, 28, 27, 27, 26, 25, 25, 24, 23, 23, 22, 21, 20, 20, 19, 18, 18, 17, 16, 16, 15, 14, 13, 13, 12, 11, 11, 10, 9, 9, 8, 7, 6, 6, 5, 4, 4, 3, 2, 2, 1, 0, 0, 0, -1, -2, -2, -3, -4, -4, -5, -6, -6, -7, -8, -9, -9, -10, -11, -11, -12, -13, -13, -14, -15, -16, -16, -17, -18, -18, -19, -20, -20, -21, -22, -23, -23, -24, -25, -25, -26, -27, -27, -28, -29, -30, -30, -31, -32, -32, -33, -34, -34, -35, -36, -36, -37, -38, -39, -39, -40, -41, -41, -42, -43, -43, -44, -45, -46, -46, -47, -48, -48, -49, -50, -50, -51, -52, -53, -53, -54, -55, -55, -56, -57, -57, -58, -59, -60, -60, -61, -62, -62, -63, -64, -64, -65, -66, -67, -67, -68, -69, -69, -70, -71, -71, -72, -73, -73, -74, -75, -76, -76, -77, -78, -78, -79, -80, -80, -81, -82, -83, -83, -84, -85, -85, -86, -87, -87, -88};
	private final int[] g_u = {43, 42, 42, 42, 41, 41, 41, 40, 40, 40, 39, 39, 39, 38, 38, 38, 37, 37, 37, 36, 36, 36, 35, 35, 35, 34, 34, 34, 33, 33, 33, 32, 32, 32, 31, 31, 31, 30, 30, 30, 29, 29, 29, 28, 28, 28, 27, 27, 27, 26, 26, 25, 25, 25, 24, 24, 24, 23, 23, 23, 22, 22, 22, 21, 21, 21, 20, 20, 20, 19, 19, 19, 18, 18, 18, 17, 17, 17, 16, 16, 16, 15, 15, 15, 14, 14, 14, 13, 13, 13, 12, 12, 12, 11, 11, 11, 10, 10, 10, 9, 9, 9, 8, 8, 8, 7, 7, 7, 6, 6, 6, 5, 5, 5, 4, 4, 4, 3, 3, 3, 2, 2, 2, 1, 1, 1, 0, 0, 0, 0, 0, -1, -1, -1, -2, -2, -2, -3, -3, -3, -4, -4, -4, -5, -5, -5, -6, -6, -6, -7, -7, -7, -8, -8, -8, -9, -9, -9, -10, -10, -10, -11, -11, -11, -12, -12, -12, -13, -13, -13, -14, -14, -14, -15, -15, -15, -16, -16, -16, -17, -17, -17, -18, -18, -18, -19, -19, -19, -20, -20, -20, -21, -21, -21, -22, -22, -22, -23, -23, -23, -24, -24, -24, -25, -25, -25, -26, -26, -27, -27, -27, -28, -28, -28, -29, -29, -29, -30, -30, -30, -31, -31, -31, -32, -32, -32, -33, -33, -33, -34, -34, -34, -35, -35, -35, -36, -36, -36, -37, -37, -37, -38, -38, -38, -39, -39, -39, -40, -40, -40, -41, -41, -41, -42, -42, -42};
	private final int[] b_u = {-221, -220, -218, -216, -214, -213, -211, -209, -207, -206, -204, -202, -200, -199, -197, -195, -194, -192, -190, -188, -187, -185, -183, -181, -180, -178, -176, -174, -173, -171, -169, -168, -166, -164, -162, -161, -159, -157, -155, -154, -152, -150, -148, -147, -145, -143, -142, -140, -138, -136, -135, -133, -131, -129, -128, -126, -124, -123, -121, -119, -117, -116, -114, -112, -110, -109, -107, -105, -103, -102, -100, -98, -97, -95, -93, -91, -90, -88, -86, -84, -83, -81, -79, -77, -76, -74, -72, -71, -69, -67, -65, -64, -62, -60, -58, -57, -55, -53, -51, -50, -48, -46, -45, -43, -41, -39, -38, -36, -34, -32, -31, -29, -27, -25, -24, -22, -20, -19, -17, -15, -13, -12, -10, -8, -6, -5, -3, -1, 0, 1, 3, 5, 6, 8, 10, 12, 13, 15, 17, 19, 20, 22, 24, 25, 27, 29, 31, 32, 34, 36, 38, 39, 41, 43, 45, 46, 48, 50, 51, 53, 55, 57, 58, 60, 62, 64, 65, 67, 69, 71, 72, 74, 76, 77, 79, 81, 83, 84, 86, 88, 90, 91, 93, 95, 97, 98, 100, 102, 103, 105, 107, 109, 110, 112, 114, 116, 117, 119, 121, 123, 124, 126, 128, 129, 131, 133, 135, 136, 138, 140, 142, 143, 145, 147, 148, 150, 152, 154, 155, 157, 159, 161, 162, 164, 166, 168, 169, 171, 173, 174, 176, 178, 180, 181, 183, 185, 187, 188, 190, 192, 194, 195, 197, 199, 200, 202, 204, 206, 207, 209, 211, 213, 214, 216, 218, 220};
	public Bitmap bitmapARGBFromByte(byte[] data){
		int[] pixels = new int[kFrameWidth * kFrameHeight];
		for (int i=0; i<kFrameWidth; i++){
			for (int j=0; j<kFrameHeight; j++){
				
				final int y = last_frame[(i*kFrameHeight + j) * 2] & 0xFF;
				final int v = last_frame[1 + (i*kFrameHeight + (j&0xFE)) * 2] & 0xFF;
				final int u = last_frame[1 + (i*kFrameHeight + (j&0xFE)+1) * 2] & 0xFF;
				int r = y + r_v[v];
				int g = y + g_v[v] + g_u[u];
				int b = y + b_u[u];
				if (r < 0) r = 0;
				if (g < 0) g = 0;
				if (b < 0) b = 0;
				if (r > 255) r = 255;
				if (g > 255) g = 255;
				if (b > 255) b = 255;
				pixels[i*kFrameHeight + j] = 0xFF000000 | r | (g<<8) | (b << 16);
			}
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

	public void startRecording(View view) {

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
