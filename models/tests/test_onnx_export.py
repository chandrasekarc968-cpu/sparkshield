"""Automated tests for ONNX model export and numerical parity."""

import os
import tempfile
import numpy as np
import onnx
import onnxruntime as ort
import pytest
import torch

from models.export_onnx import export_and_validate_onnx
from models.train import SparkShield1DCNN


@pytest.fixture(scope="module")
def sample_checkpoint():
    """Generates a temporary trained checkpoint for testing ONNX export."""
    with tempfile.NamedTemporaryFile(suffix=".pt", delete=False) as tmp:
        ckpt_path = tmp.name

    model = SparkShield1DCNN(num_classes=4)
    payload = {
        "model_state_dict": model.state_dict(),
        "class_names": ["NORMAL", "EMP", "OPTICAL", "SURGE"],
        "input_shape": [1, 1, 128],
    }
    torch.save(payload, ckpt_path)
    yield ckpt_path

    if os.path.exists(ckpt_path):
        os.remove(ckpt_path)


def test_onnx_export_static_shape_and_validation(sample_checkpoint):
    """Exported ONNX model must pass checker and have static [1, 1, 128] input and [1, 4] output."""
    with tempfile.NamedTemporaryFile(suffix=".onnx", delete=False) as tmp:
        onnx_path = tmp.name

    try:
        is_valid, max_diff = export_and_validate_onnx(
            checkpoint_path=sample_checkpoint,
            onnx_output_path=onnx_path,
            opset_version=17,
            atol=1e-5,
        )

        assert is_valid is True
        assert max_diff <= 1e-5

        # Inspect ONNX model
        model = onnx.load(onnx_path)
        onnx.checker.check_model(model)

        input_tensor = model.graph.input[0]
        input_dims = [d.dim_value for d in input_tensor.type.tensor_type.shape.dim]
        assert input_dims == [1, 1, 128]

        output_tensor = model.graph.output[0]
        output_dims = [d.dim_value for d in output_tensor.type.tensor_type.shape.dim]
        assert output_dims == [1, 4]

    finally:
        if os.path.exists(onnx_path):
            os.remove(onnx_path)


def test_onnx_runtime_inference_numerical_parity(sample_checkpoint):
    """ONNX Runtime predictions must match PyTorch tensor output within 1e-5."""
    with tempfile.NamedTemporaryFile(suffix=".onnx", delete=False) as tmp:
        onnx_path = tmp.name

    try:
        export_and_validate_onnx(sample_checkpoint, onnx_path)

        ckpt = torch.load(sample_checkpoint, map_location="cpu", weights_only=False)
        pt_model = SparkShield1DCNN(num_classes=4)
        pt_model.load_state_dict(ckpt["model_state_dict"])
        pt_model.eval()

        ort_session = ort.InferenceSession(onnx_path, providers=["CPUExecutionProvider"])
        input_name = ort_session.get_inputs()[0].name

        rng = np.random.default_rng(789)
        for _ in range(10):
            test_x = rng.uniform(0.0, 1.0, size=(1, 1, 128)).astype(np.float32)

            with torch.no_grad():
                pt_out = pt_model(torch.from_numpy(test_x)).numpy()

            ort_out = ort_session.run(None, {input_name: test_x})[0]

            assert np.allclose(pt_out, ort_out, atol=1e-5)
            assert pt_out.shape == (1, 4)
            assert ort_out.shape == (1, 4)

    finally:
        if os.path.exists(onnx_path):
            os.remove(onnx_path)
