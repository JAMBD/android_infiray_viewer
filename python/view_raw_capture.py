#!/usr/bin/env python3
"""
View raw thermal camera captures from the Android app.

Usage:
    python view_raw_capture.py [filename.bin]

If no filename provided, displays the most recent capture.
"""

import sys
import numpy as np
import matplotlib.pyplot as plt
from pathlib import Path

# Frame dimensions
FRAME_WIDTH = 256
FRAME_HEIGHT = 192
FULL_HEIGHT = 384  # Full frame from camera is 256x384

def load_raw_capture(filepath):
    """Load a raw .bin capture file."""
    data = np.fromfile(filepath, dtype=np.uint16)

    # Full frame is 256x384, thermal data is in first 256x192
    if len(data) == FRAME_WIDTH * FULL_HEIGHT:
        # Full frame - extract first half (displayed thermal image)
        data = data[:FRAME_WIDTH * FRAME_HEIGHT]

    return data.reshape((FRAME_HEIGHT, FRAME_WIDTH))

def raw_to_celsius(raw_value):
    """Convert raw sensor value to Celsius.
    Infiray P2Pro: 16-bit value maps to -40°C to 170°C range.
    """
    return (raw_value / 65536.0) * 210.0 - 40.0

def display_thermal_image(frame, title="Thermal Image"):
    """Display thermal image with temperature colorbar."""
    # Convert to Celsius
    temp_celsius = raw_to_celsius(frame.astype(np.float32))

    fig, ax = plt.subplots(figsize=(10, 8))

    # Display with coolwarm colormap (matches app)
    im = ax.imshow(temp_celsius, cmap='coolwarm', aspect='auto')

    # Add colorbar with temperature scale
    cbar = plt.colorbar(im, ax=ax, label='Temperature (°C)')

    # Add crosshair at center
    center_y, center_x = FRAME_HEIGHT // 2, FRAME_WIDTH // 2
    ax.axhline(y=center_y, color='white', linewidth=1, alpha=0.7)
    ax.axvline(x=center_x, color='white', linewidth=1, alpha=0.7)
    ax.plot(center_x, center_y, 'wo', markersize=10, fillstyle='none', markeredgewidth=2)

    # Show center temperature
    center_temp = temp_celsius[center_y, center_x]
    ax.set_title(f"{title}\nCenter: {center_temp:.1f}°C")

    # Stats
    print(f"Temperature range: {temp_celsius.min():.1f}°C to {temp_celsius.max():.1f}°C")
    print(f"Center pixel: {center_temp:.1f}°C")
    print(f"Raw value range: {frame.min()} to {frame.max()}")

    plt.tight_layout()
    plt.show()

def main():
    # Find capture file
    if len(sys.argv) > 1:
        filepath = Path(sys.argv[1])
    else:
        # Find most recent capture in raw_captures directory
        captures_dir = Path(__file__).parent.parent / "raw_captures"
        if not captures_dir.exists():
            captures_dir = Path.cwd()

        bin_files = list(captures_dir.glob("thermal_camera*.bin"))
        if not bin_files:
            print("No .bin files found. Provide a path as argument.")
            sys.exit(1)

        filepath = max(bin_files, key=lambda p: p.stat().st_mtime)
        print(f"Loading most recent: {filepath.name}")

    if not filepath.exists():
        print(f"File not found: {filepath}")
        sys.exit(1)

    # Load and display
    frame = load_raw_capture(filepath)
    display_thermal_image(frame, title=filepath.name)

if __name__ == "__main__":
    main()
