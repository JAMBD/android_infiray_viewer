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

import numpy as np
import matplotlib.pyplot as plt
import os
import glob

directory = "/home/harry/android_infiray_viewer"  # Specify the path to your downloads directory
pattern = os.path.join(directory, '*.bin')
list_of_files = glob.glob(pattern)  # * means all if need specific format then *.csv

if not list_of_files:  # check if list is empty
    raise ValueError("No bin files found in the directory")

latest_file = max(list_of_files, key=os.path.getmtime)  # Get the most recent file

print(f"Loading data from the latest file: {latest_file}")

with open(latest_file, 'rb') as f:
    data = np.fromfile(f, dtype=np.uint16)

data = data.reshape((-1, 192, 256))  # Assuming each frame is 192x256
print(f"Video data shape: {data.shape}")
print(f"data stats: {data.min()}, {data.max()}")

# temp = data[-1].astype('float')  # Convert the last frame to float for visualization
temp = data[50].astype('float')  # Convert the last frame to float for visualization
# tmin = -40; tmax = 170  # Temperature range for conversion
# temp = temp / 65536  # Normalize the data to 0-1
# temp = (tmax - tmin) * temp + tmin  # Convert to temperature scale

plt.figure()
plt.imshow(temp, interpolation='none', aspect='auto', cmap='viridis')  # Use a colormap that represents temperature
plt.colorbar()  # Show the color bar
plt.show()
