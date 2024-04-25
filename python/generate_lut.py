import numpy as np
import matplotlib.pyplot as plt

def generate_colormap_lut_cube(filename, size=32):
    """
    Generates a 3D LUT for the colormap color map.

    Parameters:
        filename (str): The path to the output LUT file.
        size (int): The resolution of the LUT. Defaults to 32.
    """
    with open(filename, 'w') as f:
        f.write("TITLE \"colormap LUT\"\n")
        f.write("LUT_3D_SIZE {}\n".format(size))
        f.write("DOMAIN_MIN 0.0 0.0 0.0\n")
        f.write("DOMAIN_MAX 1.0 1.0 1.0\n")

        for b in range(size):
            for g in range(size):
                for r in range(size):
                    value = r / (size - 1)
                    color = plt.cm.magma(value)[:3]  # Get RGB from colormap
                    f.write("{:.6f} {:.6f} {:.6f}\n".format(*color))

def generate_colormap_lut_raw(filename):
    size = 65536
    # ARGB
    out = np.empty((size, 4), dtype=np.uint8)
    out[:, 0] = 255  # A is max
    print(out[:, 1:].shape, (plt.cm.magma(np.linspace(0, 1, size)) * 255).astype(np.uint8).shape)
    out[:, 1:] = (plt.cm.coolwarm(np.linspace(0, 1, size)) * 255).astype(np.uint8)[:, 0:3]
    out = np.flip(out, axis=1)
    print(out)
    out.tofile(filename)

# def generate_colormap_lut_raw(filename):
#     size = 65536
#     out = np.empty((size, 4), dtype=np.uint8)
#     # out[:] = np.arange(size, dtype=np.uint32).view(np.uint8).reshape(-1, 4)
#     out[:] = np.full((size, 4), [0xEF, 0xBE, 0xAD, 0xDE], dtype=np.uint8)  # Correct assignment
#     out.tofile(filename)
#     print(f"lut: {out}, {out.view(np.int32)}")

if __name__ == "__main__":
    LUT_FILENAME = "colormap_lut.cube"
    generate_colormap_lut_cube(LUT_FILENAME)
    LUT_FILENAME = "colormap_lut.bin"
    generate_colormap_lut_raw(LUT_FILENAME)
    print(f"colormap LUT file generated: {LUT_FILENAME}")