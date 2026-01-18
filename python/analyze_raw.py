# import numpy as np
# import matplotlib.pyplot as plt

# filename = "thermal_camera_20240421_101443.bin"

# with open(filename, 'rb') as f:
#     data = np.fromfile(f, dtype=np.uint16)

# data = data.reshape((-1, 192, 256))
# print(f"Video data shape: {data.shape}")

# temp = data[-1].astype('float')
# tmin = -40; tmax = 170
# temp = temp / 65536
# temp = (tmax - tmin) * temp + tmin
# # plt.figure()
# # plt.imshow(data[0], interpolation='none', aspect='auto')
# plt.figure()
# plt.imshow(temp, interpolation='none', aspect='auto')
# plt.show()

import os
import subprocess
import tempfile

import matplotlib.pyplot as plt
import numpy as np

# Pull latest raw capture from device
print("Pulling latest thermal capture from device...")
result = subprocess.run(
    ["adb", "shell", "ls", "-t", "/sdcard/Download/thermal_camera*.bin"],
    capture_output=True,
    text=True,
)
if result.returncode != 0 or not result.stdout.strip():
    raise ValueError(f"No thermal captures found on device: {result.stderr}")

latest_on_device = result.stdout.strip().split("\n")[0]
print(f"Latest on device: {latest_on_device}")

latest_file = os.path.join(tempfile.gettempdir(), os.path.basename(latest_on_device))
subprocess.run(["adb", "pull", latest_on_device, latest_file], check=True)

print(f"Loading data from: {latest_file}")

with open(latest_file, "rb") as f:
    data = np.fromfile(f, dtype=np.uint16)


data = data.reshape((-1, 192, 256))  # Assuming each frame is 192x256
print(f"Video data shape: {data.shape}")
print(f"data stats: {data.min()}, {data.max()}")

# temp = data[-1].astype('float')  # Convert the last frame to float for visualization
temp = data[-1].astype("float")  # Convert the last frame to float for visualization
tmin = -40
tmax = 170  # Temperature range for conversion
temp = temp / 65536  # Normalize the data to 0-1
temp = (tmax - tmin) * temp + tmin  # Convert to temperature scale

plt.figure()
plt.imshow(
    temp, interpolation="none", aspect="auto", cmap="viridis"
)  # Use a colormap that represents temperature
plt.colorbar()  # Show the color bar
plt.show()

temp_int = data[-1].astype("float")  # Convert the last frame to float for visualization
