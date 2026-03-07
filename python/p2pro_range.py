#!/usr/bin/env python3
"""
P2Pro Temperature Range Switching & Measurement

Switches the Infiray P2Pro between high gain (narrow range) and low gain (wide
range) modes, captures thermal frames in each, and saves color-mapped images.

Usage:
    python3 python/p2pro_range.py --video /dev/video1
    python3 python/p2pro_range.py --video /dev/video1 --low-only
    python3 python/p2pro_range.py --video /dev/video1 --high-only

Requires: pyusb, opencv-python, numpy
Needs udev rule for non-root USB access (see below).

== PORTING NOTES FOR ANDROID APP ==

1. GAIN SWITCHING PROTOCOL
   The P2Pro uses Realtek USB vendor commands (bRequest=0x45 for write, 0x44
   for read, wValue=0x78 always). Gain is controlled via the "prop_tpd_params"
   command (0x8514), parameter index 5 (GAIN_SEL).

   To SET gain, use the "long command write" protocol:
     a) ctrl_transfer OUT to wIndex=0x9D00:
        bytes = little_endian_u16(0xC514) + big_endian_u16(5) + big_endian_u32(value)
        where 0xC514 = 0x8514 | 0x4000 (SET flag), 5 = GAIN_SEL, value = 0 or 1
     b) ctrl_transfer OUT to wIndex=0x1D08:
        bytes = big_endian_u32(0) + big_endian_u32(0)   (p3, p4 unused)
     c) Poll ready status until done (see below)

   To GET gain, use the "long command read" protocol:
     a) ctrl_transfer OUT to wIndex=0x9D00:
        bytes = little_endian_u16(0x8514) + big_endian_u16(5) + big_endian_u32(0)
     b) ctrl_transfer OUT to wIndex=0x1D08:
        bytes = big_endian_u32(0) + big_endian_u32(2)   (p3=0, dataLen=2)
     c) Poll ready status until done
     d) ctrl_transfer IN from wIndex=0x1D10, length=2
        Result is big_endian_u16: 0=low gain, 1=high gain

   Ready check: ctrl_transfer IN from wIndex=0x0200, length=1
     Ready when (byte & 0x03) == 0. Error if (byte & 0xFC) != 0.

2. GAIN VALUES
     GAIN_SEL=0 → Low gain  → wide range  → approx -20°C to 400°C+
     GAIN_SEL=1 → High gain → narrow range → approx -20°C to 120-180°C (default)

   High gain has better thermal sensitivity (NETD) but saturates around 180°C.
   Low gain covers much higher temperatures but with reduced sensitivity.

3. TIMING (measured on Linux, USB 2.0)
     - The gain SET command blocks for ~2.6s in the ready-poll loop.
     - After the command returns, the camera continues its internal NUC
       (Non-Uniformity Correction) for another ~3.4s. During this time
       it keeps streaming frames at the OLD gain. This is NOT pipeline
       buffering — it's the camera hardware still recalibrating.
     - Total time from command issue to first new-gain frame: ~6s.
     - Both directions (high→low and low→high) take the same time.
     - This is entirely camera-internal and cannot be reduced.

   APPROACH: Don't use a fixed sleep — issue the command, then
   immediately start reading frames and detect when they change:
     a) Issue the gain SET command (blocks ~2.6s in ready-poll)
     b) Immediately start reading frames (old-gain frames keep flowing)
     c) Detect when frames reflect the new gain mode
        (e.g., if switching to low gain, wait until max temp exceeds the
        high-gain ceiling of ~180°C, or vice versa)
     d) Skip the verify read — it adds 24ms and the command either works
        or throws an error. Frame detection is the real verification.

   In the Android app: the gain switch command should be issued from a
   background thread. The UI can show a "switching..." indicator. Frame
   processing continues normally — old-gain frames keep flowing until
   new-gain frames arrive, so the display never goes blank.

4. SHUTTER / NUC
   The gain switch triggers an automatic NUC. There is NO need to send a
   separate shutter command — in fact, the explicit shutter SET command
   (0x840C | 0x4000) returns error 0x0A on this device.

5. TEMPERATURE CONVERSION (same for both gain modes)
     temp_celsius = raw_uint16 / 64.0 - 273.15
   Raw values are in units of 1/64 Kelvin. This formula works for both
   high and low gain modes — the camera adjusts the raw value range
   internally when switching gains.

6. FRAME FORMAT (same for both gain modes)
     - UVC frame: 256x384 pixels, Y16 (16-bit little-endian)
     - Top half  (rows 0-191):   processed/pseudo-color video
     - Bottom half (rows 192-383): raw 16-bit thermal data
     - Each pixel: 2 bytes, little-endian uint16
     - In OpenCV with CAP_PROP_CONVERT_RGB=0, frame shape is (384, 256, 2)
       Reshape to (2, 192, 256, 2), index [1] = thermal, [0] = video
     - Assemble uint16: (byte[1] << 8) | byte[0]

7. USB IDENTIFIERS
     Vendor:  0x0BDA (Realtek)
     Product: 0x5830
     The P2Pro uses a Realtek USB controller with vendor-specific extensions.

8. USB PERMISSIONS
   For Linux without root, add udev rule:
     echo 'SUBSYSTEM=="usb", ATTR{idVendor}=="0bda", ATTR{idProduct}=="5830", MODE="0666"' \
       | sudo tee /etc/udev/rules.d/99-p2pro.rules
     sudo udevadm control --reload-rules && sudo udevadm trigger
   For Android, USB permissions are handled via UsbManager.requestPermission().

9. OTHER TPD PARAMETERS (prop_tpd_params index values)
     0 = DISTANCE    (1/163.835 m, range 0-32767)
     1 = TU          (1 K, range 0-1024, reflection temperature)
     2 = TA          (1 K, range 0-1024, atmospheric temperature)
     3 = EMS         (1/127, range 0-127, emissivity)
     4 = TAU         (1/127, range 0-127, atmospheric transmittance)
     5 = GAIN_SEL    (binary, 0=low gain, 1=high gain)
   All use the same long_cmd_read / long_cmd_write protocol.
"""

import argparse
import struct
import sys
import time

import cv2
import numpy as np
import usb.core
import usb.util


# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

VENDOR_ID = 0x0BDA
PRODUCT_ID = 0x5830

CMD_PROP_TPD_PARAMS = 0x8514
CMD_DIR_SET = 0x4000

GAIN_SEL = 5
GAIN_LOW = 0   # Wide range (up to ~400°C+)
GAIN_HIGH = 1  # Narrow range, higher sensitivity (default)

GAIN_NAMES = {GAIN_LOW: "LOW (wide range)", GAIN_HIGH: "HIGH (narrow range)"}

# High gain saturates at this temperature — used to detect gain transition
HIGH_GAIN_CEILING = 185.0


# ---------------------------------------------------------------------------
# USB command layer
# ---------------------------------------------------------------------------

def find_device():
    dev = usb.core.find(idVendor=VENDOR_ID, idProduct=PRODUCT_ID)
    if dev is None:
        print("ERROR: P2Pro not found. Is it connected via USB?")
        sys.exit(1)
    try:
        print(f"Found P2Pro: {dev.manufacturer} {dev.product}")
    except (ValueError, usb.core.USBError):
        print(f"Found P2Pro (vendor={VENDOR_ID:#06x}, product={PRODUCT_ID:#06x})")
    return dev


def check_camera_ready(dev):
    ret = dev.ctrl_transfer(0xC1, 0x44, 0x78, 0x200, 1)
    if ret[0] & 1 == 0 and ret[0] & 2 == 0:
        return True
    if ret[0] & 0xFC != 0:
        raise RuntimeError(f"Camera status error: {ret[0]:#X}")
    return False


def block_until_ready(dev, timeout=5):
    start = time.time()
    while True:
        if check_camera_ready(dev):
            return True
        time.sleep(0.001)
        if time.time() > start + timeout:
            return False


def long_cmd_write(dev, cmd, p1, p2, p3=0, p4=0):
    data1 = struct.pack("<H", cmd) + struct.pack(">HI", p1, p2)
    data2 = struct.pack(">II", p3, p4)
    dev.ctrl_transfer(0x41, 0x45, 0x78, 0x9d00, data1)
    dev.ctrl_transfer(0x41, 0x45, 0x78, 0x1d08, data2)
    if not block_until_ready(dev):
        raise RuntimeError("Camera not ready after long_cmd_write")


def long_cmd_read(dev, cmd, p1, p2=0, p3=0, data_len=2):
    data1 = struct.pack("<H", cmd) + struct.pack(">HI", p1, p2)
    data2 = struct.pack(">II", p3, data_len)
    dev.ctrl_transfer(0x41, 0x45, 0x78, 0x9d00, data1)
    dev.ctrl_transfer(0x41, 0x45, 0x78, 0x1d08, data2)
    if not block_until_ready(dev):
        raise RuntimeError("Camera not ready after long_cmd_read")
    res = dev.ctrl_transfer(0xC1, 0x44, 0x78, 0x1d10, data_len)
    return bytes(res)


def get_gain(dev):
    res = long_cmd_read(dev, CMD_PROP_TPD_PARAMS, GAIN_SEL)
    return struct.unpack(">H", res)[0]


def set_gain(dev, gain_value):
    long_cmd_write(dev, CMD_PROP_TPD_PARAMS | CMD_DIR_SET, GAIN_SEL, gain_value)


# ---------------------------------------------------------------------------
# Frame capture layer
# ---------------------------------------------------------------------------

def open_camera(video_device):
    cap = cv2.VideoCapture(video_device)
    if not cap.isOpened():
        print(f"ERROR: Could not open {video_device}")
        sys.exit(1)
    cap.set(cv2.CAP_PROP_CONVERT_RGB, 0)
    return cap


def grab_thermal(cap):
    """Grab one frame, return (temp_array, raw_max) or (None, None)."""
    ret, frame = cap.read()
    if not ret or frame is None:
        return None, None
    frame = np.reshape(frame, (2, 192, 256, 2))
    raw = frame[1, :, :, :].astype(np.intc)
    raw = (raw[:, :, 1] << 8) + raw[:, :, 0]
    temp = raw / 64.0 - 273.15
    return temp, temp.max()


def save_thermal_image(temp, path):
    tmin, tmax = temp.min(), temp.max()
    if tmax - tmin < 0.1:
        tmax = tmin + 0.1
    normalized = ((temp - tmin) / (tmax - tmin) * 255).astype(np.uint8)
    colored = cv2.applyColorMap(normalized, cv2.COLORMAP_INFERNO)
    cv2.putText(colored, f"Max: {tmax:.1f}C", (5, 20),
                cv2.FONT_HERSHEY_SIMPLEX, 0.5, (255, 255, 255), 1)
    cv2.putText(colored, f"Min: {tmin:.1f}C", (5, 40),
                cv2.FONT_HERSHEY_SIMPLEX, 0.5, (255, 255, 255), 1)
    cv2.imwrite(path, colored)
    print(f"Saved: {path}")


# ---------------------------------------------------------------------------
# Optimized gain switch with frame-based detection
# ---------------------------------------------------------------------------

def switch_gain_optimized(dev, cap, target_gain, max_wait_frames=200):
    """Switch gain and wait for frames to reflect the change.

    Instead of a fixed sleep, we:
      1. Issue the gain SET command (~2.6s blocking in ready-poll)
      2. Immediately start reading frames
      3. Detect when frames show the new gain mode's characteristics

    Returns (switched, elapsed_ms):
      switched: True if gain was changed, False if already correct
      elapsed_ms: total time from command start to first valid frame
    """
    current = get_gain(dev)
    print(f"Current gain: {current} = {GAIN_NAMES.get(current, '?')}")

    if current == target_gain:
        return False, 0

    t0 = time.time()
    print(f"Switching to {GAIN_NAMES[target_gain]}...")
    set_gain(dev, target_gain)
    t_cmd = time.time()
    print(f"  Command done: {(t_cmd - t0) * 1000:.0f}ms")

    # Read frames until we detect the new gain mode.
    # High gain saturates at ~180°C. If switching TO low gain, wait for
    # max temp to exceed that ceiling (if there's a hot object). If switching
    # TO high gain, wait for max temp to drop below it.
    # If no hot object is present, we fall back to a frame count timeout.
    for i in range(max_wait_frames):
        temp, tmax = grab_thermal(cap)
        if temp is None:
            continue
        elapsed = (time.time() - t0) * 1000

        if target_gain == GAIN_LOW and tmax > HIGH_GAIN_CEILING:
            print(f"  New gain visible at frame {i} @ {elapsed:.0f}ms (max={tmax:.1f}°C)")
            return True, elapsed
        elif target_gain == GAIN_HIGH and tmax < HIGH_GAIN_CEILING:
            print(f"  New gain visible at frame {i} @ {elapsed:.0f}ms (max={tmax:.1f}°C)")
            return True, elapsed

    # Fallback: no obvious hot object to detect transition, but the command
    # succeeded and enough frames have elapsed (~8s at 25fps).
    elapsed = (time.time() - t0) * 1000
    print(f"  Gain set (no hot reference to detect transition) @ {elapsed:.0f}ms")
    return True, elapsed


# ---------------------------------------------------------------------------
# Capture
# ---------------------------------------------------------------------------

def capture_frames(cap, num_capture=10):
    """Capture frames and return (best_frame, best_max)."""
    best_frame = None
    best_max = -999

    for i in range(num_capture):
        temp, tmax = grab_thermal(cap)
        if temp is None:
            print(f"  Frame {i + 1}: FAILED")
            continue

        tmin, tavg = temp.min(), temp.mean()
        max_loc = np.unravel_index(temp.argmax(), temp.shape)
        print(f"  Frame {i + 1}: min={tmin:.1f}°C  max={tmax:.1f}°C  "
              f"avg={tavg:.1f}°C  max@({max_loc[1]},{max_loc[0]})")

        if tmax > best_max:
            best_max = tmax
            best_frame = temp.copy()

    return best_frame, best_max


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(
        description="P2Pro gain mode switching and thermal capture")
    parser.add_argument("--low-only", action="store_true",
                        help="Only capture in low gain (wide range) mode")
    parser.add_argument("--high-only", action="store_true",
                        help="Only capture in high gain (narrow range) mode")
    parser.add_argument("--video", default="/dev/video0",
                        help="Video device path (default: /dev/video0)")
    parser.add_argument("--frames", type=int, default=10,
                        help="Number of frames to capture per mode (default: 10)")
    args = parser.parse_args()

    if args.low_only:
        modes = [(GAIN_LOW, "/tmp/thermal_low_gain.png")]
    elif args.high_only:
        modes = [(GAIN_HIGH, "/tmp/thermal_high_gain.png")]
    else:
        modes = [
            (GAIN_HIGH, "/tmp/thermal_high_gain.png"),
            (GAIN_LOW, "/tmp/thermal_low_gain.png"),
        ]

    print("Connecting to P2Pro...")
    dev = find_device()
    cap = open_camera(args.video)

    # Warm up the video pipeline
    for _ in range(5):
        cap.read()

    for gain_value, output_path in modes:
        print(f"\n{'=' * 50}")
        print(f"Mode: {GAIN_NAMES[gain_value]}")
        print(f"{'=' * 50}")

        t0 = time.time()
        switched, switch_ms = switch_gain_optimized(dev, cap, gain_value)

        if not switched:
            print("Already in correct gain mode.")

        best_frame, best_max = capture_frames(cap, args.frames)
        total_ms = (time.time() - t0) * 1000

        if best_frame is not None:
            print(f"\nBest max: {best_max:.1f}°C  (total: {total_ms:.0f}ms)")
            save_thermal_image(best_frame, output_path)
        else:
            print("\nERROR: No frames captured.")

    cap.release()

    # Restore high gain as default
    final_gain = get_gain(dev)
    if final_gain != GAIN_HIGH and not args.low_only:
        print("\nRestoring high gain (default)...")
        set_gain(dev, GAIN_HIGH)

    print("\nDone.")


if __name__ == "__main__":
    main()
