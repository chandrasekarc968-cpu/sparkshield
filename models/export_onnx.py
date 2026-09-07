"""SparkShield Static ONNX Model Exporter and Numerical Parity Validator.

Exports PyTorch checkpoint to static-shape ONNX format (input: [1, 1, 128], output: [1, 4])
and verifies numerical parity between PyTorch and ONNX Runtime.
"""

import argparse
import logging
import os
import sys
from typing import Tuple

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8")

import numpy as np
import onnx
import onnxruntime as ort
import torch

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))
from models.train import SparkShield1DCNN

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(name)s: %(message)s")
logger = logging.getLogger("sparkshield.models.export_onnx")


def export_and_validate_onnx(
    checkpoint_path: str = "models/sparkshield_1d_cnn.pt",
    onnx_output_path: str = "models/sparkshield_1d_cnn.onnx",
    opset_version: int = 17,
    atol: float = 1e-5,
) -> Tuple[bool, float]:
    """Exports PyTorch model to static ONNX and validates output parity.

    Args:
        checkpoint_path: Path to PyTorch checkpoint.
        onnx_output_path: Path to output ONNX model.
        opset_version: Target ONNX opset version (default: 17).
        atol: Absolute tolerance threshold for numerical equivalence.

    Returns:
        Tuple of (is_valid, max_abs_diff).
    """
    if not os.path.exists(checkpoint_path):
        raise FileNotFoundError(f"Checkpoint not found: {checkpoint_path}")

    logger.info("Loading PyTorch checkpoint from %s...", checkpoint_path)
    checkpoint = torch.load(checkpoint_path, map_location="cpu", weights_only=False)

    model = SparkShield1DCNN(num_classes=4)
    model.load_state_dict(checkpoint["model_state_dict"])
    model.eval()

    # Create static dummy input tensor: exactly (1, 1, 128)
    dummy_input = torch.randn(1, 1, 128, dtype=torch.float32)

    os.makedirs(os.path.dirname(onnx_output_path) or ".", exist_ok=True)
    logger.info("Exporting to static ONNX model (Opset %d) at %s...", opset_version, onnx_output_path)

    # Use dynamo=False for deterministic static tracing across edge engines
    torch.onnx.export(
        model,
        dummy_input,
        onnx_output_path,
        export_params=True,
        opset_version=opset_version,
        do_constant_folding=True,
        input_names=["input"],
        output_names=["logits"],
        dynamo=False,
    )

    # 1. Verify ONNX model structure
    onnx_model = onnx.load(onnx_output_path)
    onnx.checker.check_model(onnx_model)
    logger.info("ONNX model structure check passed successfully.")

    # 2. Inspect static dimensions
    input_tensor = onnx_model.graph.input[0]
    input_shape = [dim.dim_value for dim in input_tensor.type.tensor_type.shape.dim]
    output_tensor = onnx_model.graph.output[0]
    output_shape = [dim.dim_value for dim in output_tensor.type.tensor_type.shape.dim]

    logger.info("ONNX Graph Input: '%s' with shape %s", input_tensor.name, input_shape)
    logger.info("ONNX Graph Output: '%s' with shape %s", output_tensor.name, output_shape)

    assert input_shape == [1, 1, 128], f"Expected input shape [1, 1, 128], got {input_shape}"
    assert output_shape == [1, 4], f"Expected output shape [1, 4], got {output_shape}"

    # 3. Validate numerical parity using ONNX Runtime
    session = ort.InferenceSession(onnx_output_path, providers=["CPUExecutionProvider"])
    ort_input_name = session.get_inputs()[0].name

    max_diff = 0.0
    num_test_trials = 25

    # Run deterministic test tensors across varying inputs
    rng = np.random.default_rng(42)
    for trial in range(num_test_trials):
        test_np = rng.uniform(0.0, 1.0, size=(1, 1, 128)).astype(np.float32)
        test_tensor = torch.from_numpy(test_np)

        with torch.no_grad():
            torch_logits = model(test_tensor).numpy()

        ort_logits = session.run(None, {ort_input_name: test_np})[0]

        diff = float(np.max(np.abs(torch_logits - ort_logits)))
        if diff > max_diff:
            max_diff = diff

    logger.info("Numerical Parity Validation: Max Absolute Diff = %.8e (Threshold: %.1e)", max_diff, atol)
    is_valid = max_diff <= atol

    if is_valid:
        logger.info(">>> PyTorch vs ONNX Runtime Numerical Parity PASSED! <<<")
    else:
        logger.error(">>> Parity check FAILED: max diff %.8e exceeds threshold %.1e", max_diff, atol)

    return is_valid, max_diff


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Export SparkShield PyTorch checkpoint to ONNX")
    parser.add_argument("--checkpoint", type=str, default="models/sparkshield_1d_cnn.pt")
    parser.add_argument("--output", type=str, default="models/sparkshield_1d_cnn.onnx")
    parser.add_argument("--opset", type=int, default=17)
    args = parser.parse_args()

    success, diff = export_and_validate_onnx(args.checkpoint, args.output, opset_version=args.opset)
    if not success:
        sys.exit(1)
