#!/bin/bash
# Thermal Camera ADB Control Script
# Usage: ./thermal_control.sh <command>
# Commands: capture_image, capture_raw, start_video, stop_video, cycle_color,
#           toggle_center_crosshair, toggle_minmax, toggle_roi, status,
#           pull_latest_image, pull_latest_video, pull_latest_raw

PACKAGE="info.jnlm.thermal_camera"
ACTIVITY=".MainActivity"

case "$1" in
    capture_image|capture_raw|start_video|stop_video|cycle_color|status|toggle_center_crosshair|toggle_minmax|toggle_roi)
        adb shell am start -n "${PACKAGE}/${ACTIVITY}" --es action "$1"
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
        echo "  status                 - Log current app status (view with: adb logcat -d | grep STATUS)"
        echo ""
        echo "  pull_latest_image      - Pull most recent PNG to /tmp/"
        echo "  pull_latest_video      - Pull most recent MP4 to /tmp/"
        echo "  pull_latest_raw        - Pull most recent .bin to /tmp/"
        exit 1
        ;;
esac
