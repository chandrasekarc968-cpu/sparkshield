"""Automated tests for calibration generation, static INT8 quantization, and validation."""

import json
import os
import tempfile
import numpy as np
import onnxruntime as ort
import pytest

from models.calibration.generate_calibration import generate_calibration_data
from models.export_onnx import export_and_validate_onnx
from models.quantize import (
    SparkShieldCalibrationDataReader,
    evaluate_onnx_model_on_test,
    extract_quant_parameters,
    quantize_model,
)


@pytest.fixture(scope="module")
def base_onnx_model():
    """Generates a valid static ONNX model for quantization tests."""
    with tempfile.NamedTemporaryFile(suffix=".onnx", delete=False) as tmp_onnx:
        onnx_path = tmp_onnx.name

    checkpoint_path = "artifacts/sparkshield.pt"
    if not os.path.exists(checkpoint_path):
        pytest.skip(f"Base checkpoint not found at {checkpoint_path}")

    export_and_validate_onnx(checkpoint_path, onnx_path)
    yield onnx_path

    if os.path.exists(onnx_path):
        os.remove(onnx_path)
    meta_path = os.path.splitext(onnx_path)[0] + "_metadata.json"
    if os.path.exists(meta_path):
        os.remove(meta_path)


def test_calibration_manifest_validity():
    """Verify calibration dataset generator produces valid manifest with shapes, paths, labels, seed."""
    with tempfile.TemporaryDirectory() as tmp_dir:
        manifest_path = os.path.join(tmp_dir, "manifest.json")

        tensors, manifest = generate_calibration_data(
            output_dir=tmp_dir,
            manifest_path=manifest_path,
            samples_per_class=25,
            seed=444,
        )

        assert os.path.exists(manifest_path)
        assert tensors.shape == (100, 1, 128)
        assert tensors.dtype == np.float32

        with open(manifest_path, "r") as f:
            data = json.load(f)

        assert data["seed"] == 444
        assert data["total_samples"] == 100
        assert data["samples_per_class"] == 25
        assert data["sample_tensor_shape"] == [1, 1, 128]
        assert data["full_dataset_shape"] == [100, 1, 128]
        assert data["dtype"] == "float32"
        assert "data_file_path" in data
        assert data["class_labels"] == {"0": "NORMAL", "1": "EMP", "2": "OPTICAL", "3": "SURGE"}
        assert data["class_distribution"] == {"NORMAL": 25, "EMP": 25, "OPTICAL": 25, "SURGE": 25}
        assert "statistics" in data


def test_quantized_model_existence_and_inference(base_onnx_model):
    """Static INT8 quantization must generate valid model file and run inference returning [1, 4]."""
    with tempfile.TemporaryDirectory() as tmp_dir:
        quant_path = os.path.join(tmp_dir, "quant_model.onnx")
        calib_manifest = os.path.join(tmp_dir, "manifest.json")

        generate_calibration_data(
            output_dir=tmp_dir,
            manifest_path=calib_manifest,
            samples_per_class=10,
            seed=101,
        )

        quant_result = quantize_model(
            onnx_path=base_onnx_model,
            calibration_manifest_path=calib_manifest,
            output_path=quant_path,
        )

        # 1. Quantized model existence
        assert os.path.exists(quant_path)
        assert os.path.getsize(quant_path) > 0

        # 2. Quantized model inference
        session = ort.InferenceSession(quant_path, providers=["CPUExecutionProvider"])
        input_name = session.get_inputs()[0].name

        rng = np.random.default_rng(202)
        for _ in range(10):
            dummy_in = rng.uniform(0.0, 1.0, size=(1, 1, 128)).astype(np.float32)
            out = session.run(None, {input_name: dummy_in})[0]

            assert out.shape == (1, 4)
            # 3. Quantized logits or probabilities remaining finite
            assert not np.isnan(out).any(), "Quantized output contains NaN"
            assert not np.isinf(out).any(), "Quantized output contains Inf"

        # 4. Verify quantization configuration report
        config_path = os.path.splitext(quant_path)[0] + "_config.json"
        assert os.path.exists(config_path)
        with open(config_path, "r") as f:
            cfg = json.load(f)
        assert "quantization_parameters" in cfg
        assert "test_comparison" in cfg
        assert "dequantization_rule" in cfg


def test_no_fake_quantization_output_on_missing_model():
    """quantize_model must fail strictly with FileNotFoundError if input ONNX is missing."""
    with tempfile.TemporaryDirectory() as tmp_dir:
        dummy_manifest = os.path.join(tmp_dir, "manifest.json")
        with open(dummy_manifest, "w") as f:
            json.dump({"dummy": True}, f)

        with pytest.raises(FileNotFoundError):
            quantize_model(
                onnx_path="non_existent_model_12345.onnx",
                calibration_manifest_path=dummy_manifest,
                output_path=os.path.join(tmp_dir, "out.onnx"),
            )
