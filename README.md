# Third party Android app for Infiray thermal cameras

An implementation of a UVC camera app specific to the Infiray P2Pro thermal camera.

## Features

**Viewing**
- Live thermal camera stream (256x192 @ 25fps)
- 11 color palettes (cycle with the palette button)

**Capture**
- Save images as PNG to gallery
- Save raw 16-bit thermal data as .bin files to Downloads
- Record video with H.264 encoding

**Overlays**
- Center crosshair with temperature reading
- Min/max temperature tracking with markers
- Region of Interest (ROI) - drag to select an area for min/max tracking within a region

**Settings**
- Toggle overlays on/off
- Option to include overlays in saved images and videos
- All settings persist between sessions

**Remote Control**
- Control via intents for automation (adb or other apps):
  - `am broadcast -a info.jnlm.thermal_camera.CONTROL --es action capture_image`
  - `am broadcast -a info.jnlm.thermal_camera.CONTROL --es action capture_raw`
  - `am broadcast -a info.jnlm.thermal_camera.CONTROL --es action start_video`
  - `am broadcast -a info.jnlm.thermal_camera.CONTROL --es action stop_video`
  - `am broadcast -a info.jnlm.thermal_camera.CONTROL --es action cycle_color`
  - `am broadcast -a info.jnlm.thermal_camera.CONTROL --es action toggle_center_crosshair`
  - `am broadcast -a info.jnlm.thermal_camera.CONTROL --es action toggle_minmax`

![Screenshot](images/screenshot.jpg?raw=true "Screenshot")

## Prior works

* [saki4510t/UVCCamera](https://github.com/saki4510t/UVCCamera/tree/master)
* [Peter-St/Android-UVC-Camera](https://github.com/Peter-St/Android-UVC-Camera/tree/master)
* [LeoDJ/P2Pro-Viewer](https://github.com/LeoDJ/P2Pro-Viewer/tree/main)

Thanks to Peter-St for having an app on the playstore that proved my device can operate on my phone.

