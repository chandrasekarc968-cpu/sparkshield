"""Automated tests for FeatureExtractor and sliding window."""

import numpy as np
import pytest
from python_core.signal_models import FeatureExtractor, SignalClass, SignalGenerator


def test_feature_extractor_tensor_shape():
    """FeatureExtractor must strictly produce tensor of shape (1, 1, 128)."""
    extractor = FeatureExtractor()
    gen = SignalGenerator(seed=777)

    for _ in range(20):
        frame, _ = gen.generate(SignalClass.NORMAL)
        tensor = extractor.update(frame)

        assert tensor.shape == (1, 1, 128)
        assert tensor.dtype == np.float32


def test_feature_extractor_normalization_bounds():
    """All extracted features must fall within [0.0, 1.0]."""
    extractor = FeatureExtractor()
    gen = SignalGenerator(seed=888)

    for sc in SignalClass:
        for _ in range(5):
            frame, _ = gen.generate(sc)
            tensor = extractor.update(frame)

            assert not np.isnan(tensor).any()
            assert not np.isinf(tensor).any()
            assert (tensor >= 0.0).all(), f"Negative feature in {sc}: {tensor.min()}"
            assert (tensor <= 1.0).all(), f"Feature > 1.0 in {sc}: {tensor.max()}"


def test_feature_extractor_sliding_window_fifo():
    """Verify that sliding window shifts oldest frames out as new frames arrive."""
    extractor = FeatureExtractor()
    gen = SignalGenerator(seed=999)

    # Generate 8 distinct frames and record their 16-element feature vectors
    vectors = []
    for i in range(8):
        frame, _ = gen.generate(SignalClass.NORMAL, seq=i)
        vec = extractor.extract_frame_features(frame)
        vectors.append(vec)
        tensor = extractor.update(frame)

    # The full window of 128 should equal the 8 vectors concatenated
    expected_window = np.concatenate(vectors, axis=0).reshape(1, 1, 128)
    assert np.allclose(tensor, expected_window, atol=1e-6)

    # Now push an EMP frame (9th frame)
    emp_frame, _ = gen.generate(SignalClass.EMP, seq=8)
    emp_vec = extractor.extract_frame_features(emp_frame)
    new_tensor = extractor.update(emp_frame)

    # Oldest vector (vectors[0]) should be dropped, new vector at end
    expected_new_window = np.concatenate(vectors[1:] + [emp_vec], axis=0).reshape(1, 1, 128)
    assert np.allclose(new_tensor, expected_new_window, atol=1e-6)


def test_feature_extractor_reset():
    """Reset should restore baseline state."""
    extractor = FeatureExtractor()
    gen = SignalGenerator(seed=123)

    # Feed EMP frames
    for _ in range(10):
        frame, _ = gen.generate(SignalClass.EMP)
        extractor.update(frame)

    extractor.reset()
    # Baseline tensor should be populated
    baseline_frame, _ = gen.generate(SignalClass.NORMAL, seq=0)
    tensor = extractor.update(baseline_frame)
    assert tensor.shape == (1, 1, 128)
