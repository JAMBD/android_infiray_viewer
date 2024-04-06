# Minimal andoid demo for UVC cameras

This was a minimal app that tried to use the libraries as-is from HEAD for libuvc and libusb.

To get the app working I did need to patch libuvc to limit the maximum packets_per_transfer.
This is availible as `libuvc.patch`.

This app also demonstrate a minimal save of the image and raw frame. As well as sending
control transfers to the camera.


## Prior works

* [saki4510t/UVCCamera](https://github.com/saki4510t/UVCCamera/tree/master)
* [Peter-St/Android-UVC-Camera](https://github.com/Peter-St/Android-UVC-Camera/master)

Thanks to Peter-St for having an app on the playstore that proved my device can operate on my phone.
