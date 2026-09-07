"""Automated tests for static INT8 quantization and calibration."""

import json
import os
import tempfile
import numpy as np
import onnx
import onnxruntime as ort
import pytest

from models.calibration.generate_calibration import generate_calibration_dataset
from models.export_onnx import export_and_validate_onnx
from models.quantize import (
    SparkShieldCalibrationDataReader,
    extract_quantization_parameters,
    quantize_onnx_model,
)


@pytest.fixture(scope="module")
def base_onnx_model():
    """Generates a valid static ONNX model for quantization tests."""
    with tempfile.NamedTemporaryFile(suffix=".onnx", delete=False) as tmp_onnx:
        onnx_path = tmp_onnx.name

    checkpoint_path = "models/sparkshield_1d_cnn.pt"
    if not os.path.exists(checkpoint_path):
        pytest.skip(f"Base checkpoint not found at {checkpoint_path}")

    export_and_validate_onnx(checkpoint_path, onnx_path)
    yield onnx_path

    if os.path.exists(onnx_path):
        os.remove(onnx_path)


def test_calibration_dataset_generator():
    """Calibration dataset must contain 200 balanced samples of shape (200, 1, 128)."""
    with tempfile.NamedTemporaryFile(suffix=".npy", delete=False) as tmp_data, \
         tempfile.NamedTemporaryFile(suffix=".json", delete=False) as tmp_manifest:
        data_path = tmp_data.name
        manifest_path = tmp_manifest.name

    try:
        calib_data = generate_calibration_dataset(
            output_path=data_path,
            manifest_path=manifest_path,
            samples_per_class=50,
            seed=555,
        )

        assert calib_data.shape == (200, 1, 128)
        assert calib_data.dtype == np.float32

        # Verify manifest
        with open(manifest_path, "r") as f:
            manifest = json.load(f)

        assert manifest["total_samples"] == 200
        assert manifest["samples_per_class"] == 50
        assert manifest["class_distribution"] == {"NORMAL": 50, "EMP": 50, "OPTICAL": 50, "SURGE": 50}
        assert manifest["input_tensor_shape"] == [1, 128]
    finally:
        if os.path.exists(data_path):
            os.remove(data_path)
        if os.path.exists(manifest_path):
            os.remove(manifest_path)


def test_calibration_data_reader():
    """CalibrationDataReader correctly yields dictionaries with (1, 1, 128) arrays."""
    with tempfile.NamedTemporaryFile(suffix=".npy", delete=False) as tmp:
        data_path = tmp.name

    try:
        test_data = np.random.uniform(0.0, 1.0, size=(10, 1, 128)).astype(np.float32)
        np.save(data_path, test_data)

        reader = SparkShieldCalibrationDataReader(data_path, input_name="input")
        count = 0
        while True:
            item = reader.get_next()
            if item is None:
                break
            assert "input" in item
            assert item["input"].shape == (1, 1, 128)
            count += 1
        assert count == 10
    finally:
        if os.path.exists(data_path):
            os.remove(data_path)


def test_int8_quantization_and_manifest_recording(base_onnx_model):
    """Static INT8 quantization produces runnable model and records scale/zero-point parameters."""
    with tempfile.NamedTemporaryFile(suffix=".onnx", delete=False) as tmp_quant, \
         tempfile.NamedTemporaryFile(suffix=".npy", delete=False) as tmp_calib, \
         tempfile.NamedTemporaryFile(suffix=".json", delete=False) as tmp_manifest:
        quant_path = tmp_quant.name
        calib_path = tmp_calib.name
        manifest_path = tmp_manifest.name

    try:
        # Generate 40 calibration samples
        generate_calibration_dataset(calib_path, manifest_path, samples_per_class=10, seed=123)

        manifest_result = quantize_onnx_model(
            input_model_path=base_onnx_model,
            output_model_path=quant_path,
            calibration_data_path=calib_path,
            manifest_path=manifest_path,
        )

        assert os.path.exists(quant_path)
        assert os.path.getsize(quant_path) > 0

        # Run quantized model with ONNX Runtime
        session = ort.InferenceSession(quant_path, providers=["CPUExecutionProvider"])
        test_in = np.random.uniform(0.0, 1.0, size=(1, 1, 128)).astype(np.float32)
        out = session.run(None, {"input": test_in})[0]

        assert out.shape == (1, 4)
        assert not np.isnan(out).any()

        # Check recorded scale & zero-point parameters
        assert "quantization_parameters" in manifest_result
        quant_params = manifest_result["quantization_parameters"]
        assert quant_params["summary"]["quantized_initializers"] > 0
        assert "edge_deployment_dequantization_rule" in manifest_result
        assert "scale" in manifest_result["edge_deployment_dequantization_rule"].lower()

    finally:
        for p in [quant_path, calib_path, manifest_path]:
            if os.path.exists(p):
                os.remove(p)
