"""SparkShield 1D CNN Classifier Training Pipeline.

Architecture:
  Input: (Batch, 1, 128)
  1. Conv1D(1 -> 16, kernel_size=5, padding=2) -> BatchNorm1d -> ReLU -> MaxPool1d(2)
  2. Conv1D(16 -> 32, kernel_size=3, padding=1) -> BatchNorm1d -> ReLU -> MaxPool1d(2)
  3. Conv1D(32 -> 64, kernel_size=3, padding=1) -> BatchNorm1d -> ReLU
  4. AdaptiveAvgPool1d(1) (Global Average Pooling) -> Flatten (64)
  5. Linear(64 -> 32) -> ReLU
  6. Linear(32 -> 4) (Classes: 0=NORMAL, 1=EMP, 2=OPTICAL, 3=SURGE)
"""

import argparse
import json
import logging
import os
import sys
from typing import Tuple

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("sparkshield.models.train")

try:
    import numpy as np
    import torch
    import torch.nn as nn
    import torch.optim as optim
    from torch.utils.data import DataLoader, TensorDataset
    HAS_TORCH = True
except ImportError:
    HAS_TORCH = False


if HAS_TORCH:
    class SparkShield1DCNN(nn.Module):
        """Small 1D CNN classifier with static (1, 1, 128) input."""

        def __init__(self, num_classes: int = 4):
            super().__init__()
            self.features = nn.Sequential(
                nn.Conv1d(1, 16, kernel_size=5, padding=2),
                nn.BatchNorm1d(16),
                nn.ReLU(inplace=True),
                nn.MaxPool1d(2),
                nn.Conv1d(16, 32, kernel_size=3, padding=1),
                nn.BatchNorm1d(32),
                nn.ReLU(inplace=True),
                nn.MaxPool1d(2),
                nn.Conv1d(32, 64, kernel_size=3, padding=1),
                nn.BatchNorm1d(64),
                nn.ReLU(inplace=True),
                nn.AdaptiveAvgPool1d(1),
            )
            self.classifier = nn.Sequential(
                nn.Linear(64, 32),
                nn.ReLU(inplace=True),
                nn.Linear(32, num_classes),
            )

        def forward(self, x: torch.Tensor) -> torch.Tensor:
            feat = self.features(x)
            flat = feat.view(feat.size(0), -1)
            logits = self.classifier(flat)
            return logits


def generate_dataset(
    num_samples_per_class: int = 1000,
    seed: int = 42,
) -> Tuple[np.ndarray, np.ndarray]:
    """Generates balanced synthetic training dataset using Python core signal generators."""
    sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))
    from python_core.signal_models import FeatureExtractor, SignalClass, SignalGenerator

    gen = SignalGenerator(seed=seed)
    extractor = FeatureExtractor()

    x_data = []
    y_data = []

    classes = [SignalClass.NORMAL, SignalClass.EMP, SignalClass.OPTICAL, SignalClass.SURGE]
    for c_idx, s_class in enumerate(classes):
        for _ in range(num_samples_per_class):
            frame, _ = gen.generate(s_class)
            tensor = extractor.update(frame)
            x_data.append(tensor[0])  # Shape (1, 128)
            y_data.append(c_idx)

    x_arr = np.array(x_data, dtype=np.float32)
    y_arr = np.array(y_data, dtype=np.int64)

    # Shuffle dataset
    rng = np.random.default_rng(seed)
    indices = np.arange(len(y_arr))
    rng.shuffle(indices)

    return x_arr[indices], y_arr[indices]


def main():
    parser = argparse.ArgumentParser(description="Train SparkShield 1D CNN Classifier")
    parser.add_argument("--epochs", type=int, default=15, help="Training epochs")
    parser.add_argument("--samples", type=int, default=1000, help="Samples per class")
    parser.add_argument("--seed", type=int, default=42, help="Random seed")
    parser.add_argument("--output", type=str, default="sparkshield_1d_cnn.pt", help="Checkpoint output path")
    args = parser.parse_args()

    if not HAS_TORCH:
        logger.error("PyTorch is required for model training. Install with: pip install torch")
        sys.exit(1)

    logger.info("Generating dataset (%d samples/class)...", args.samples)
    x_train, y_train = generate_dataset(num_samples_per_class=args.samples, seed=args.seed)
    logger.info("Dataset generated. X shape: %s, Y shape: %s", x_train.shape, y_train.shape)

    model = SparkShield1DCNN(num_classes=4)
    logger.info("Model architecture:\n%s", model)


if __name__ == "__main__":
    main()
