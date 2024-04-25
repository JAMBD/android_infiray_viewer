rm colormap_lut* && python python/generate_lut.py && cp colormap_lut* app/src/main/assets/ && gradle build && adb install ./app/build/outputs/apk/debug/app-debug.apk
