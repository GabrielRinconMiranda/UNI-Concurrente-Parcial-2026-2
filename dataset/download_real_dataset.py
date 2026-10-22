"""
Dataset Downloader: Fetches authentic Creative Commons animal photographs
from Wikimedia Commons for the 5 target classes: panda, cat, dog, bear, lion.
Organizes into train, val, and test partitions.
"""

import os
import urllib.request

IMAGE_URLS = {
    "panda": [
        ("train", "panda_01.jpg", "https://upload.wikimedia.org/wikipedia/commons/0/0f/Grosser_Panda.JPG"),
        ("val",   "panda_02.jpg", "https://upload.wikimedia.org/wikipedia/commons/5/5a/Panda_G%C3%A9ant.jpg"),
        ("test",  "panda_03.jpg", "https://upload.wikimedia.org/wikipedia/commons/4/40/Little_Giant_Panda.jpg"),
    ],
    "cat": [
        ("train", "cat_01.jpg", "https://upload.wikimedia.org/wikipedia/commons/3/3a/Cat03.jpg"),
        ("val",   "cat_02.jpg", "https://upload.wikimedia.org/wikipedia/commons/4/4d/Cat_November_2010-1a.jpg"),
        ("test",  "cat_03.jpg", "https://upload.wikimedia.org/wikipedia/commons/6/69/June_odd-eyed-cat_cropped.jpg"),
    ],
    "dog": [
        ("train", "dog_01.jpg", "https://upload.wikimedia.org/wikipedia/commons/8/82/Golden_Retriever_standing_Tucker.jpg"),
        ("val",   "dog_02.jpg", "https://upload.wikimedia.org/wikipedia/commons/7/7a/Huskiesatrest.jpg"),
        ("test",  "dog_03.jpg", "https://upload.wikimedia.org/wikipedia/commons/2/2b/WelshCorgi.jpeg"),
    ],
    "bear": [
        ("train", "bear_01.jpg", "https://upload.wikimedia.org/wikipedia/commons/7/71/2010-kodiak-bear-1.jpg"),
        ("val",   "bear_02.jpg", "https://upload.wikimedia.org/wikipedia/commons/2/2a/Brown_bear_%28Ursus_arctos_arctos%29_running.jpg"),
        ("test",  "bear_03.jpg", "https://upload.wikimedia.org/wikipedia/commons/9/94/Eurasian_brown_bear_%28Ursus_arctos_arctos%29_female_1.jpg"),
    ],
    "lion": [
        ("train", "lion_01.jpg", "https://upload.wikimedia.org/wikipedia/commons/7/73/Lion_waiting_in_Namibia.jpg"),
        ("val",   "lion_02.jpg", "https://upload.wikimedia.org/wikipedia/commons/c/c6/Okonjima_Lioness.jpg"),
        ("test",  "lion_03.jpg", "https://upload.wikimedia.org/wikipedia/commons/9/9e/Lion_%28Panthera_leo%29_male_6y.jpg"),
    ]
}

def download_dataset(target_dir="dataset/real"):
    headers = {"User-Agent": "OpenAICubesAcademicProject/1.0 (edu.concurrente.parcial; contact@example.edu)"}
    success = 0
    total = sum(len(items) for items in IMAGE_URLS.values())

    print(f"[Dataset] Downloading {total} authentic animal photographs from Wikimedia Commons...")
    for animal, files in IMAGE_URLS.items():
        for partition, filename, url in files:
            dest_folder = os.path.join(target_dir, partition, animal)
            os.makedirs(dest_folder, exist_ok=True)
            dest_path = os.path.join(dest_folder, filename)

            try:
                req = urllib.request.Request(url, headers=headers)
                with urllib.request.urlopen(req, timeout=20) as response, open(dest_path, "wb") as out_file:
                    out_file.write(response.read())
                print(f"  [OK] Saved {animal}/{partition}/{filename}")
                success += 1
            except Exception as e:
                print(f"  [WARN] Failed to download {url}: {e}")

    print(f"[Dataset] Download completed: {success}/{total} photos saved into {target_dir}/")

if __name__ == "__main__":
    download_dataset()
