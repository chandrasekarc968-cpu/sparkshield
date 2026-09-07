"""Automated tests for ML training pipeline and dataset generator."""

import os
import tempfile
import numpy as np
import pytest
import torch

from models.train import (
    CLASS_NAMES,
    FEATURE_COUNT_PER_FRAME,
    INPUT_SHAPE,
    WINDOW_FRAME_COUNT,
    SparkShield1DCNN,
    generate_split,
    set_seed,
    train_model,
)
from torch.utils.data import DataLoader, TensorDataset


def test_dataset_generation_shapes_and_types():
    """generate_split must return strictly (N, 1, 128) float32 and (N,) int64."""
    samples_per_class = 25
    total_expected = samples_per_class * 4  # 100 samples

    X, y = generate_split("test_split", samples_per_class=samples_per_class, seed=123)

    assert X.shape == (total_expected, 1, 128)
    assert X.dtype == np.float32
    assert y.shape == (total_expected,)
    assert y.dtype == np.int64

    # Class balance check
    for c_idx in range(4):
        assert np.sum(y == c_idx) == samples_per_class

    # Bounds check
    assert not np.isnan(X).any()
    assert not np.isinf(X).any()
    assert (X >= 0.0).all()
    assert (X <= 1.0).all()


def test_leak_free_split_isolation():
    """Train, Val, and Test splits generated with distinct seeds must not share identical samples."""
    x_train, y_train = generate_split("train", samples_per_class=20, seed=100)
    x_val, y_val = generate_split("val", samples_per_class=20, seed=200)
    x_test, y_test = generate_split("test", samples_per_class=20, seed=300, held_out_ranges=True)

    # Flatten samples to 128-float vectors for equality checking
    train_flat = x_train.reshape(len(x_train), 128)
    val_flat = x_val.reshape(len(x_val), 128)
    test_flat = x_test.reshape(len(x_test), 128)

    # Check that no sample in val exactly matches any sample in train
    for v_sample in val_flat:
        matches = np.isclose(train_flat, v_sample, atol=1e-7).all(axis=1)
        assert not matches.any(), "Data leakage: identical sample found in train and val splits!"

    # Check that no sample in test matches any in train or val
    for t_sample in test_flat:
        matches_train = np.isclose(train_flat, t_sample, atol=1e-7).all(axis=1)
        matches_val = np.isclose(val_flat, t_sample, atol=1e-7).all(axis=1)
        assert not matches_train.any(), "Data leakage: identical sample in test and train!"
        assert not matches_val.any(), "Data leakage: identical sample in test and val!"


def test_model_forward_pass():
    """SparkShield1DCNN forward pass on (Batch, 1, 128) produces (Batch, 4)."""
    model = SparkShield1DCNN(num_classes=4)
    model.eval()

    batch_sizes = [1, 4, 16, 32]
    for b in batch_sizes:
        dummy_input = torch.randn(b, 1, 128, dtype=torch.float32)
        with torch.no_grad():
            output = model(dummy_input)

        assert output.shape == (b, 4)
        assert output.dtype == torch.float32
        assert not torch.isnan(output).any()


def test_mini_training_and_checkpoint_payload():
    """Verify training loop reduces loss, computes Macro F1, and saves valid checkpoint payload."""
    set_seed(42)
    x_train, y_train = generate_split("train", samples_per_class=40, seed=11)
    x_val, y_val = generate_split("val", samples_per_class=15, seed=22)

    train_loader = DataLoader(
        TensorDataset(torch.from_numpy(x_train), torch.from_numpy(y_train)),
        batch_size=16,
        shuffle=True,
    )
    val_loader = DataLoader(
        TensorDataset(torch.from_numpy(x_val), torch.from_numpy(y_val)),
        batch_size=16,
        shuffle=False,
    )

    model = SparkShield1DCNN(num_classes=4)
    trained_model, history, best_f1, best_weights = train_model(
        model=model,
        train_loader=train_loader,
        val_loader=val_loader,
        epochs=3,
        lr=0.002,
    )

    assert len(history["train_loss"]) == 3
    assert history["train_loss"][-1] < history["train_loss"][0]  # Loss decreased
    assert best_f1 > 0.8  # Strong classification performance
    assert best_weights is not None

    with tempfile.NamedTemporaryFile(suffix=".pt", delete=False) as tmp:
        tmp_path = tmp.name

    try:
        payload = {
            "model_state_dict": best_weights,
            "class_names": CLASS_NAMES,
            "input_shape": INPUT_SHAPE,
            "feature_count_per_frame": FEATURE_COUNT_PER_FRAME,
            "window_frame_count": WINDOW_FRAME_COUNT,
            "best_val_macro_f1": best_f1,
        }
        torch.save(payload, tmp_path)

        loaded = torch.load(tmp_path, map_location="cpu", weights_only=False)
        assert "model_state_dict" in loaded
        assert loaded["class_names"] == ["NORMAL", "EMP", "OPTICAL", "SURGE"]
        assert loaded["input_shape"] == [1, 1, 128]
        assert loaded["best_val_macro_f1"] == best_f1
    finally:
        if os.path.exists(tmp_path):
            os.remove(tmp_path)
