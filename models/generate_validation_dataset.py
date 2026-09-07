"""Generate fixed, versioned validation dataset for SparkShield numerical parity tests.

Generates 400 deterministic feature tensors (100 per class: NORMAL, EMP, OPTICAL, SURGE)
using the 8-frame sliding window FeatureExtractor to represent authentic telemetry stream states.
Outputs:
  - models/validation/validation_set_v1.npz: X (400, 1, 128), y (400,)
  - models/validation/validation_manifest.json: metadata and integrity hashes
"""

import hashlib
import json
import logging
from pathlib import Path
from typing import Tuple

import numpy as np

from python_core.signal_models import FeatureExtractor, SignalClass, SignalGenerator

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(name)s: %(message)s")
logger = logging.getLogger("sparkshield.models.validation_dataset")

REPO_ROOT = Path(__file__).resolve().parent.parent
OUTPUT_DIR = REPO_ROOT / "models" / "validation"
DATASET_FILE = OUTPUT_DIR / "validation_set_v1.npz"
MANIFEST_FILE = OUTPUT_DIR / "validation_manifest.json"


def generate_versioned_dataset(samples_per_class: int = 100, base_seed: int = 777000) -> Tuple[np.ndarray, np.ndarray, dict]:
    """Generates deterministic validation dataset across all 4 classes."""
    classes = [
        (SignalClass.NORMAL, 0, "NORMAL"),
        (SignalClass.EMP, 1, "EMP"),
        (SignalClass.OPTICAL, 2, "OPTICAL"),
        (SignalClass.SURGE, 3, "SURGE"),
    ]

    tensors = []
    labels = []

    for sig_class, class_idx, name in classes:
        logger.info(f"Generating {samples_per_class} validation samples for {name} (class {class_idx})...")
        for i in range(samples_per_class):
            seed = base_seed + (class_idx * 10000) + i
            gen = SignalGenerator(seed=seed)
            extractor = FeatureExtractor()

            # Warm up sliding window with 7 frames of normal background
            for seq in range(7):
                pre_frame, _ = gen.generate(SignalClass.NORMAL, seq=seq)
                extractor.update(pre_frame)

            # Target class event on 8th frame
            target_frame, _ = gen.generate(sig_class, seq=7)
            feature_tensor = extractor.update(target_frame) # shape: (1, 1, 128)

            tensors.append(feature_tensor)
            labels.append(class_idx)

    X = np.concatenate(tensors, axis=0).astype(np.float32) # (400, 1, 128)
    y = np.array(labels, dtype=np.int64)                   # (400,)

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    np.savez_compressed(str(DATASET_FILE), X=X, y=y)

    with open(DATASET_FILE, "rb") as f:
        file_sha256 = hashlib.sha256(f.read()).hexdigest()

    manifest = {
        "dataset_name": "validation_set_v1",
        "filename": DATASET_FILE.name,
        "sha256": file_sha256,
        "total_samples": len(y),
        "samples_per_class": samples_per_class,
        "tensor_shape": list(X.shape),
        "classes": {
            "0": "NORMAL",
            "1": "EMP",
            "2": "OPTICAL",
            "3": "SURGE"
        },
        "statistics": {
            "min": float(np.min(X)),
            "max": float(np.max(X)),
            "mean": float(np.mean(X)),
            "std": float(np.std(X))
        }
    }

    with open(MANIFEST_FILE, "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2)

    logger.info(f"Generated {DATASET_FILE} ({len(y)} samples, SHA256={file_sha256})")
    return X, y, manifest


if __name__ == "__main__":
    generate_versioned_dataset()
