"""SparkShield 1D CNN Classifier Training & Comprehensive Evaluation Pipeline.

Generates leak-free balanced synthetic telemetry datasets across NORMAL, EMP,
OPTICAL, and SURGE classes, trains the SparkShield1DCNN architecture, selects the
best checkpoint by validation Macro F1, evaluates on both validation and test sets,
and saves model checkpoint and metrics JSON.

Disclaimer:
  All results are based on synthetic software telemetry simulations only.
  No production or real-world hardware accuracy is claimed.
"""

import argparse
import json
import logging
import os
import random
import sys
from typing import Dict, List, Optional, Tuple

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8")

import numpy as np
import torch
import torch.nn as nn
import torch.optim as optim
from sklearn.metrics import confusion_matrix, precision_recall_fscore_support, f1_score
from torch.utils.data import DataLoader, TensorDataset

# Ensure root directory is in sys.path
_ROOT_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
if _ROOT_DIR not in sys.path:
    sys.path.insert(0, _ROOT_DIR)

from python_core.signal_models import FeatureExtractor, SignalClass, SignalGenerator

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(name)s: %(message)s")
logger = logging.getLogger("sparkshield.models.train")

CLASS_NAMES = ["NORMAL", "EMP", "OPTICAL", "SURGE"]
INPUT_SHAPE = [1, 1, 128]
FEATURE_COUNT_PER_FRAME = 16
WINDOW_FRAME_COUNT = 8
SYNTHETIC_DATA_DISCLAIMER = (
    "All results are based on deterministic synthetic telemetry simulations only. "
    "These metrics reflect synthetic-data performance and do not claim production or "
    "field accuracy on physical hardware."
)

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
        for _ in range(samples_per_class):
            sample_seed = int(rng.integers(1, 2_000_000_000))
            gen = SignalGenerator(seed=sample_seed)
            extractor = FeatureExtractor()

            # Apply held-out variation for test generalization
            if held_out_ranges:
                gen.grid_freq_hz = float(rng.uniform(48.5, 51.5))

            # Simulate a multi-frame arrival sequence to fill sliding history naturally
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
        "Split '%s' generated: X shape %s (dtype %s), y shape %s (dtype %s)",
        split_name,
        x_shuffled.shape,
        x_shuffled.dtype,
        y_shuffled.shape,
        y_shuffled.dtype,
    )
    return x_shuffled, y_shuffled


def compute_metrics_for_split(
    model: nn.Module,
    x_data: np.ndarray,
    y_data: np.ndarray,
    device: torch.device,
) -> Dict:
    """Computes full classification metrics for a dataset split.

    Metrics:
      - accuracy
      - macro precision
      - macro recall
      - macro F1
      - per-class precision, recall, and F1
      - confusion matrix
      - NORMAL false-positive rate
      - number of samples per class
    """
    model.eval()
    with torch.no_grad():
        x_tensor = torch.from_numpy(x_data).to(device)
        logits = model(x_tensor)
        preds = torch.argmax(logits, dim=1).cpu().numpy()

    # Confusion matrix (Rows: True, Columns: Predicted)
    cm = confusion_matrix(y_data, preds, labels=[0, 1, 2, 3])

    # Per-class metrics
    precision, recall, f1, support = precision_recall_fscore_support(
        y_data, preds, labels=[0, 1, 2, 3], zero_division=0
    )

    # Macro averages
    macro_precision = float(np.mean(precision))
    macro_recall = float(np.mean(recall))
    macro_f1 = float(np.mean(f1))
    accuracy = float(np.mean(preds == y_data))

    # False-Positive Rate on NORMAL (Class 0):
    # Any non-NORMAL true event (EMP, OPTICAL, SURGE) classified as NORMAL
    non_normal_mask = (y_data != 0)
    total_non_normal = int(np.sum(non_normal_mask))
    fp_normal = int(np.sum((preds == 0) & non_normal_mask))
    fpr_normal = float(fp_normal / total_non_normal) if total_non_normal > 0 else 0.0

    per_class_dict = {}
    samples_per_class_dict = {}
    for idx, cname in enumerate(CLASS_NAMES):
        per_class_dict[cname] = {
            "precision": float(precision[idx]),
            "recall": float(recall[idx]),
            "f1_score": float(f1[idx]),
            "support": int(support[idx]),
        }
        samples_per_class_dict[cname] = int(np.sum(y_data == idx))

    return {
        "accuracy": accuracy,
        "macro_precision": macro_precision,
        "macro_recall": macro_recall,
        "macro_f1": macro_f1,
        "normal_false_positive_rate": fpr_normal,
        "normal_false_positive_count": fp_normal,
        "total_non_normal_samples": total_non_normal,
        "samples_per_class": samples_per_class_dict,
        "total_samples": int(len(y_data)),
        "per_class_metrics": per_class_dict,
        "confusion_matrix": cm.tolist(),
    }


def train_model(
    model: nn.Module,
    train_loader: DataLoader,
    val_loader: DataLoader,
    epochs: int = 15,
    lr: float = 0.001,
    device: Optional[torch.device] = None,
) -> Tuple[nn.Module, Dict[str, List[float]], float, Dict]:
    """Trains SparkShield1DCNN with validation Macro F1 checkpoint selection."""
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

    logger.info("Training SparkShield1DCNN for %d epochs on device '%s'...", epochs, device)

    for epoch in range(1, epochs + 1):
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

        # Validation
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
            logger.info(">>> New best model checkpoint found at epoch %d (Val Macro F1: %.4f)", epoch, best_macro_f1)

    if best_weights is not None:
        model.load_state_dict(best_weights)

    return model, history, best_macro_f1, best_weights


def main():
    parser = argparse.ArgumentParser(description="SparkShield 1D CNN Model Training")
    parser.add_argument("--epochs", type=int, default=20, help="Number of training epochs")
    parser.add_argument("--samples-per-class", type=int, default=1000, help="Train samples per class")
    parser.add_argument("--val-samples-per-class", type=int, default=200, help="Val samples per class")
    parser.add_argument("--test-samples-per-class", type=int, default=200, help="Test samples per class")
    parser.add_argument("--batch-size", type=int, default=32, help="Batch size")
    parser.add_argument("--lr", type=float, default=0.001, help="Learning rate")
    parser.add_argument("--seed", type=int, default=42, help="Master random seed")
    parser.add_argument("--output", type=str, default="artifacts/sparkshield.pt", help="Checkpoint output path")
    parser.add_argument("--metrics", type=str, default="artifacts/metrics.json", help="Metrics JSON output path")
    args = parser.parse_args()

    set_seed(args.seed)

    # Independent seeds for splits
    train_seed = args.seed + 101
    val_seed = args.seed + 202
    test_seed = args.seed + 303

    # Generate datasets
    x_train, y_train = generate_split("train", args.samples_per_class, train_seed, held_out_ranges=False)
    x_val, y_val = generate_split("val", args.val_samples_per_class, val_seed, held_out_ranges=False)
    x_test, y_test = generate_split("test", args.test_samples_per_class, test_seed, held_out_ranges=True)

    # Save test dataset alongside output for downstream export/quantize parity evaluation
    output_dir = os.path.dirname(args.output) or "."
    os.makedirs(output_dir, exist_ok=True)
    metrics_dir = os.path.dirname(args.metrics) or "."
    os.makedirs(metrics_dir, exist_ok=True)

    test_data_path = os.path.join(output_dir, "test_data.npz")
    np.savez_compressed(test_data_path, x_test=x_test, y_test=y_test)
    logger.info("Saved test dataset to %s", test_data_path)

    # PyTorch DataLoaders
    train_dataset = TensorDataset(torch.from_numpy(x_train), torch.from_numpy(y_train))
    val_dataset = TensorDataset(torch.from_numpy(x_val), torch.from_numpy(y_val))

    train_loader = DataLoader(train_dataset, batch_size=args.batch_size, shuffle=True)
    val_loader = DataLoader(val_dataset, batch_size=args.batch_size, shuffle=False)

    # Initialize model
    model = SparkShield1DCNN(num_classes=4)
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")

    # Train
    trained_model, history, best_val_f1, best_weights = train_model(
        model=model,
        train_loader=train_loader,
        val_loader=val_loader,
        epochs=args.epochs,
        lr=args.lr,
        device=device,
    )

    # Compute comprehensive evaluation metrics on validation and test splits
    val_metrics = compute_metrics_for_split(trained_model, x_val, y_val, device)
    test_metrics = compute_metrics_for_split(trained_model, x_test, y_test, device)

    logger.info("================ VALIDATION METRICS (SYNTHETIC) ================")
    logger.info("Accuracy: %.4f | Macro F1: %.4f | NORMAL FPR: %.6f",
                val_metrics["accuracy"], val_metrics["macro_f1"], val_metrics["normal_false_positive_rate"])
    logger.info("================ TEST METRICS (SYNTHETIC) ======================")
    logger.info("Accuracy: %.4f | Macro F1: %.4f | NORMAL FPR: %.6f",
                test_metrics["accuracy"], test_metrics["macro_f1"], test_metrics["normal_false_positive_rate"])

    # Build checkpoint payload
    checkpoint_payload = {
        "model_state_dict": best_weights,
        "class_names": CLASS_NAMES,
        "input_shape": INPUT_SHAPE,
        "feature_count_per_frame": FEATURE_COUNT_PER_FRAME,
        "window_frame_count": WINDOW_FRAME_COUNT,
        "seed": args.seed,
        "disclaimer": SYNTHETIC_DATA_DISCLAIMER,
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
            "samples_per_class": args.samples_per_class,
            "val_samples_per_class": args.val_samples_per_class,
            "test_samples_per_class": args.test_samples_per_class,
            "device": str(device),
        },
        "best_val_macro_f1": best_val_f1,
    }

    torch.save(checkpoint_payload, args.output)
    logger.info("Saved model checkpoint to %s", args.output)

    # Save comprehensive metrics JSON
    full_metrics_report = {
        "disclaimer": SYNTHETIC_DATA_DISCLAIMER,
        "environment": "Synthetic Software Simulation",
        "checkpoint_path": args.output,
        "model_architecture": "SparkShield1DCNN",
        "input_shape": INPUT_SHAPE,
        "class_names": CLASS_NAMES,
        "seed": args.seed,
        "validation_metrics": val_metrics,
        "test_metrics": test_metrics,
        "training_config": checkpoint_payload["training_config"],
        "normalization": checkpoint_payload["normalization"],
    }

    with open(args.metrics, "w") as f:
        json.dump(full_metrics_report, f, indent=2)
    logger.info("Saved complete metrics JSON to %s", args.metrics)


if __name__ == "__main__":
    main()
