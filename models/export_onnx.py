"""SparkShield Static ONNX Model Exporter and Numerical Parity Validator.

Exports a valid trained PyTorch checkpoint to a static-shape ONNX model:
  - Input: 'input' with static shape [1, 1, 128] (no dynamic axes)
  - Output: 'logits' with static shape [1, 4]
  - Supported ONNX opset (default: 17)
  - Runs parity validation between PyTorch and ONNX Runtime (Max Error & MAE)
  - Writes metadata JSON beside the exported ONNX model
  - Fails strictly if checkpoint is missing or numerical tolerance is exceeded.
"""

import argparse
import json
import logging
import os
import sys
from typing import Dict, Tuple

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8")

import numpy as np
import onnx
import onnxruntime as ort
import torch

_ROOT_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
if _ROOT_DIR not in sys.path:
    sys.path.insert(0, _ROOT_DIR)

from models.train import CLASS_NAMES, INPUT_SHAPE, SYNTHETIC_DATA_DISCLAIMER, SparkShield1DCNN

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(name)s: %(message)s")
logger = logging.getLogger("sparkshield.models.export_onnx")


def export_and_validate_onnx(
    checkpoint_path: str,
    onnx_output_path: str,
    opset_version: int = 17,
    tolerance: float = 1e-5,
) -> Tuple[bool, float, float]:
    """Exports a trained checkpoint to static ONNX and validates numerical parity.

    Args:
        checkpoint_path: Path to trained PyTorch checkpoint. Must exist and be valid.
        onnx_output_path: Destination path for exported ONNX model.
        opset_version: ONNX operator set version (default: 17).
        tolerance: Maximum acceptable absolute error between PyTorch and ONNX Runtime.

    Returns:
        Tuple of (is_valid, max_abs_error, mean_abs_error).

    Raises:
        FileNotFoundError: If checkpoint_path does not exist.
        ValueError: If checkpoint is corrupted or missing required keys.
    """
    if not os.path.exists(checkpoint_path):
        raise FileNotFoundError(
            f"Cannot export ONNX: checkpoint does not exist at '{checkpoint_path}'. "
            "Please train the model first with 'python -m models.train'."
        )

    logger.info("Loading PyTorch checkpoint from '%s'...", checkpoint_path)
    try:
        checkpoint = torch.load(checkpoint_path, map_location="cpu", weights_only=False)
    except Exception as exc:
        raise ValueError(f"Failed to safely load checkpoint from '{checkpoint_path}': {exc}") from exc

    if not isinstance(checkpoint, dict) or "model_state_dict" not in checkpoint:
        raise ValueError(
            f"Invalid checkpoint format in '{checkpoint_path}': expected dict containing 'model_state_dict'."
        )

    model = SparkShield1DCNN(num_classes=4)
    model.load_state_dict(checkpoint["model_state_dict"])
    model.eval()

    output_dir = os.path.dirname(onnx_output_path) or "."
    os.makedirs(output_dir, exist_ok=True)

    # Static input tensor: strictly [1, 1, 128]
    dummy_input = torch.randn(1, 1, 128, dtype=torch.float32)

    logger.info(
        "Exporting to static ONNX model (Opset %d, shape [1, 1, 128] -> [1, 4]) at '%s'...",
        opset_version,
        onnx_output_path,
    )

    # Export using static TorchScript tracing (no dynamic axes)
    torch.onnx.export(
        model,
        dummy_input,
        onnx_output_path,
        export_params=True,
        opset_version=opset_version,
        do_constant_folding=True,
        input_names=["input"],
        output_names=["logits"],
        dynamic_axes=None,  # Strictly static axes
        dynamo=False,
    )

    # Verify that the output file exists
    if not os.path.exists(onnx_output_path) or os.path.getsize(onnx_output_path) == 0:
        raise RuntimeError(f"ONNX export failed: output file '{onnx_output_path}' was not created.")

    # Validate ONNX structure
    onnx_model = onnx.load(onnx_output_path)
    onnx.checker.check_model(onnx_model)
    logger.info("ONNX model structure check passed.")

    # Validate input/output names and shapes
    graph_input = onnx_model.graph.input[0]
    graph_output = onnx_model.graph.output[0]

    assert graph_input.name == "input", f"Expected input name 'input', got '{graph_input.name}'"
    assert graph_output.name == "logits", f"Expected output name 'logits', got '{graph_output.name}'"

    input_dims = [dim.dim_value for dim in graph_input.type.tensor_type.shape.dim]
    output_dims = [dim.dim_value for dim in graph_output.type.tensor_type.shape.dim]

    logger.info("ONNX Graph Input: '%s' with static shape %s", graph_input.name, input_dims)
    logger.info("ONNX Graph Output: '%s' with static shape %s", graph_output.name, output_dims)

    if input_dims != [1, 1, 128]:
        raise ValueError(f"Expected static input shape [1, 1, 128], got {input_dims}")
    if output_dims != [1, 4]:
        raise ValueError(f"Expected static output shape [1, 4], got {output_dims}")

    # Parity check: compare PyTorch and ONNX Runtime on identical fixed inputs
    session = ort.InferenceSession(onnx_output_path, providers=["CPUExecutionProvider"])
    ort_in_name = session.get_inputs()[0].name

    rng = np.random.default_rng(12345)
    max_abs_error = 0.0
    total_abs_error = 0.0
    total_elements = 0
    num_test_trials = 50

    for _ in range(num_test_trials):
        test_np = rng.uniform(0.0, 1.0, size=(1, 1, 128)).astype(np.float32)
        test_pt = torch.from_numpy(test_np)

        with torch.no_grad():
            pt_logits = model(test_pt).numpy()

        ort_logits = session.run(None, {ort_in_name: test_np})[0]

        abs_diff = np.abs(pt_logits - ort_logits)
        trial_max = float(np.max(abs_diff))
        if trial_max > max_abs_error:
            max_abs_error = trial_max

        total_abs_error += float(np.sum(abs_diff))
        total_elements += pt_logits.size

    mean_abs_error = float(total_abs_error / total_elements)

    logger.info("Parity Validation Results (%d trials):", num_test_trials)
    logger.info("  Max Absolute Error:  %.8e (Tolerance: %.1e)", max_abs_error, tolerance)
    logger.info("  Mean Absolute Error: %.8e", mean_abs_error)

    if max_abs_error > tolerance:
        raise ValueError(
            f"Parity validation FAILED: Max Absolute Error ({max_abs_error:.8e}) "
            f"exceeds tolerance ({tolerance:.1e})."
        )

    logger.info(">>> PyTorch vs ONNX Runtime Numerical Parity PASSED! <<<")

    # Write metadata beside the model
    base_name = os.path.splitext(onnx_output_path)[0]
    metadata_path = f"{base_name}_metadata.json"

    model_metadata = {
        "model_name": "SparkShield1DCNN",
        "checkpoint_source": checkpoint_path,
        "onnx_model_path": onnx_output_path,
        "opset_version": opset_version,
        "input_name": "input",
        "input_shape": [1, 1, 128],
        "output_name": "logits",
        "output_shape": [1, 4],
        "class_names": CLASS_NAMES,
        "parity_check": {
            "status": "PASSED",
            "trials": num_test_trials,
            "tolerance": tolerance,
            "max_absolute_error": max_abs_error,
            "mean_absolute_error": mean_abs_error,
        },
        "disclaimer": SYNTHETIC_DATA_DISCLAIMER,
        "training_metadata": {
            "best_val_macro_f1": checkpoint.get("best_val_macro_f1"),
            "seed": checkpoint.get("seed"),
            "normalization": checkpoint.get("normalization"),
        },
    }

    with open(metadata_path, "w") as f:
        json.dump(model_metadata, f, indent=2)
    logger.info("Saved ONNX model metadata to '%s'", metadata_path)

    return True, max_abs_error, mean_abs_error


def main():
    parser = argparse.ArgumentParser(description="Export SparkShield PyTorch checkpoint to ONNX")
    parser.add_argument("--checkpoint", type=str, default="artifacts/sparkshield.pt", help="Path to checkpoint")
    parser.add_argument("--output", type=str, default="artifacts/sparkshield.onnx", help="Path to output ONNX model")
    parser.add_argument("--opset", type=int, default=17, help="ONNX opset version")
    parser.add_argument("--tolerance", type=float, default=1e-5, help="Parity error tolerance")
    args = parser.parse_args()

    try:
        export_and_validate_onnx(
            checkpoint_path=args.checkpoint,
            onnx_output_path=args.output,
            opset_version=args.opset,
            tolerance=args.tolerance,
        )
    except Exception as exc:
        logger.error("ONNX export failed: %s", exc)
        sys.exit(1)


if __name__ == "__main__":
    main()
