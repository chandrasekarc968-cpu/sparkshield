"""Automated tests for ML training pipeline, data generation, and checkpointing."""

import os
import tempfile
import numpy as np
import pytest
import torch
from torch.utils.data import DataLoader, TensorDataset

from models.train import (
    CLASS_NAMES,
    FEATURE_COUNT_PER_FRAME,
    INPUT_SHAPE,
    WINDOW_FRAME_COUNT,
    SparkShield1DCNN,
    compute_metrics_for_split,
    generate_split,
    set_seed,
    train_model,
)


def test_dataset_shapes_and_dtypes():
    """Verify dataset generation strictly produces (N, 1, 128) float32 and (N,) int64."""
    samples_per_class = 20
    total = samples_per_class * 4  # 80

    X, y = generate_split("test_split", samples_per_class=samples_per_class, seed=42)

    assert X.shape == (total, 1, 128), f"Expected shape ({total}, 1, 128), got {X.shape}"
    assert X.dtype == np.float32, f"Expected float32, got {X.dtype}"
    assert y.shape == (total,), f"Expected shape ({total},), got {y.shape}"
    assert y.dtype == np.int64, f"Expected int64, got {y.dtype}"
    assert not np.isnan(X).any(), "NaN found in generated features"
    assert not np.isinf(X).any(), "Inf found in generated features"
    assert (X >= 0.0).all() and (X <= 1.0).all(), "Features out of normalized bounds [0, 1]"


def test_all_four_classes_present_and_balanced():
    """Verify all four classes (0=NORMAL, 1=EMP, 2=OPTICAL, 3=SURGE) are present and balanced."""
    samples_per_class = 30
    X, y = generate_split("test_balance", samples_per_class=samples_per_class, seed=777)

    unique_classes = set(np.unique(y))
    assert unique_classes == {0, 1, 2, 3}, f"Expected classes {{0, 1, 2, 3}}, got {unique_classes}"

    for c in range(4):
        count = int(np.sum(y == c))
        assert count == samples_per_class, f"Class {c} has {count} samples, expected {samples_per_class}"


def test_deterministic_generation_with_same_seed():
    """Same seed must produce bit-for-bit identical dataset splits."""
    seed = 8888
    x1, y1 = generate_split("split1", samples_per_class=15, seed=seed)
    x2, y2 = generate_split("split2", samples_per_class=15, seed=seed)

    assert np.array_equal(x1, x2), "Feature arrays differ despite identical seed!"
    assert np.array_equal(y1, y2), "Label arrays differ despite identical seed!"


def test_different_splits_using_independent_seeds():
    """Train, Val, and Test splits with independent seeds must not have duplicate samples."""
    x_train, _ = generate_split("train", samples_per_class=20, seed=101)
    x_val, _ = generate_split("val", samples_per_class=20, seed=202)
    x_test, _ = generate_split("test", samples_per_class=20, seed=303, held_out_ranges=True)

    train_flat = x_train.reshape(len(x_train), 128)
    val_flat = x_val.reshape(len(x_val), 128)
    test_flat = x_test.reshape(len(x_test), 128)

    for v in val_flat:
        assert not np.isclose(train_flat, v, atol=1e-7).all(axis=1).any(), "Data leakage: sample in train & val"

    for t in test_flat:
        assert not np.isclose(train_flat, t, atol=1e-7).all(axis=1).any(), "Data leakage: sample in train & test"
        assert not np.isclose(val_flat, t, atol=1e-7).all(axis=1).any(), "Data leakage: sample in val & test"


def test_model_output_shape_exactly_batch_size_by_4():
    """Model forward pass must return shape strictly [batch_size, 4] for various batch sizes."""
    model = SparkShield1DCNN(num_classes=4)
    model.eval()

    for batch_size in [1, 2, 7, 16, 32]:
        dummy = torch.randn(batch_size, 1, 128, dtype=torch.float32)
        with torch.no_grad():
            out = model(dummy)
        assert out.shape == (batch_size, 4), f"Expected shape ({batch_size}, 4), got {out.shape}"
        assert not torch.isnan(out).any()


def test_checkpoint_creation_and_metadata():
    """Verify model training creates valid checkpoint with all required metadata fields."""
    set_seed(42)
    x_tr, y_tr = generate_split("tr", samples_per_class=30, seed=10)
    x_va, y_va = generate_split("va", samples_per_class=15, seed=20)

    tr_loader = DataLoader(TensorDataset(torch.from_numpy(x_tr), torch.from_numpy(y_tr)), batch_size=16, shuffle=True)
    va_loader = DataLoader(TensorDataset(torch.from_numpy(x_va), torch.from_numpy(y_va)), batch_size=16, shuffle=False)

    model = SparkShield1DCNN(num_classes=4)
    trained_model, history, best_f1, best_weights = train_model(
        model=model,
        train_loader=tr_loader,
        val_loader=va_loader,
        epochs=3,
        lr=0.002,
    )

    with tempfile.NamedTemporaryFile(suffix=".pt", delete=False) as tmp:
        ckpt_path = tmp.name

    try:
        payload = {
            "model_state_dict": best_weights,
            "class_names": CLASS_NAMES,
            "input_shape": INPUT_SHAPE,
            "feature_count_per_frame": FEATURE_COUNT_PER_FRAME,
            "window_frame_count": WINDOW_FRAME_COUNT,
            "seed": 42,
            "disclaimer": "Synthetic-data results only.",
            "normalization": {"feature_range": [0.0, 1.0]},
            "training_config": {"epochs": 3, "batch_size": 16, "lr": 0.002},
            "best_val_macro_f1": best_f1,
        }
        torch.save(payload, ckpt_path)

        assert os.path.exists(ckpt_path)
        assert os.path.getsize(ckpt_path) > 0

        # Load and verify metadata
        ckpt = torch.load(ckpt_path, map_location="cpu", weights_only=False)
        assert "model_state_dict" in ckpt
        assert ckpt["class_names"] == ["NORMAL", "EMP", "OPTICAL", "SURGE"]
        assert ckpt["input_shape"] == [1, 1, 128]
        assert ckpt["feature_count_per_frame"] == 16
        assert ckpt["window_frame_count"] == 8
        assert ckpt["seed"] == 42
        assert "normalization" in ckpt
        assert "training_config" in ckpt
        assert "best_val_macro_f1" in ckpt
        assert "disclaimer" in ckpt
    finally:
        if os.path.exists(ckpt_path):
            os.remove(ckpt_path)


def test_split_metrics_reporting():
    """Verify compute_metrics_for_split computes all required evaluation fields."""
    x_test, y_test = generate_split("eval_test", samples_per_class=10, seed=55)
    model = SparkShield1DCNN(num_classes=4)
    device = torch.device("cpu")

    metrics = compute_metrics_for_split(model, x_test, y_test, device)

    required_keys = [
        "accuracy",
        "macro_precision",
        "macro_recall",
        "macro_f1",
        "normal_false_positive_rate",
        "samples_per_class",
        "per_class_metrics",
        "confusion_matrix",
    ]
    for key in required_keys:
        assert key in metrics, f"Missing required metric key '{key}'"

    assert len(metrics["confusion_matrix"]) == 4
    for row in metrics["confusion_matrix"]:
        assert len(row) == 4

    for cname in CLASS_NAMES:
        assert cname in metrics["per_class_metrics"]
        pcm = metrics["per_class_metrics"][cname]
        assert "precision" in pcm and "recall" in pcm and "f1_score" in pcm and "support" in pcm
