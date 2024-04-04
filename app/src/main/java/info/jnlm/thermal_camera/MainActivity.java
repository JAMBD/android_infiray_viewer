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

import android.net.Uri; 
import android.content.ContentValues;
import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.graphics.Bitmap;

import android.widget.ImageView;
import java.io.IOException;
import java.io.OutputStream;

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

	private static final String ACTION_USB_PERMISSION =
            "info.jnlm.thermal_camera.USB_PERMISSION";
	
	long native_stream = -1;
	byte[] last_frame;

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
					Log.v("ThermalCamera", "File written!");
                }
            } catch (IOException e) {
				Log.v("ThermalCamera", "File failed");
            }
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

		Button initButton = findViewById(R.id.initStreamButton);

        initButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
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
					int fd = usbDeviceConnection.getFileDescriptor();
					native_stream = initializeStream(fd);
				}

			}
        });

		Button frameButton = findViewById(R.id.getFrameButton);

        frameButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
				last_frame = grabFrame(native_stream);
				// saveBytesToFileInDownloads(getApplicationContext(), last_frame, "test.bin");
				int width = 256;
				int height = 192;
				int[] pixels = new int[width * height];
				for (int i=0; i<width; i++){
					for (int j=0; j<height; j++){
						int v = last_frame[(i*height + j) * 2] & 0xFF;
						pixels[i*height + j] = 0xFF000000 | v | (v<<8) | (v << 16);
					}
				}

				Matrix matrix = new Matrix();
				matrix.postRotate(90);
				Bitmap bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
				bitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, false);
				ImageView imageView = findViewById(R.id.imageView);
				imageView.setImageBitmap(bitmap);
			}
        });

        int width = 192;
        int height = 256;
        int[] pixels = new int[width * height];
		for (int i=0; i<width; i++){
			for (int j=0; j<height; j++){
				pixels[i*height + j] = 0xFF000000;
			}
		}

        Bitmap bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
        ImageView imageView = findViewById(R.id.imageView);
        imageView.setImageBitmap(bitmap);
	}

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

}
