"""SparkShield Calibration Dataset Generator.

Generates realistic telemetry feature vectors using the identical feature extraction
path as training (SignalGenerator + FeatureExtractor) across all four classes:
NORMAL, EMP, OPTICAL, and SURGE.

Writes:
  - Float32 input tensor array with shape [N, 1, 128]
  - Calibration manifest JSON with file paths, shapes, class labels, and seed.
"""

import argparse
import json
import logging
import os
import sys
from typing import Dict, List, Tuple

import numpy as np

# Ensure root directory is in sys.path
_ROOT_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), "../.."))
if _ROOT_DIR not in sys.path:
    sys.path.insert(0, _ROOT_DIR)

from models.train import CLASS_NAMES, SYNTHETIC_DATA_DISCLAIMER
from python_core.signal_models import FeatureExtractor, SignalClass, SignalGenerator

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(name)s: %(message)s")
logger = logging.getLogger("sparkshield.models.calibration")


def generate_calibration_data(
    output_dir: str = "artifacts/calibration",
    manifest_path: str = "artifacts/calibration/manifest.json",
    samples_per_class: int = 50,
    seed: int = 999,
) -> Tuple[np.ndarray, Dict]:
    """Generates balanced calibration telemetry dataset and writes manifest."""
    os.makedirs(output_dir, exist_ok=True)
    manifest_dir = os.path.dirname(manifest_path) or "."
    os.makedirs(manifest_dir, exist_ok=True)

    data_file_path = os.path.join(output_dir, "calibration_tensors.npy")
    logger.info(
        "Generating calibration data (%d samples/class, seed=%d) at '%s'...",
        samples_per_class,
        seed,
        data_file_path,
    )

    rng = np.random.default_rng(seed)
    classes = [
        (SignalClass.NORMAL, 0),
        (SignalClass.EMP, 1),
        (SignalClass.OPTICAL, 2),
        (SignalClass.SURGE, 3),
    ]

    tensors_list: List[np.ndarray] = []
    labels_list: List[int] = []

    for signal_class, class_idx in classes:
        for _ in range(samples_per_class):
            sample_seed = int(rng.integers(1, 2_000_000_000))
            gen = SignalGenerator(seed=sample_seed)
            extractor = FeatureExtractor()

            # Pre-fill sliding history with natural arrival sequence
            num_pre_frames = int(rng.integers(4, 9))
            for pre_seq in range(num_pre_frames):
                pre_frame, _ = gen.generate(SignalClass.NORMAL, seq=pre_seq)
                extractor.update(pre_frame)

            # Generate target frame and extract (1, 1, 128) feature tensor
            target_frame, _ = gen.generate(signal_class, seq=num_pre_frames)
            tensor_1_1_128 = extractor.update(target_frame)

            tensors_list.append(tensor_1_1_128)  # Shape (1, 1, 128)
            labels_list.append(class_idx)

    # Stack to shape (N, 1, 128)
    all_tensors = np.concatenate(tensors_list, axis=0).astype(np.float32)
    all_labels = np.array(labels_list, dtype=np.int64)

    # Shuffle calibration samples
    indices = np.arange(len(all_labels))
    rng.shuffle(indices)
    shuffled_tensors = all_tensors[indices]
    shuffled_labels = all_labels[indices]

    np.save(data_file_path, shuffled_tensors)
    logger.info("Saved %d calibration tensors to '%s' (shape: %s)", len(shuffled_tensors), data_file_path, shuffled_tensors.shape)

    manifest = {
        "manifest_version": "1.0",
        "dataset_name": "SparkShield-INT8-Calibration",
        "disclaimer": SYNTHETIC_DATA_DISCLAIMER,
        "seed": seed,
        "total_samples": int(len(shuffled_labels)),
        "samples_per_class": samples_per_class,
        "sample_tensor_shape": [1, 1, 128],
        "full_dataset_shape": list(shuffled_tensors.shape),
        "dtype": "float32",
        "data_file_path": os.path.relpath(data_file_path, manifest_dir).replace("\\", "/"),
        "class_labels": {str(idx): name for idx, name in enumerate(CLASS_NAMES)},
        "class_distribution": {
            CLASS_NAMES[idx]: int(np.sum(shuffled_labels == idx)) for idx in range(4)
        },
        "statistics": {
            "min": float(np.min(shuffled_tensors)),
            "max": float(np.max(shuffled_tensors)),
            "mean": float(np.mean(shuffled_tensors)),
            "std": float(np.std(shuffled_tensors)),
        },
    }

    with open(manifest_path, "w") as f:
        json.dump(manifest, f, indent=2)
    logger.info("Saved calibration manifest to '%s'", manifest_path)

    return shuffled_tensors, manifest


# Fix type hint for python compatibility
Tuple_np_manifest = tuple


def main():
    parser = argparse.ArgumentParser(description="Generate SparkShield Calibration Dataset")
    parser.add_argument("--output-dir", type=str, default="artifacts/calibration", help="Directory for tensors")
    parser.add_argument("--manifest", type=str, default="artifacts/calibration/manifest.json", help="Manifest path")
    parser.add_argument("--samples-per-class", type=int, default=50, help="Number of samples per class")
    parser.add_argument("--seed", type=int, default=999, help="Random seed")
    args = parser.parse_args()

    generate_calibration_data(
        output_dir=args.output_dir,
        manifest_path=args.manifest,
        samples_per_class=args.samples_per_class,
        seed=args.seed,
    )


if __name__ == "__main__":
    main()
