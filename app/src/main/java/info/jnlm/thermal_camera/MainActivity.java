package info.jnlm.thermal_camera;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public class MainActivity extends Activity {
	static {
		System.loadLibrary("usb-1.0");
		System.loadLibrary("jpeg");
		System.loadLibrary("uvc");
	}
    public native long nativeCreate( long camera_pointer);

    private long mNativePtr;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        if (mNativePtr == 0) {
           mNativePtr = nativeCreate(mNativePtr);
		}
        TextView textView = new TextView(this);
        textView.setText("Hello, World!" + mNativePtr);
        
        setContentView(textView);
    }
}
