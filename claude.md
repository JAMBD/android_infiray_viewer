# Claude Code Development Notes

## Project Overview

Android app for viewing Infiray P2Pro thermal camera streams over USB.

### Key Files
- `app/src/main/java/info/jnlm/thermal_camera/MainActivity.java` - Main app code
- `app/src/main/cpp/thermal_camera.cpp` - Native JNI bridge for USB/UVC
- `app/src/main/res/layout/activity_main.xml` - UI layout
- `python/view_raw_capture.py` - View captured thermal images

---

## ADB over WiFi (required - USB port used by camera)

With phone connected via USB (before attaching camera):
```bash
adb tcpip 5555
```

Then disconnect USB, attach camera, and connect over WiFi:
```bash
adb connect <phone_ip>:5555
```

---

## Development Commands

### Build, Deploy & Launch
```bash
./gradlew installDebug && adb shell am start -n info.jnlm.thermal_camera/.MainActivity
```

### Pull Screenshot
```bash
adb shell screencap /sdcard/screen.png && adb pull /sdcard/screen.png
```

### Pull Raw Thermal Captures
```bash
mkdir -p raw_captures
adb shell ls -t /sdcard/Download/thermal_camera*.bin | head -3 | xargs -I {} adb pull {} raw_captures/
```

### View Raw Captures
```bash
python python/view_raw_capture.py raw_captures/thermal_camera_*.bin
```

### Stream Logs
```bash
adb logcat -s ThermalCamera:* AndroidRuntime:E
```

---

## Temperature Conversion

Raw 16-bit value to Celsius (from analyze_raw.py):
```
temp_celsius = (raw / 65536.0) * 210.0 - 40.0
```

Maps 0-65535 to -40°C to 170°C range. May need calibration.

---

## Frame Format

- Resolution: 256x192 pixels
- Pixel format: 16-bit grayscale (little-endian)
- Full UVC frame: 256x384 (thermal data in first half)
- Raw capture size: 196608 bytes (256 * 384 * 2)
