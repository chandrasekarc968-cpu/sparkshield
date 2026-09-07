"""SparkShield Calibration Dataset Generator.

Generates 200 balanced, realistic telemetry feature vectors (50 per class)
for static INT8 quantization calibration.
"""

import argparse
import json
import logging
import os
import sys

import numpy as np

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "../..")))
from models.train import generate_split

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(name)s: %(message)s")
logger = logging.getLogger("sparkshield.models.calibration")


def generate_calibration_dataset(
    output_path: str = "models/calibration/calibration_data.npy",
    manifest_path: str = "models/calibration/manifest.json",
    samples_per_class: int = 50,
    seed: int = 999,
) -> np.ndarray:
    """Generates balanced calibration dataset and saves manifest."""
    os.makedirs(os.path.dirname(output_path) or ".", exist_ok=True)
    os.makedirs(os.path.dirname(manifest_path) or ".", exist_ok=True)

    logger.info("Generating calibration dataset (%d samples/class, seed=%d)...", samples_per_class, seed)
    x_calib, y_calib = generate_split("calibration", samples_per_class, seed, held_out_ranges=False)

    np.save(output_path, x_calib)
    logger.info("Saved calibration tensors to %s with shape %s", output_path, x_calib.shape)

    manifest = {
        "dataset_name": "SparkShield-INT8-Calibration",
        "total_samples": int(len(y_calib)),
        "samples_per_class": samples_per_class,
        "classes": ["NORMAL", "EMP", "OPTICAL", "SURGE"],
        "class_distribution": {
            "NORMAL": int(np.sum(y_calib == 0)),
            "EMP": int(np.sum(y_calib == 1)),
            "OPTICAL": int(np.sum(y_calib == 2)),
            "SURGE": int(np.sum(y_calib == 3)),
        },
        "input_tensor_shape": list(x_calib.shape[1:]),
        "data_min": float(np.min(x_calib)),
        "data_max": float(np.max(x_calib)),
        "data_mean": float(np.mean(x_calib)),
        "data_std": float(np.std(x_calib)),
        "seed": seed,
    }

    with open(manifest_path, "w") as f:
        json.dump(manifest, f, indent=2)
    logger.info("Saved calibration manifest to %s", manifest_path)

    return x_calib


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Generate SparkShield Calibration Dataset")
    parser.add_argument("--output", type=str, default="models/calibration/calibration_data.npy")
    parser.add_argument("--manifest", type=str, default="models/calibration/manifest.json")
    parser.add_argument("--samples-per-class", type=int, default=50)
    parser.add_argument("--seed", type=int, default=999)
    args = parser.parse_args()
    generate_calibration_dataset(args.output, args.manifest, args.samples_per_class, args.seed)
