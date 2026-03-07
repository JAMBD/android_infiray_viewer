#!/bin/bash
# Thermal Camera ADB Control Script
# Usage: ./thermal_control.sh <command>
# Commands: capture_image, capture_raw, start_video, stop_video, cycle_color,
#           toggle_center_crosshair, toggle_minmax, toggle_roi, toggle_overlay_in_saves,
#           set_scale_auto, set_scale_manual, set_manual_range, set_scale_topbottom,
#           set_manual_top, set_manual_bottom, toggle_gain, set_gain_low, set_gain_high,
#           status, pull_latest_image, pull_latest_video, pull_latest_raw

PACKAGE="info.jnlm.thermal_camera"
ACTIVITY=".MainActivity"

case "$1" in
    capture_image|capture_raw|start_video|stop_video|cycle_color|status|toggle_center_crosshair|toggle_minmax|toggle_roi|toggle_overlay_in_saves|set_scale_auto|set_scale_manual|set_scale_topbottom|toggle_gain|set_gain_low|set_gain_high|toggle_mirror|set_mirror_on|set_mirror_off)
        adb shell am start -n "${PACKAGE}/${ACTIVITY}" --es action "$1"
        ;;
    set_manual_range)
        if [ -z "$2" ]; then
            echo "Usage: $0 set_manual_range <degrees>"
            echo "  degrees: 0.5 to 20.0"
            exit 1
        fi
        adb shell am start -n "${PACKAGE}/${ACTIVITY}" --es action set_manual_range --ef range "$2"
        ;;
    set_manual_top)
        if [ -z "$2" ]; then
            echo "Usage: $0 set_manual_top <temp>"
            echo "  temp: -40.0 to 400.0"
            exit 1
        fi
        adb shell am start -n "${PACKAGE}/${ACTIVITY}" --es action set_manual_top --ef temp "$2"
        ;;
    set_manual_bottom)
        if [ -z "$2" ]; then
            echo "Usage: $0 set_manual_bottom <temp>"
            echo "  temp: -40.0 to 400.0"
            exit 1
        fi
        adb shell am start -n "${PACKAGE}/${ACTIVITY}" --es action set_manual_bottom --ef temp "$2"
        ;;
    auto_mirror)
        orientation=$(adb shell cat /sys/class/typec/port0/orientation 2>/dev/null | tr -d '\r\n')
        if [ "$orientation" = "normal" ]; then
            echo "USB-C orientation: normal -> mirror ON"
            adb shell am start -n "${PACKAGE}/${ACTIVITY}" --es action set_mirror_on
        elif [ "$orientation" = "reverse" ]; then
            echo "USB-C orientation: reverse -> mirror OFF"
            adb shell am start -n "${PACKAGE}/${ACTIVITY}" --es action set_mirror_off
        else
            echo "Could not read USB-C orientation (got: '$orientation')"
            exit 1
        fi
        ;;
    pull_latest_image)
        latest=$(adb shell ls -t /sdcard/Pictures/thermal_camera/*.png 2>/dev/null | head -1 | tr -d '\r')
        if [ -n "$latest" ]; then
            echo "Pulling: $latest"
            adb pull "$latest" /tmp/
        else
            echo "No images found"
            exit 1
        fi
        ;;
    pull_latest_video)
        latest=$(adb shell ls -t /sdcard/Pictures/thermal_camera/*.mp4 2>/dev/null | head -1 | tr -d '\r')
        if [ -n "$latest" ]; then
            echo "Pulling: $latest"
            adb pull "$latest" /tmp/
        else
            echo "No videos found"
            exit 1
        fi
        ;;
    pull_latest_raw)
        latest=$(adb shell ls -t /sdcard/Download/thermal_camera*.bin 2>/dev/null | head -1 | tr -d '\r')
        if [ -n "$latest" ]; then
            echo "Pulling: $latest"
            adb pull "$latest" /tmp/
        else
            echo "No raw files found"
            exit 1
        fi
        ;;
    *)
        echo "Thermal Camera ADB Control"
        echo "Usage: $0 <command>"
        echo ""
        echo "Commands:"
        echo "  capture_image          - Capture PNG image"
        echo "  capture_raw            - Capture raw binary data"
        echo "  start_video            - Start video recording"
        echo "  stop_video             - Stop video recording"
        echo "  cycle_color            - Cycle to next colormap"
        echo "  toggle_center_crosshair - Toggle center crosshair overlay"
        echo "  toggle_minmax          - Toggle min/max tracking overlay"
        echo "  toggle_roi             - Toggle ROI (region of interest) tracking"
        echo "  toggle_overlay_in_saves - Toggle including overlay in saved images/videos"
        echo "  set_scale_auto         - Set color scale to auto mode"
        echo "  set_scale_manual       - Set color scale to manual center ±range mode"
        echo "  set_manual_range <deg> - Set manual range in degrees (0.5-20.0)"
        echo "  set_scale_topbottom    - Set color scale to manual top/bottom mode"
        echo "  set_manual_top <temp>  - Set top temperature (-40.0 to 400.0)"
        echo "  set_manual_bottom <temp> - Set bottom temperature (-40.0 to 400.0)"
        echo "  toggle_gain            - Toggle between high/low gain mode"
        echo "  set_gain_low           - Set low gain (wide range, up to ~400°C)"
        echo "  set_gain_high          - Set high gain (narrow range, more detail)"
        echo "  toggle_mirror          - Toggle image mirror (for USB-C orientation)"
        echo "  set_mirror_on          - Enable image mirror"
        echo "  set_mirror_off         - Disable image mirror"
        echo "  auto_mirror            - Auto-detect USB-C orientation and set mirror"
        echo "  status                 - Log current app status (view with: adb logcat -d | grep STATUS)"
        echo ""
        echo "  pull_latest_image      - Pull most recent PNG to /tmp/"
        echo "  pull_latest_video      - Pull most recent MP4 to /tmp/"
        echo "  pull_latest_raw        - Pull most recent .bin to /tmp/"
        exit 1
        ;;
esac
