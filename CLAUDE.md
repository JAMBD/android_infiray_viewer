# Claude Code Development Notes

## Debugging Guidelines

After a successful debugging session, **delete incorrect guesses and temporary debugging code** (extra logging, unused variables, dead code paths) to keep the codebase clean. Only keep the actual fix.

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

---

## ADB Remote Control

The app can be controlled via ADB intents, enabling AI/automation to trigger actions without manual interaction. **Use this capability as a matter of course when developing and testing new features.**

### Helper Script
```bash
./scripts/thermal_control.sh <command>
```

### Available Commands

| Command | Description |
|---------|-------------|
| `capture_image` | Save PNG to Pictures/thermal_camera/ |
| `capture_raw` | Save raw binary to Downloads/ |
| `start_video` | Start MP4 recording |
| `stop_video` | Stop recording |
| `cycle_color` | Cycle to next colormap |
| `toggle_center_crosshair` | Toggle center crosshair overlay |
| `toggle_minmax` | Toggle min/max tracking overlay |
| `toggle_roi` | Toggle ROI (region of interest) tracking |
| `toggle_overlay_in_saves` | Toggle including overlay in saved images/videos |
| `set_scale_auto` | Set color scale to auto mode |
| `set_scale_manual` | Set color scale to manual center ±range mode |
| `set_manual_range <deg>` | Set manual range in degrees (0.5-20.0) |
| `status` | Log current state (view with `adb logcat -d \| grep STATUS`) |
| `pull_latest_image` | Pull most recent PNG to /tmp/ |
| `pull_latest_video` | Pull most recent MP4 to /tmp/ |
| `pull_latest_raw` | Pull most recent .bin to /tmp/ |

### Direct ADB Usage
```bash
adb shell am start -n info.jnlm.thermal_camera/.MainActivity --es action <command>
```

### Direct ADB Usage (with parameters)
```bash
# For set_manual_range which needs a float parameter:
adb shell am start -n info.jnlm.thermal_camera/.MainActivity --es action set_manual_range --ef range 5.0
```

### Adding New Remote Actions

When adding new features or actions to the app, **always add corresponding ADB remote control support**:

1. Add a new case to `handleControlAction()` in MainActivity.java
2. Add the command to `scripts/thermal_control.sh`
3. Update this documentation

This ensures all app functionality can be triggered programmatically for testing and automation.

---

## Remote Testing (REQUIRED)

**After implementing any feature, Claude MUST test it remotely via ADB before considering the task done.** Do not ask the user to test manually — use the tools below to verify the feature works end-to-end on the device.

### Testing Workflow

1. **Build & deploy**: `./gradlew installDebug && adb shell am start -n info.jnlm.thermal_camera/.MainActivity`
2. **Wait for app launch**: `sleep 2`
3. **Trigger the feature** via `./scripts/thermal_control.sh <command>` or direct ADB intents
4. **Take a screenshot**: `adb shell screencap /sdcard/screen.png && adb pull /sdcard/screen.png /tmp/`
5. **View the screenshot** using the Read tool on the pulled image file to visually verify the result
6. **Check logs** if needed: `adb logcat -d | grep ThermalCamera | tail -20`
7. **Test multiple states** (e.g. toggle on/off, different parameter values) — take screenshots of each
8. **Test persistence**: kill and relaunch the app, screenshot again to verify settings survived

### Screenshot Verification

When verifying UI changes, always pull and view screenshots. Look for:
- New UI elements appearing/disappearing as expected
- Text labels showing correct values
- Visual changes in the thermal image (e.g. color scale differences)

### Quick Reference
```bash
# Full build-deploy-screenshot cycle
./gradlew installDebug && adb shell am start -n info.jnlm.thermal_camera/.MainActivity && sleep 3 && adb shell screencap /sdcard/screen.png && adb pull /sdcard/screen.png /tmp/

# Screenshot only
adb shell screencap /sdcard/screen.png && adb pull /sdcard/screen.png /tmp/

# Check recent logs
adb logcat -d | grep ThermalCamera | tail -30
```
