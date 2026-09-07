"""SparkShield 1D CNN Classifier Training Pipeline.

Generates leak-free balanced synthetic telemetry datasets across NORMAL, EMP,
OPTICAL, and SURGE classes, trains the SparkShield1DCNN architecture, selects the
best checkpoint by validation Macro F1, and saves model checkpoint with metadata.
"""

import argparse
import json
import logging
import os
import random
import sys
from typing import Dict, List, Optional, Tuple

import numpy as np
import torch
import torch.nn as nn
import torch.optim as optim
from sklearn.metrics import f1_score
from torch.utils.data import DataLoader, TensorDataset

# Ensure python_core is in path
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))
from python_core.signal_models import FeatureExtractor, SignalClass, SignalGenerator

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(name)s: %(message)s")
logger = logging.getLogger("sparkshield.models.train")

CLASS_NAMES = ["NORMAL", "EMP", "OPTICAL", "SURGE"]
INPUT_SHAPE = [1, 1, 128]
FEATURE_COUNT_PER_FRAME = 16
WINDOW_FRAME_COUNT = 8

FEATURE_LAYOUT = [
    "0: peak_mv / 65535.0 (Linear peak voltage)",
    "1: rise_time_code / 65535.0 (Linear rise time)",
    "2: log1p(rise_time_code) / log1p(65535) (Log rise time)",
    "3: decay_time_us / 65535.0 (Linear decay time)",
    "4: log1p(decay_time_us) / log1p(65535) (Log decay time)",
    "5: optical_sensor_mv / 65535.0 (Linear optical sensor)",
    "6: min(optical_sensor_mv / 5000.0, 1.0) (Optical saturation rail proximity)",
    "7: sum(bins[4:8]) / (sum(bins) + 1e-5) (High-frequency RF energy proportion)",
    "8-15: fft_energy_bins[0..7] / 255.0 (8 normalized FFT energy bins)",
]


def set_seed(seed: int):
    """Sets random seeds deterministically across Python, NumPy, and PyTorch."""
    random.seed(seed)
    np.random.seed(seed)
    torch.manual_seed(seed)
    if torch.cuda.is_available():
        torch.cuda.manual_seed_all(seed)


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


def generate_split(
    split_name: str,
    samples_per_class: int,
    seed: int,
    held_out_ranges: bool = False,
) -> Tuple[np.ndarray, np.ndarray]:
    """Generates a balanced dataset split without leakage.

    Each sample uses an independent generator stream and a dedicated
    FeatureExtractor instance so no state leaks between samples or splits.

    Args:
        split_name: Name of the split ("train", "val", "test").
        samples_per_class: Number of samples to generate per class.
        seed: Unique random seed for this split.
        held_out_ranges: If True, adds wider parameter perturbation for test generalization.

    Returns:
        X: (N, 1, 128) float32 array.
        y: (N,) int64 array.
    """
    logger.info(
        "Generating split '%s' (seed=%d, samples_per_class=%d, held_out=%s)...",
        split_name,
        seed,
        samples_per_class,
        held_out_ranges,
    )

    rng = np.random.default_rng(seed)
    classes = [
        (SignalClass.NORMAL, 0),
        (SignalClass.EMP, 1),
        (SignalClass.OPTICAL, 2),
        (SignalClass.SURGE, 3),
    ]

    x_list: List[np.ndarray] = []
    y_list: List[int] = []

    for signal_class, class_idx in classes:
        for i in range(samples_per_class):
            sample_seed = int(rng.integers(1, 2_000_000_000))
            gen = SignalGenerator(seed=sample_seed)
            extractor = FeatureExtractor()

            # Optional held-out parameter perturbation for test set
            if held_out_ranges:
                # Add mild grid frequency fluctuation (e.g. 48.5 Hz or 51.5 Hz)
                gen.grid_freq_hz = float(rng.uniform(48.5, 51.5))

            # Simulate a multi-frame arrival sequence to fill sliding history naturally
            # 7 background normal frames, then target class frame
            num_pre_frames = int(rng.integers(4, 9))
            for pre_seq in range(num_pre_frames):
                pre_frame, _ = gen.generate(SignalClass.NORMAL, seq=pre_seq)
                extractor.update(pre_frame)

            # Target frame
            target_seq = num_pre_frames
            target_frame, _ = gen.generate(signal_class, seq=target_seq)
            tensor_1_1_128 = extractor.update(target_frame)

            x_list.append(tensor_1_1_128[0])  # Shape (1, 128)
            y_list.append(class_idx)

    x_arr = np.array(x_list, dtype=np.float32)
    y_arr = np.array(y_list, dtype=np.int64)

    # Shuffle the split
    shuffle_indices = np.arange(len(y_arr))
    rng.shuffle(shuffle_indices)

    x_shuffled = x_arr[shuffle_indices]
    y_shuffled = y_arr[shuffle_indices]

    logger.info(
        "Split '%s' complete: X shape %s (dtype %s), y shape %s (dtype %s)",
        split_name,
        x_shuffled.shape,
        x_shuffled.dtype,
        y_shuffled.shape,
        y_shuffled.dtype,
    )
    return x_shuffled, y_shuffled


def train_model(
    model: nn.Module,
    train_loader: DataLoader,
    val_loader: DataLoader,
    epochs: int = 15,
    lr: float = 0.001,
    device: Optional[torch.device] = None,
) -> Tuple[nn.Module, Dict[str, List[float]], float, Dict]:
    """Trains SparkShield1DCNN with validation Macro F1 model checkpointing."""
    if device is None:
        device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    model = model.to(device)

    criterion = nn.CrossEntropyLoss()
    optimizer = optim.Adam(model.parameters(), lr=lr, weight_decay=1e-4)

    history: Dict[str, List[float]] = {
        "train_loss": [],
        "val_loss": [],
        "val_acc": [],
        "val_macro_f1": [],
    }

    best_macro_f1 = -1.0
    best_weights = None

    logger.info("Starting training for %d epochs on device '%s'...", epochs, device)

    for epoch in range(1, epochs + 1):
        # Training phase
        model.train()
        running_loss = 0.0
        total_train_samples = 0

        for batch_x, batch_y in train_loader:
            batch_x = batch_x.to(device)
            batch_y = batch_y.to(device)

            optimizer.zero_grad()
            logits = model(batch_x)
            loss = criterion(logits, batch_y)
            loss.backward()
            optimizer.step()

            running_loss += loss.item() * batch_x.size(0)
            total_train_samples += batch_x.size(0)

        epoch_train_loss = running_loss / max(1, total_train_samples)

        # Validation phase
        model.eval()
        val_loss = 0.0
        val_samples = 0
        all_preds = []
        all_targets = []

        with torch.no_grad():
            for batch_x, batch_y in val_loader:
                batch_x = batch_x.to(device)
                batch_y = batch_y.to(device)

                logits = model(batch_x)
                loss = criterion(logits, batch_y)

                val_loss += loss.item() * batch_x.size(0)
                val_samples += batch_x.size(0)

                preds = torch.argmax(logits, dim=1).cpu().numpy()
                all_preds.extend(preds)
                all_targets.extend(batch_y.cpu().numpy())

        epoch_val_loss = val_loss / max(1, val_samples)
        epoch_val_acc = float(np.mean(np.array(all_preds) == np.array(all_targets)))
        epoch_macro_f1 = float(f1_score(all_targets, all_preds, average="macro"))

        history["train_loss"].append(epoch_train_loss)
        history["val_loss"].append(epoch_val_loss)
        history["val_acc"].append(epoch_val_acc)
        history["val_macro_f1"].append(epoch_macro_f1)

        logger.info(
            "Epoch [%02d/%02d] - Train Loss: %.4f | Val Loss: %.4f | Val Acc: %.4f | Val Macro F1: %.4f",
            epoch,
            epochs,
            epoch_train_loss,
            epoch_val_loss,
            epoch_val_acc,
            epoch_macro_f1,
        )

        if epoch_macro_f1 > best_macro_f1:
            best_macro_f1 = epoch_macro_f1
            best_weights = {k: v.cpu().clone() for k, v in model.state_dict().items()}
            logger.info(">>> New best model found at epoch %d (Val Macro F1: %.4f)", epoch, best_macro_f1)

    if best_weights is not None:
        model.load_state_dict(best_weights)

    return model, history, best_macro_f1, best_weights


def main():
    parser = argparse.ArgumentParser(description="SparkShield 1D CNN Model Training")
    parser.add_argument("--epochs", type=int, default=12, help="Number of training epochs")
    parser.add_argument("--batch-size", type=int, default=32, help="Batch size")
    parser.add_argument("--lr", type=float, default=0.001, help="Learning rate")
    parser.add_argument("--train-samples", type=int, default=1000, help="Train samples per class")
    parser.add_argument("--val-samples", type=int, default=200, help="Val samples per class")
    parser.add_argument("--test-samples", type=int, default=200, help="Test samples per class")
    parser.add_argument("--seed", type=int, default=42, help="Master random seed")
    parser.add_argument("--output", type=str, default="models/sparkshield_1d_cnn.pt", help="Checkpoint path")
    parser.add_argument("--metadata-out", type=str, default="models/split_metadata.json", help="Metadata path")
    args = parser.parse_args()

    set_seed(args.seed)

    # 1. Independent seeds for splits to eliminate leakage
    train_seed = args.seed + 101
    val_seed = args.seed + 202
    test_seed = args.seed + 303

    # 2. Generate datasets
    x_train, y_train = generate_split("train", args.train_samples, train_seed, held_out_ranges=False)
    x_val, y_val = generate_split("val", args.val_samples, val_seed, held_out_ranges=False)
    x_test, y_test = generate_split("test", args.test_samples, test_seed, held_out_ranges=True)

    # Save test dataset for evaluate.py and test scripts
    test_data_path = "models/test_data.npz"
    np.savez_compressed(test_data_path, x_test=x_test, y_test=y_test)
    logger.info("Saved test dataset to %s", test_data_path)

    # 3. Create PyTorch DataLoaders
    train_dataset = TensorDataset(torch.from_numpy(x_train), torch.from_numpy(y_train))
    val_dataset = TensorDataset(torch.from_numpy(x_val), torch.from_numpy(y_val))

    train_loader = DataLoader(train_dataset, batch_size=args.batch_size, shuffle=True)
    val_loader = DataLoader(val_dataset, batch_size=args.batch_size, shuffle=False)

    # 4. Initialize model
    model = SparkShield1DCNN(num_classes=4)

    # 5. Train model
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    trained_model, history, best_val_f1, best_weights = train_model(
        model=model,
        train_loader=train_loader,
        val_loader=val_loader,
        epochs=args.epochs,
        lr=args.lr,
        device=device,
    )

    # 6. Save checkpoint with full metadata
    checkpoint_payload = {
        "model_state_dict": best_weights,
        "class_names": CLASS_NAMES,
        "input_shape": INPUT_SHAPE,
        "feature_count_per_frame": FEATURE_COUNT_PER_FRAME,
        "window_frame_count": WINDOW_FRAME_COUNT,
        "seed": args.seed,
        "normalization": {
            "peak_mv_scale": 65535.0,
            "rise_time_scale": 65535.0,
            "decay_time_scale": 65535.0,
            "optical_mv_scale": 65535.0,
            "optical_rail_mv": 5000.0,
            "fft_energy_scale": 255.0,
            "feature_range": [0.0, 1.0],
        },
        "training_config": {
            "epochs": args.epochs,
            "batch_size": args.batch_size,
            "lr": args.lr,
            "optimizer": "Adam",
            "loss": "CrossEntropyLoss",
            "train_samples_per_class": args.train_samples,
            "val_samples_per_class": args.val_samples,
            "test_samples_per_class": args.test_samples,
            "device": str(device),
        },
        "best_val_macro_f1": best_val_f1,
    }

    os.makedirs(os.path.dirname(args.output) or ".", exist_ok=True)
    torch.save(checkpoint_payload, args.output)
    logger.info("Saved model checkpoint to %s", args.output)

    # 7. Persist split metadata JSON
    split_metadata = {
        "seed": args.seed,
        "train_seed": train_seed,
        "val_seed": val_seed,
        "test_seed": test_seed,
        "sample_counts": {
            "train_total": len(y_train),
            "val_total": len(y_val),
            "test_total": len(y_test),
            "per_class_train": args.train_samples,
            "per_class_val": args.val_samples,
            "per_class_test": args.test_samples,
        },
        "class_mapping": {str(idx): name for idx, name in enumerate(CLASS_NAMES)},
        "feature_layout": FEATURE_LAYOUT,
        "input_shape": INPUT_SHAPE,
        "normalization_rules": checkpoint_payload["normalization"],
        "parameter_ranges": {
            "NORMAL": {
                "peak_mv": [2800, 3600],
                "rise_time_code": [1000, 65535],
                "decay_time_us": [1000, 65535],
                "optical_sensor_mv": [50, 400],
            },
            "EMP": {
                "peak_mv": [20000, 65535],
                "rise_time_code": [1, 3],
                "decay_time_us": [1, 15],
                "optical_sensor_mv": [50, 400],
            },
            "OPTICAL": {
                "peak_mv": [2800, 3600],
                "rise_time_code": [20000, 45000],
                "decay_time_us": [30000, 60000],
                "optical_sensor_mv": [3200, 5000],
            },
            "SURGE": {
                "peak_mv": [6000, 25000],
                "rise_time_code": [1000, 5001],
                "decay_time_us": [500, 5001],
                "optical_sensor_mv": [50, 400],
            },
        },
    }

    with open(args.metadata_out, "w") as f:
        json.dump(split_metadata, f, indent=2)
    logger.info("Saved split metadata to %s", args.metadata_out)


if __name__ == "__main__":
    main()
