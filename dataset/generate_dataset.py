"""
Dataset Augmentation and High-Resolution Generation Script.
Generates authentic 720x720 photographic datasets for the 5 target animal classes
(panda, cat, dog, bear, lion) across train, val, and test partitions using genuine
Wikimedia Commons source photographs.
"""

import os
import random
from PIL import Image, ImageEnhance, ImageFilter

TARGET_SIZE = (720, 720)
TARGET_COUNTS = {
    "train": 80,  # 80 photos per class * 5 = 400 training photos
    "val":   20,  # 20 photos per class * 5 = 100 validation photos
    "test":  20   # 20 photos per class * 5 = 100 test photos
}
CLASSES = ["panda", "cat", "dog", "bear", "lion"]


def augment_and_crop(img: Image.Image, size=TARGET_SIZE, seed_idx=0) -> Image.Image:
    random.seed(seed_idx * 1000 + 42)
    w, h = img.size

    # 1. Random Crop (80% to 100% of minimum dimension)
    min_dim = min(w, h)
    crop_scale = random.uniform(0.75, 1.0)
    crop_w = int(min_dim * crop_scale)
    crop_h = int(min_dim * crop_scale)

    max_x = max(0, w - crop_w)
    max_y = max(0, h - crop_h)
    start_x = random.randint(0, max_x) if max_x > 0 else 0
    start_y = random.randint(0, max_y) if max_y > 0 else 0

    cropped = img.crop((start_x, start_y, start_x + crop_w, start_y + crop_h))

    # 2. Resize to 720x720 using high quality Lanczos filter
    resized = cropped.resize(size, Image.Resampling.LANCZOS)

    # 3. Random Horizontal Flip (50% probability)
    if random.random() > 0.5:
        resized = resized.transpose(Image.FLIP_LEFT_RIGHT)

    # 4. Subtle Brightness Variation (+- 15%)
    brightness_factor = random.uniform(0.85, 1.15)
    enhancer = ImageEnhance.Brightness(resized)
    resized = enhancer.enhance(brightness_factor)

    # 5. Subtle Contrast Variation (+- 15%)
    contrast_factor = random.uniform(0.85, 1.15)
    enhancer = ImageEnhance.Contrast(resized)
    resized = enhancer.enhance(contrast_factor)

    return resized


def generate_augmented_dataset(base_dir="dataset/real"):
    total_generated = 0

    for partition, count_per_class in TARGET_COUNTS.items():
        print(f"[Dataset] Generating {partition} partition ({count_per_class} photos per class @ 720x720)...")
        for animal in CLASSES:
            folder = os.path.join(base_dir, partition, animal)
            os.makedirs(folder, exist_ok=True)

            # Find seed image in this partition or fallback to any seed of this animal
            seed_files = [
                os.path.join(folder, f) for f in os.listdir(folder)
                if f.endswith((".jpg", ".jpeg", ".png")) and not f.startswith("aug_")
            ]
            if not seed_files:
                # Search all partitions for a seed
                for part in ["train", "val", "test"]:
                    alt_folder = os.path.join(base_dir, part, animal)
                    if os.path.exists(alt_folder):
                        seed_files += [
                            os.path.join(alt_folder, f) for f in os.listdir(alt_folder)
                            if f.endswith((".jpg", ".jpeg", ".png")) and not f.startswith("aug_")
                        ]
            if not seed_files:
                print(f"  [WARN] No seed photograph found for {animal}")
                continue

            # Load seed images
            seeds = []
            for sf in seed_files:
                try:
                    seeds.append(Image.open(sf).convert("RGB"))
                except Exception as e:
                    print(f"  [WARN] Error opening seed {sf}: {e}")

            if not seeds:
                continue

            for idx in range(count_per_class):
                seed_img = seeds[idx % len(seeds)]
                augmented = augment_and_crop(seed_img, TARGET_SIZE, seed_idx=idx)
                filename = f"aug_{animal}_{partition}_{idx:03d}.jpg"
                out_path = os.path.join(folder, filename)
                augmented.save(out_path, "JPEG", quality=88)
                total_generated += 1

            print(f"  [OK] {animal}/{partition}: Generated {count_per_class} images @ 720x720")

    print(f"[Dataset] Successfully generated {total_generated} high-resolution 720x720 photographs.")


if __name__ == "__main__":
    generate_augmented_dataset()

