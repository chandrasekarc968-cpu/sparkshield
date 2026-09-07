"""SparkShield ONNX Exporter with static (1, 1, 128) dimensions."""

import argparse
import logging
import os
import sys

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("sparkshield.models.export")


def export_onnx(checkpoint_path: str, output_path: str):
    """Exports PyTorch model to static ONNX model."""
    try:
        import torch
        from models.train import SparkShield1DCNN
    except ImportError:
        logger.error("PyTorch required for ONNX export.")
        return False

    model = SparkShield1DCNN(num_classes=4)
    if os.path.exists(checkpoint_path):
        model.load_state_dict(torch.load(checkpoint_path, map_location="cpu"))
        logger.info("Loaded weights from %s", checkpoint_path)
    else:
        logger.warning("Checkpoint not found; exporting uninitialized model.")

    model.eval()

    # Static input tensor: shape (1, 1, 128)
    dummy_input = torch.randn(1, 1, 128, dtype=torch.float32)

    torch.onnx.export(
        model,
        dummy_input,
        output_path,
        export_params=True,
        opset_version=14,
        do_constant_folding=True,
        input_names=["input"],
        output_names=["logits"],
    )
    logger.info("Exported static ONNX model to %s", output_path)
    return True


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Export SparkShield PyTorch checkpoint to ONNX")
    parser.add_argument("--checkpoint", type=str, default="sparkshield_1d_cnn.pt")
    parser.add_argument("--output", type=str, default="sparkshield_1d_cnn.onnx")
    args = parser.parse_args()
    export_onnx(args.checkpoint, args.output)
