import numpy as np

def generate_video_file(width, height, frames, filename):
    """
    Generates a raw video file with random grayscale (uint16) data.

    Parameters:
        width (int): The width of the video in pixels.
        height (int): The height of the video in pixels.
        frames (int): The number of frames in the video.
        filename (str): The filename for the output binary file.
    """
    # Create a random video data array
    # Each pixel's intensity is between 0 and 65535 (uint16)
    # data = np.random.randint(0, 65536, size=(frames, height, width), dtype=np.uint16)
    data = np.zeros((frames, height, width), dtype=np.uint16)

    for f in range(frames):
        phase = (10 * f) % 256
        sin_ = (np.sin(np.linspace(0, 2 * np.pi, width)) * 32767).astype(np.uint16)
        sin_ = np.roll(sin_, phase)
        sin_ = np.tile(sin_, (height, 1))
        data[f] = sin_.reshape((height, width))

    # Write raw video data to a file
    with open(filename, 'wb') as f:
        for frame in data:
            frame.tofile(f)

if __name__ == "__main__":
    # Example dimensions and frame count
    WIDTH = 256
    HEIGHT = 192
    FRAMES = 60
    FILENAME = "example_video.bin"

    generate_video_file(WIDTH, HEIGHT, FRAMES, FILENAME)
    print("Video file generated:", FILENAME)
