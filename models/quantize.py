"""SparkShield INT8 Static Quantization with Calibration Data.

Quantizes static ONNX models using onnxruntime.quantization.quantize_static
with representative calibration telemetry data. Records actual scale and zero-point
parameters to eliminate signed/unsigned ambiguity during edge deployment.
"""

import argparse
import json
import logging
import os
import sys
from typing import Dict, List, Optional

import numpy as np
import onnx
import onnxruntime as ort
from onnxruntime.quantization import (
    CalibrationDataReader,
    CalibrationMethod,
    QuantFormat,
    QuantType,
    quantize_static,
)

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))
from models.calibration.generate_calibration import generate_calibration_dataset

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(name)s: %(message)s")
logger = logging.getLogger("sparkshield.models.quantize")


class SparkShieldCalibrationDataReader(CalibrationDataReader):
    """Feeds (1, 1, 128) calibration tensors to the ONNX Runtime calibrator."""

    def __init__(self, data_path: str, input_name: str = "input"):
        self.data: np.ndarray = np.load(data_path).astype(np.float32)
        self.input_name = input_name
        self.idx = 0
        self.total = len(self.data)
        logger.info("Initialized CalibrationDataReader with %d samples for input '%s'", self.total, self.input_name)

    def get_next(self) -> Optional[Dict[str, np.ndarray]]:
        if self.idx < self.total:
            # Shape of sample in self.data is (1, 128) -> expand to (1, 1, 128)
            sample = self.data[self.idx]
            if sample.ndim == 2:
                sample = np.expand_dims(sample, axis=0)  # (1, 1, 128)
            self.idx += 1
            return {self.input_name: sample}
        return None

    def rewind(self):
        self.idx = 0


def extract_quantization_parameters(onnx_model_path: str) -> Dict[str, Dict]:
    """Inspects quantized ONNX graph initializers to extract scales and zero-points."""
    model = onnx.load(onnx_model_path)
    initializers = {init.name: onnx.numpy_helper.to_array(init) for init in model.graph.initializer}

    quant_params = {
        "tensors": {},
        "summary": {
            "total_initializers": len(initializers),
            "quantized_initializers": 0,
        },
    }

    for name, val in initializers.items():
        if "_scale" in name or "_zero_point" in name or "scale" in name.lower() or "zero_point" in name.lower():
            quant_params["summary"]["quantized_initializers"] += 1
            param_type = "scale" if "scale" in name.lower() else "zero_point"
            tensor_name = name.rsplit("_", 1)[0] if "_" in name else name

            if tensor_name not in quant_params["tensors"]:
                quant_params["tensors"][tensor_name] = {}

            if val.size == 1:
                quant_params["tensors"][tensor_name][param_type] = float(val.item())
            else:
                quant_params["tensors"][tensor_name][param_type] = val.flatten()[:4].tolist()

    return quant_params


def quantize_onnx_model(
    input_model_path: str = "models/sparkshield_1d_cnn.onnx",
    output_model_path: str = "models/sparkshield_1d_cnn_quant.onnx",
    calibration_data_path: str = "models/calibration/calibration_data.npy",
    manifest_path: str = "models/calibration/manifest.json",
) -> Dict:
    """Performs static INT8 calibration and quantization on ONNX model."""
    if not os.path.exists(input_model_path):
        raise FileNotFoundError(f"Input ONNX model not found: {input_model_path}")

    # Ensure calibration dataset exists
    if not os.path.exists(calibration_data_path):
        logger.warning("Calibration data not found; generating 200 samples...")
        generate_calibration_dataset(calibration_data_path, manifest_path)

    # Determine input node name from ONNX model
    onnx_proto = onnx.load(input_model_path)
    input_name = onnx_proto.graph.input[0].name
    logger.info("Target model input tensor name: '%s'", input_name)

    reader = SparkShieldCalibrationDataReader(calibration_data_path, input_name=input_name)

    os.makedirs(os.path.dirname(output_model_path) or ".", exist_ok=True)
    logger.info("Executing static INT8 quantization on %s -> %s...", input_model_path, output_model_path)

    quantize_static(
        model_input=input_model_path,
        model_output=output_model_path,
        calibration_data_reader=reader,
        quant_format=QuantFormat.QDQ,
        activation_type=QuantType.QInt8,
        weight_type=QuantType.QInt8,
        per_channel=False,
        calibrate_method=CalibrationMethod.MinMax,
    )
    logger.info("Quantized model saved to %s", output_model_path)

    # Validate execution of quantized model via ONNX Runtime
    session = ort.InferenceSession(output_model_path, providers=["CPUExecutionProvider"])
    dummy_input = np.random.uniform(0.0, 1.0, size=(1, 1, 128)).astype(np.float32)
    output = session.run(None, {input_name: dummy_input})[0]

    logger.info("Quantized model execution verified: input shape (1, 1, 128) -> output shape %s", output.shape)
    assert output.shape == (1, 4), f"Expected output shape (1, 4), got {output.shape}"

    # Extract scale and zero-point parameters
    quant_params = extract_quantization_parameters(output_model_path)

    # Update manifest
    manifest_data = {}
    if os.path.exists(manifest_path):
        with open(manifest_path, "r") as f:
            manifest_data = json.load(f)

    manifest_data.update({
        "quantized_model_path": output_model_path,
        "quantization_format": "QDQ",
        "activation_type": "QInt8",
        "weight_type": "QInt8",
        "per_channel": False,
        "calibration_method": "MinMax",
        "quantization_parameters": quant_params,
        "edge_deployment_dequantization_rule": (
            "Dequantize signed INT8 using formula: float_value = (int8_value - zero_point) * scale. "
            "Do NOT cast signed INT8 values directly as unsigned bytes."
        ),
    })

    with open(manifest_path, "w") as f:
        json.dump(manifest_data, f, indent=2)
    logger.info("Updated manifest with quantization parameters at %s", manifest_path)

    return manifest_data


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Quantize SparkShield ONNX Model to INT8")
    parser.add_argument("--onnx", type=str, default="models/sparkshield_1d_cnn.onnx")
    parser.add_argument("--output", type=str, default="models/sparkshield_1d_cnn_quant.onnx")
    parser.add_argument("--calibration-data", type=str, default="models/calibration/calibration_data.npy")
    parser.add_argument("--manifest", type=str, default="models/calibration/manifest.json")
    args = parser.parse_args()

    quantize_onnx_model(args.onnx, args.output, args.calibration_data, args.manifest)
