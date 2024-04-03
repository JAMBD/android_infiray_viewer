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

import java.util.HashMap;

public class MainActivity extends Activity {
	static {
		System.loadLibrary("usb-1.0");
		System.loadLibrary("uvc");
		System.loadLibrary("thermalcamera");
	}
    public native void initializeLibUsb(int fd);

	private static final String ACTION_USB_PERMISSION =
            "info.jnlm.thermal_camera.USB_PERMISSION";

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

		Button myButton = findViewById(R.id.myButton);
        myButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                // Handle button click here

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
					initializeLibUsb(fd);
				}

			}
        });
		//TextView textView = new TextView(this);
		//textView.setText("Hello, World! "); 
		//setContentView(textView);
	}

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

}
