# pull latest video off phone:
adb -s 10.0.0.71:5555 shell 'ls -t /storage/emulated/0/Download/*.bin' | head -n 1 | tr -d '\r' | xargs -I{} adb -s 10.0.0.71:5555 pull {} .
# convert binary video to normalised mp4 video:
ffmpeg -y -f rawvideo -pixel_format gray16le -video_size 256x192 -framerate 25 -i thermal_camera_20240421_105035.bin -vf "normalize=blackpt=black:whitept=white, lut3d=coolwarm_lut.cube" output_video.mp4

