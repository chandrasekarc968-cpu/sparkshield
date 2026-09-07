"""Automated tests for ONNX model export, static dimensions, and numerical parity."""

import json
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
def valid_checkpoint():
    """Generates a temporary trained checkpoint for testing ONNX export."""
    with tempfile.NamedTemporaryFile(suffix=".pt", delete=False) as tmp:
        ckpt_path = tmp.name

    model = SparkShield1DCNN(num_classes=4)
    payload = {
        "model_state_dict": model.state_dict(),
        "class_names": ["NORMAL", "EMP", "OPTICAL", "SURGE"],
        "input_shape": [1, 1, 128],
        "seed": 42,
    }
    torch.save(payload, ckpt_path)
    yield ckpt_path

    if os.path.exists(ckpt_path):
        os.remove(ckpt_path)


def test_onnx_export_fails_on_missing_checkpoint():
    """export_and_validate_onnx must fail strictly if checkpoint does not exist."""
    with pytest.raises(FileNotFoundError):
        export_and_validate_onnx("non_existent_checkpoint_xyz.pt", "dummy.onnx")


def test_onnx_export_static_input_and_output_shapes(valid_checkpoint):
    """Exported ONNX model must have input shape strictly [1, 1, 128] and output shape [1, 4]."""
    with tempfile.NamedTemporaryFile(suffix=".onnx", delete=False) as tmp:
        onnx_path = tmp.name

    try:
        is_valid, max_err, mean_err = export_and_validate_onnx(
            checkpoint_path=valid_checkpoint,
            onnx_output_path=onnx_path,
            opset_version=17,
            tolerance=1e-5,
        )

        assert is_valid is True
        assert os.path.exists(onnx_path)
        assert os.path.getsize(onnx_path) > 0

        # Verify ONNX graph structure
        model = onnx.load(onnx_path)
        onnx.checker.check_model(model)

        # Verify exact static input shape [1, 1, 128]
        graph_input = model.graph.input[0]
        assert graph_input.name == "input"
        input_dims = [d.dim_value for d in graph_input.type.tensor_type.shape.dim]
        assert input_dims == [1, 1, 128], f"Expected static shape [1, 1, 128], got {input_dims}"

        # Verify exact static output shape [1, 4]
        graph_output = model.graph.output[0]
        assert graph_output.name == "logits"
        output_dims = [d.dim_value for d in graph_output.type.tensor_type.shape.dim]
        assert output_dims == [1, 4], f"Expected static shape [1, 4], got {output_dims}"

        # Verify metadata JSON was created beside the model
        meta_path = os.path.splitext(onnx_path)[0] + "_metadata.json"
        assert os.path.exists(meta_path)
        with open(meta_path, "r") as f:
            meta = json.load(f)
        assert meta["input_shape"] == [1, 1, 128]
        assert meta["output_shape"] == [1, 4]
        assert meta["parity_check"]["status"] == "PASSED"
        if os.path.exists(meta_path):
            os.remove(meta_path)
    finally:
        if os.path.exists(onnx_path):
            os.remove(onnx_path)


def test_pytorch_versus_onnx_output_parity(valid_checkpoint):
    """Verify PyTorch and ONNX Runtime produce identical outputs within tolerance (1e-5)."""
    with tempfile.NamedTemporaryFile(suffix=".onnx", delete=False) as tmp:
        onnx_path = tmp.name

    try:
        _, max_err, mean_err = export_and_validate_onnx(
            checkpoint_path=valid_checkpoint,
            onnx_output_path=onnx_path,
            tolerance=1e-5,
        )

        assert max_err <= 1e-5, f"Max error {max_err} exceeds 1e-5"
        assert mean_err <= max_err

        # Load PyTorch model and ONNX Runtime session
        ckpt = torch.load(valid_checkpoint, map_location="cpu", weights_only=False)
        pt_model = SparkShield1DCNN(num_classes=4)
        pt_model.load_state_dict(ckpt["model_state_dict"])
        pt_model.eval()

        ort_session = ort.InferenceSession(onnx_path, providers=["CPUExecutionProvider"])
        input_name = ort_session.get_inputs()[0].name

        rng = np.random.default_rng(999)
        for _ in range(15):
            test_x = rng.uniform(0.0, 1.0, size=(1, 1, 128)).astype(np.float32)

            with torch.no_grad():
                pt_logits = pt_model(torch.from_numpy(test_x)).numpy()

            ort_logits = ort_session.run(None, {input_name: test_x})[0]

            assert np.allclose(pt_logits, ort_logits, atol=1e-5)
            assert not np.isnan(ort_logits).any()
            assert not np.isinf(ort_logits).any()

        meta_path = os.path.splitext(onnx_path)[0] + "_metadata.json"
        if os.path.exists(meta_path):
            os.remove(meta_path)
    finally:
        if os.path.exists(onnx_path):
            os.remove(onnx_path)
