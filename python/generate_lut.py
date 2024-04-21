import numpy as np
import matplotlib.pyplot as plt

def generate_coolwarm_lut(filename, size=32):
    """
    Generates a 3D LUT for the coolwarm color map.

    Parameters:
        filename (str): The path to the output LUT file.
        size (int): The resolution of the LUT. Defaults to 32.
    """
    with open(filename, 'w') as f:
        f.write("TITLE \"Coolwarm LUT\"\n")
        f.write("LUT_3D_SIZE {}\n".format(size))
        f.write("DOMAIN_MIN 0.0 0.0 0.0\n")
        f.write("DOMAIN_MAX 1.0 1.0 1.0\n")

        for b in range(size):
            for g in range(size):
                for r in range(size):
                    value = r / (size - 1)
                    # color = plt.cm.coolwarm(value)[:3]  # Get RGB from coolwarm
                    color = plt.cm.magma(value)[:3]  # Get RGB from coolwarm
                    f.write("{:.6f} {:.6f} {:.6f}\n".format(*color))

if __name__ == "__main__":
    LUT_FILENAME = "coolwarm_lut.cube"
    generate_coolwarm_lut(LUT_FILENAME)
    print(f"Coolwarm LUT file generated: {LUT_FILENAME}")
