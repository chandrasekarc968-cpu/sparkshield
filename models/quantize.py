"""SparkShield INT8 Static Quantization Pipeline.

Performs static post-training INT8 quantization using ONNX Runtime quantization
with representative calibration telemetry. Compares float ONNX vs quantized INT8
predictions on the test set, enforces signed INT8 handling, and saves quantization
metadata.
"""

import argparse
import json
import logging
import os
import sys
from typing import Dict, List, Optional, Tuple

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8")

import numpy as np
from sklearn.metrics import f1_score, precision_score, recall_score

# Dependency verification: strictly fail with instructions if missing
try:
    import onnx
    import onnxruntime as ort
    from onnxruntime.quantization import (
        CalibrationDataReader,
        CalibrationMethod,
        QuantFormat,
        QuantType,
        quantize_static,
    )
    HAS_ONNX_QUANT = True
except ImportError as exc:
    HAS_ONNX_QUANT = False
    _IMPORT_ERROR_MSG = str(exc)

# Ensure root directory is in sys.path
_ROOT_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
if _ROOT_DIR not in sys.path:
    sys.path.insert(0, _ROOT_DIR)

from models.calibration.generate_calibration import generate_calibration_data
from models.train import CLASS_NAMES, SYNTHETIC_DATA_DISCLAIMER, generate_split

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(name)s: %(message)s")
logger = logging.getLogger("sparkshield.models.quantize")


class SparkShieldCalibrationDataReader(CalibrationDataReader):
    """Streams calibration tensors of shape [1, 1, 128] to the ONNX Runtime calibrator."""

    def __init__(self, tensors: np.ndarray, input_name: str = "input"):
        self.tensors = tensors.astype(np.float32)
        self.input_name = input_name
        self.idx = 0
        self.total = len(self.tensors)

    def get_next(self) -> Optional[Dict[str, np.ndarray]]:
        if self.idx < self.total:
            # Ensure shape is exactly (1, 1, 128)
            sample = self.tensors[self.idx]
            if sample.ndim == 2:
                sample = np.expand_dims(sample, axis=0)
            self.idx += 1
            return {self.input_name: sample}
        return None

    def rewind(self):
        self.idx = 0


def extract_quant_parameters(onnx_model_path: str) -> Dict:
    """Inspects quantized ONNX initializers to extract scales and zero-points."""
    model = onnx.load(onnx_model_path)
    initializers = {init.name: onnx.numpy_helper.to_array(init) for init in model.graph.initializer}

    tensors_meta = {}
    for name, arr in initializers.items():
        if "scale" in name.lower() or "zero_point" in name.lower():
            base = name
            for suffix in ["_scale", "_zero_point", "_quantized", "_quantized_zero"]:
                if base.endswith(suffix):
                    base = base[:-len(suffix)]
                    break
            if base not in tensors_meta:
                tensors_meta[base] = {}
            if "scale" in name.lower():
                tensors_meta[base]["scale"] = float(arr.item()) if arr.size == 1 else arr.flatten()[:4].tolist()
            if "zero_point" in name.lower():
                tensors_meta[base]["zero_point"] = float(arr.item()) if arr.size == 1 else arr.flatten()[:4].tolist()

    return {
        "total_initializers": len(initializers),
        "quantized_tensors": tensors_meta,
    }


def evaluate_onnx_model_on_test(
    onnx_path: str,
    x_test: np.ndarray,
    y_test: np.ndarray,
) -> Dict:
    """Runs ONNX model on test set and computes accuracy and macro F1."""
    session = ort.InferenceSession(onnx_path, providers=["CPUExecutionProvider"])
    input_name = session.get_inputs()[0].name

    all_preds = []
    all_logits = []

    for i in range(len(x_test)):
        sample = x_test[i]
        if sample.ndim == 2:
            sample = np.expand_dims(sample, axis=0)  # (1, 1, 128)

        logits = session.run(None, {input_name: sample})[0]
        # Assert logits remain finite (no NaN, no Inf)
        if np.isnan(logits).any() or np.isinf(logits).any():
            raise ValueError(f"Inference produced non-finite logits (NaN/Inf) at sample {i} in {onnx_path}")

        pred = int(np.argmax(logits, axis=1)[0])
        all_preds.append(pred)
        all_logits.append(logits)

    preds_arr = np.array(all_preds)
    accuracy = float(np.mean(preds_arr == y_test))
    macro_f1 = float(f1_score(y_test, preds_arr, average="macro", zero_division=0))
    macro_precision = float(precision_score(y_test, preds_arr, average="macro", zero_division=0))
    macro_recall = float(recall_score(y_test, preds_arr, average="macro", zero_division=0))

    return {
        "accuracy": accuracy,
        "macro_f1": macro_f1,
        "macro_precision": macro_precision,
        "macro_recall": macro_recall,
        "total_test_samples": len(y_test),
    }


def quantize_model(
    onnx_path: str,
    calibration_manifest_path: str,
    output_path: str,
) -> Dict:
    """Runs static INT8 post-training quantization and compares with float ONNX."""
    if not HAS_ONNX_QUANT:
        raise ImportError(
            f"ONNX Runtime quantization is not available: {_IMPORT_ERROR_MSG}. "
            "Please install dependencies with: pip install onnx onnxruntime onnxscript"
        )

    if not os.path.exists(onnx_path):
        raise FileNotFoundError(f"Input ONNX model not found: '{onnx_path}'")

    # Load or generate calibration data
    manifest_dir = os.path.dirname(calibration_manifest_path) or "."
    if os.path.exists(calibration_manifest_path):
        with open(calibration_manifest_path, "r") as f:
            manifest = json.load(f)
        rel_data_path = manifest.get("data_file_path", "calibration_tensors.npy")
        full_data_path = os.path.normpath(os.path.join(manifest_dir, rel_data_path))
        if not os.path.exists(full_data_path):
            logger.warning("Calibration data file '%s' not found; regenerating...", full_data_path)
            calib_tensors, manifest = generate_calibration_data(
                output_dir=manifest_dir,
                manifest_path=calibration_manifest_path,
            )
        else:
            calib_tensors = np.load(full_data_path)
            logger.info("Loaded %d calibration tensors from '%s'", len(calib_tensors), full_data_path)
    else:
        logger.warning("Calibration manifest not found; generating calibration dataset...")
        calib_tensors, manifest = generate_calibration_data(
            output_dir=manifest_dir,
            manifest_path=calibration_manifest_path,
        )

    # Determine input node name from ONNX model
    onnx_model = onnx.load(onnx_path)
    input_name = onnx_model.graph.input[0].name

    reader = SparkShieldCalibrationDataReader(calib_tensors, input_name=input_name)

    output_dir = os.path.dirname(output_path) or "."
    os.makedirs(output_dir, exist_ok=True)

    logger.info("Executing static INT8 quantization on '%s' -> '%s'...", onnx_path, output_path)

    quantize_static(
        model_input=onnx_path,
        model_output=output_path,
        calibration_data_reader=reader,
        quant_format=QuantFormat.QDQ,
        activation_type=QuantType.QInt8,
        weight_type=QuantType.QInt8,
        per_channel=False,
        calibrate_method=CalibrationMethod.MinMax,
    )

    if not os.path.exists(output_path) or os.path.getsize(output_path) == 0:
        raise RuntimeError(f"Quantization failed: output file '{output_path}' was not generated.")

    logger.info("Quantized model saved to '%s'", output_path)

    # Validate quantized model execution with ONNX Runtime
    session = ort.InferenceSession(output_path, providers=["CPUExecutionProvider"])
    dummy_input = np.random.uniform(0.0, 1.0, size=(1, 1, 128)).astype(np.float32)
    sample_out = session.run(None, {input_name: dummy_input})[0]

    assert sample_out.shape == (1, 4), f"Expected shape (1, 4), got {sample_out.shape}"
    assert not np.isnan(sample_out).any(), "Quantized inference returned NaN"
    assert not np.isinf(sample_out).any(), "Quantized inference returned Inf"
    logger.info("Quantized model validation check passed: static input [1, 1, 128] -> output [1, 4]")

    # Extract scale and zero-point parameters
    quant_params = extract_quant_parameters(output_path)

    # Load or generate test set for side-by-side comparison
    test_data_path = os.path.join(os.path.dirname(onnx_path) or ".", "test_data.npz")
    if os.path.exists(test_data_path):
        test_npz = np.load(test_data_path)
        x_test = test_npz["x_test"]
        y_test = test_npz["y_test"]
    else:
        logger.info("Generating held-out test split for model comparison...")
        x_test, y_test = generate_split("test", samples_per_class=200, seed=345, held_out_ranges=True)

    logger.info("Evaluating Float ONNX model on test set (%d samples)...", len(y_test))
    float_metrics = evaluate_onnx_model_on_test(onnx_path, x_test, y_test)

    logger.info("Evaluating INT8 Quantized ONNX model on test set (%d samples)...", len(y_test))
    quant_metrics = evaluate_onnx_model_on_test(output_path, x_test, y_test)

    logger.info("================ MODEL COMPARISON ON TEST SET ================")
    logger.info(f"{'Metric':<20} | {'Float ONNX':<15} | {'Quantized INT8':<15}")
    logger.info("--------------------------------------------------------------")
    logger.info(f"{'Accuracy':<20} | {float_metrics['accuracy']:<15.4f} | {quant_metrics['accuracy']:<15.4f}")
    logger.info(f"{'Macro F1':<20} | {float_metrics['macro_f1']:<15.4f} | {quant_metrics['macro_f1']:<15.4f}")
    logger.info(f"{'Macro Precision':<20} | {float_metrics['macro_precision']:<15.4f} | {quant_metrics['macro_precision']:<15.4f}")
    logger.info(f"{'Macro Recall':<20} | {float_metrics['macro_recall']:<15.4f} | {quant_metrics['macro_recall']:<15.4f}")
    logger.info("==============================================================")

    # Record quantization configuration and evaluation comparison JSON
    base_out = os.path.splitext(output_path)[0]
    config_out_path = f"{base_out}_config.json"

    quant_config = {
        "model_name": "SparkShield1DCNN",
        "quantization_type": "Static Post-Training Quantization (PTQ)",
        "quant_format": "QDQ",
        "activation_type": "QInt8 (Signed 8-bit integer)",
        "weight_type": "QInt8 (Signed 8-bit integer)",
        "calibration_method": "MinMax",
        "per_channel": False,
        "input_name": input_name,
        "input_shape": [1, 1, 128],
        "output_shape": [1, 4],
        "class_names": CLASS_NAMES,
        "disclaimer": SYNTHETIC_DATA_DISCLAIMER,
        "dequantization_rule": (
            "Dequantize signed INT8 using formula: float_value = (int8_value - zero_point) * scale. "
            "Do NOT cast signed INT8 values directly as unsigned bytes without dequantization."
        ),
        "test_comparison": {
            "float_onnx": float_metrics,
            "quantized_int8": quant_metrics,
            "accuracy_delta": quant_metrics["accuracy"] - float_metrics["accuracy"],
            "macro_f1_delta": quant_metrics["macro_f1"] - float_metrics["macro_f1"],
        },
        "quantization_parameters": quant_params,
    }

    with open(config_out_path, "w") as f:
        json.dump(quant_config, f, indent=2)
    logger.info("Saved quantization configuration and comparison report to '%s'", config_out_path)

    return quant_config


def main():
    parser = argparse.ArgumentParser(description="SparkShield Static INT8 ONNX Quantizer")
    parser.add_argument("--onnx", type=str, default="artifacts/sparkshield.onnx", help="Path to input float ONNX model")
    parser.add_argument("--calibration-manifest", type=str, default="artifacts/calibration/manifest.json", help="Path to calibration manifest")
    parser.add_argument("--output", type=str, default="artifacts/sparkshield_int8.onnx", help="Path to output quantized ONNX model")
    args = parser.parse_args()

    try:
        quantize_model(
            onnx_path=args.onnx,
            calibration_manifest_path=args.calibration_manifest,
            output_path=args.output,
        )
    except Exception as exc:
        logger.error("Quantization failed: %s", exc)
        sys.exit(1)


if __name__ == "__main__":
    main()
