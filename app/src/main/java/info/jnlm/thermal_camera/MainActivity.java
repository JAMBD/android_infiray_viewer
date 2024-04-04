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
    public native void sendCtrl(int fd);

	private static final String ACTION_USB_PERMISSION =
            "info.jnlm.thermal_camera.USB_PERMISSION";
	
	private long native_stream = 0;
	private byte[] last_frame;
	private float scale = 1.0f;
	private int fd;

	public Bitmap bitmapFromData(byte[] data){
		int width = 256;
		int height = 192;
		int[] pixels = new int[width * height];
		for (int i=0; i<width; i++){
			for (int j=0; j<height; j++){
				int v = last_frame[(i*height + j) * 2] & 0xFF;
				pixels[i*height + j] = 0xFF000000 | v | (v<<8) | (v << 16);
			}
		}

		return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
	}

	private final Handler handler = new Handler();
    private final Runnable runnable = new Runnable() {
        @Override
        public void run() {
            handler.postDelayed(this, 50); // Schedule the task to run again in 1 second
			if (native_stream != 0){
				last_frame = grabFrame(native_stream);
				Bitmap bitmap = bitmapFromData(last_frame);
				Matrix matrix = new Matrix();
				matrix.postRotate(90);
				matrix.postScale(scale,scale);
				bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, false);
				ImageView imageView = findViewById(R.id.imageView);
				imageView.setImageBitmap(bitmap);
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
				Bitmap bitmap = bitmapFromData(last_frame);
				SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.getDefault());
				Date now = new Date();
				String dateTimeString = dateFormat.format(now);
				saveImageToGallery(getApplicationContext(), bitmapFromData(last_frame), "thermal_camera",  dateTimeString + ".png");
			}
        });


		Button ctrlButton = findViewById(R.id.ctrlButton);

        ctrlButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
				sendCtrl(fd);
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
