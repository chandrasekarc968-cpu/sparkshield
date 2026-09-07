"""SparkShield Qualcomm QAIRT / QNN Hexagon HTP Model Compiler.

Production pipeline for compiling the SparkShield 1D CNN model into a genuine
Qualcomm Neural Network (QNN) Hexagon Tensor Processor (HTP) context binary
using the official Qualcomm AI Engine Direct (QAIRT / QNN) SDK.

Pipeline Stages:
  1. Export calibration inputs (raw IEEE 754 float32 binary tensors + input_list.txt).
  2. qnn-onnx-converter: Convert static ONNX graph [1, 1, 128] -> [1, 4] to QNN C++ model.
  3. qnn-model-lib-generator: Compile QNN model C++ source into aarch64-android model library.
  4. qnn-context-binary-generator: Generate genuine serialized HTP context binary using libQnnHtp.so.
  5. Generate reproducible manifest containing model hashes, SoC target, HTP architecture,
     quantization scale/offset parameters, and exact compiler command line.

Safety and Integrity Rules:
  - Requires official Qualcomm QNN/QAIRT SDK tools.
  - Strictly fails if official tools are not installed; NEVER produces fake binary containers.
  - SoC target and HTP architecture are fully configurable (default: SM8750 / HTP v79).
"""

import argparse
import hashlib
import json
import logging
import os
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Dict, List, Optional, Tuple

import numpy as np

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(name)s: %(message)s")
logger = logging.getLogger("sparkshield.models.compile_qnn")

REPO_ROOT = Path(__file__).resolve().parent.parent
ONNX_MODEL_PATH = REPO_ROOT / "models" / "sparkshield_1d_cnn.onnx"
CALIBRATION_DATA_PATH = REPO_ROOT / "models" / "calibration" / "calibration_data.npy"
DEFAULT_OUTPUT_DIR = REPO_ROOT / "android_app" / "app" / "src" / "main" / "assets"
DEFAULT_MANIFEST_PATH = REPO_ROOT / "models" / "qnn_manifest.json"

# Supported Snapdragon Targets & HTP Architectures
SOC_TO_HTP_ARCH = {
    "SM8750": "v79",  # Snapdragon 8 Elite (Gen 5)
    "SM8650": "v75",  # Snapdragon 8 Gen 3
    "SM8550": "v73",  # Snapdragon 8 Gen 2
    "SM8475": "v69",  # Snapdragon 8+ Gen 1
    "SM8450": "v69",  # Snapdragon 8 Gen 1
    "X_ELITE": "v75", # Snapdragon X Elite (compute)
}


class QnnCompilationError(RuntimeError):
    """Raised when QNN compilation fails or prerequisites are not met."""
    pass


def find_tool(tool_name: str, sdk_root: Optional[Path] = None) -> Optional[Path]:
    """Locate official Qualcomm SDK CLI binary in PATH or SDK root."""
    # Check PATH first
    path_bin = shutil.which(tool_name)
    if path_bin:
        return Path(path_bin).resolve()

    if sdk_root and sdk_root.is_dir():
        # Common QNN SDK bin layouts
        candidates = [
            sdk_root / "bin" / "x86_64-linux-clang" / tool_name,
            sdk_root / "bin" / "x86_64-windows-msvc" / f"{tool_name}.exe",
            sdk_root / "bin" / f"{tool_name}.exe",
            sdk_root / "bin" / tool_name,
        ]
        for candidate in candidates:
            if candidate.is_file():
                return candidate.resolve()

    return None


def verify_prerequisites() -> Tuple[np.ndarray, str]:
    """Verify input ONNX model and calibration dataset."""
    if not ONNX_MODEL_PATH.is_file():
        raise FileNotFoundError(f"ONNX model missing at {ONNX_MODEL_PATH}. Run models/export_onnx.py first.")

    with open(ONNX_MODEL_PATH, "rb") as f:
        model_sha256 = hashlib.sha256(f.read()).hexdigest()

    if not CALIBRATION_DATA_PATH.is_file():
        raise FileNotFoundError(f"Calibration data missing at {CALIBRATION_DATA_PATH}.")

    calib_data = np.load(CALIBRATION_DATA_PATH)
    logger.info(f"Loaded calibration dataset: shape={calib_data.shape}, dtype={calib_data.dtype}")
    assert calib_data.shape[1:] == (1, 128), f"Expected shape [N, 1, 128], got {calib_data.shape}"

    return calib_data, model_sha256


def export_calibration_tensors(calib_data: np.ndarray, output_dir: Path, max_samples: int = 100) -> Tuple[Path, List[Path]]:
    """Export raw IEEE 754 binary float32 tensors and input_list.txt for qnn-onnx-converter."""
    output_dir.mkdir(parents=True, exist_ok=True)
    raw_files = []
    input_list_path = output_dir / "input_list.txt"

    samples_to_export = min(max_samples, len(calib_data))
    with open(input_list_path, "w", encoding="utf-8") as f_list:
        for idx in range(samples_to_export):
            raw_path = output_dir / f"calib_{idx:03d}.raw"
            sample = calib_data[idx].astype(np.float32)
            sample.tofile(str(raw_path))
            raw_files.append(raw_path)
            # Format: <input_tensor_name>:=<path_to_raw_file>
            f_list.write(f"input:={raw_path.resolve().as_posix()}\n")

    logger.info(f"Exported {len(raw_files)} calibration tensors to {output_dir}")
    return input_list_path, raw_files


def run_command(cmd: List[str], desc: str, env: Optional[Dict[str, str]] = None) -> None:
    """Run a subprocess command with clear error reporting."""
    cmd_str = " ".join(cmd)
    logger.info(f"Executing [{desc}]: {cmd_str}")
    result = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, env=env)
    if result.returncode != 0:
        logger.error(f"Command failed with exit code {result.returncode}:\nSTDOUT: {result.stdout}\nSTDERR: {result.stderr}")
        raise QnnCompilationError(f"{desc} failed ({result.returncode}): {result.stderr.strip()}")
    logger.info(f"[{desc}] completed successfully.")


def compile_qnn_pipeline(
    onnx_path: Path,
    input_list_path: Path,
    output_dir: Path,
    soc: str,
    htp_arch: str,
    qnn_sdk_root: Optional[Path],
    ndk_root: Optional[Path],
    model_name: str = "sparkshield_1d_cnn"
) -> Tuple[Path, Dict]:
    """Execute official Qualcomm QNN compilation pipeline."""
    # 1. Locate Qualcomm tools
    converter = find_tool("qnn-onnx-converter", qnn_sdk_root)
    lib_generator = find_tool("qnn-model-lib-generator", qnn_sdk_root)
    context_generator = find_tool("qnn-context-binary-generator", qnn_sdk_root)

    missing = []
    if not converter: missing.append("qnn-onnx-converter")
    if not lib_generator: missing.append("qnn-model-lib-generator")
    if not context_generator: missing.append("qnn-context-binary-generator")

    if missing:
        msg = (
            f"Official Qualcomm QNN / QAIRT SDK tools are missing: {', '.join(missing)}.\n"
            f"Genuine Hexagon HTP context compilation requires the Qualcomm AI Engine Direct SDK.\n"
            f"Please set QNN_SDK_ROOT or add tools to PATH.\n"
            f"Generating fake context binaries or packaging raw ONNX models is strictly disallowed."
        )
        logger.error(msg)
        raise QnnCompilationError(msg)

    # Working directory for intermediate generated C++ and libraries
    work_dir = REPO_ROOT / "models" / "qnn_build"
    work_dir.mkdir(parents=True, exist_ok=True)

    model_cpp = work_dir / f"{model_name}.cpp"
    model_bin = work_dir / f"{model_name}.bin"

    # 2. Stage 1: qnn-onnx-converter
    conv_cmd = [
        str(converter),
        "--input_network", str(onnx_path.resolve()),
        "--input_list", str(input_list_path.resolve()),
        "--output_path", str(model_cpp.resolve()),
        "--act_bw", "8",
        "--weight_bw", "8"
    ]
    run_command(conv_cmd, "qnn-onnx-converter")

    # 3. Stage 2: qnn-model-lib-generator
    env = os.environ.copy()
    if ndk_root:
        env["ANDROID_NDK_ROOT"] = str(ndk_root.resolve())

    lib_cmd = [
        str(lib_generator),
        "-c", str(model_cpp.resolve()),
        "-b", str(model_bin.resolve()),
        "-t", "aarch64-android",
        "-o", str(work_dir.resolve())
    ]
    run_command(lib_cmd, "qnn-model-lib-generator", env=env)

    # 4. Stage 3: qnn-context-binary-generator
    model_lib_so = work_dir / "aarch64-android" / f"lib{model_name}.so"
    if not model_lib_so.exists():
        raise QnnCompilationError(f"Compiled model library not found at {model_lib_so}")

    backend_lib = "libQnnHtp.so"
    if qnn_sdk_root:
        backend_candidate = qnn_sdk_root / "lib" / "aarch64-android" / "libQnnHtp.so"
        if backend_candidate.is_file():
            backend_lib = str(backend_candidate.resolve())

    output_dir.mkdir(parents=True, exist_ok=True)
    out_bin_path = output_dir / "sparkshield_htp.bin"

    ctx_cmd = [
        str(context_generator),
        "--model", str(model_lib_so.resolve()),
        "--backend", backend_lib,
        "--binary_file", out_bin_path.name,
        "--output_dir", str(output_dir.resolve()),
        "--config_file", f"htp_arch={htp_arch}"
    ]
    run_command(ctx_cmd, "qnn-context-binary-generator")

    if not out_bin_path.is_file():
        raise QnnCompilationError(f"Context binary was not produced at {out_bin_path}")

    # Compute binary hash
    with open(out_bin_path, "rb") as f:
        bin_sha256 = hashlib.sha256(f.read()).hexdigest()

    manifest_info = {
        "model_name": model_name,
        "target_soc": soc,
        "htp_architecture": f"HTP_{htp_arch.upper()}",
        "binary_filename": out_bin_path.name,
        "binary_sha256": bin_sha256,
        "binary_size_bytes": out_bin_path.stat().st_size,
        "compiler_tool": str(context_generator),
        "compiler_command": " ".join(ctx_cmd),
        "input_tensor": {"name": "input", "shape": [1, 1, 128], "dtype": "INT8/FLOAT32"},
        "output_tensor": {"name": "logits", "shape": [1, 4], "dtype": "FLOAT32"},
        "classes": ["NORMAL", "EMP", "OPTICAL", "SURGE"],
        "quantization": {"method": "PTQ", "act_bw": 8, "weight_bw": 8}
    }

    return out_bin_path, manifest_info


def main():
    parser = argparse.ArgumentParser(description="SparkShield Qualcomm QAIRT / QNN Model Compiler")
    parser.add_argument("--qnn-sdk-root", type=str, default=os.getenv("QNN_SDK_ROOT") or os.getenv("QAIRT_SDK_ROOT"),
                        help="Path to Qualcomm QNN / QAIRT SDK root")
    parser.add_argument("--ndk-root", type=str, default=os.getenv("ANDROID_NDK_ROOT") or os.getenv("NDK_ROOT"),
                        help="Path to Android NDK root")
    parser.add_argument("--soc", type=str, default="SM8750",
                        help="Target Snapdragon SoC (default: SM8750 - Snapdragon 8 Elite)")
    parser.add_argument("--htp-arch", type=str, default=None,
                        help="Target HTP architecture (v79, v75, v73). Defaults to SoC-mapped architecture.")
    parser.add_argument("--output-dir", type=str, default=str(DEFAULT_OUTPUT_DIR),
                        help="Output directory for context binary")
    parser.add_argument("--manifest", type=str, default=str(DEFAULT_MANIFEST_PATH),
                        help="Path to save reproducible qnn_manifest.json")
    args = parser.parse_args()

    print("=" * 75)
    print("SparkShield: Qualcomm QAIRT / QNN Hexagon HTP Compiler")
    print("=" * 75)

    soc = args.soc.upper()
    htp_arch = args.htp_arch or SOC_TO_HTP_ARCH.get(soc, "v79")

    print(f"Target SoC:             {soc}")
    print(f"Target HTP Architecture: HTP {htp_arch.upper()}")
    print(f"QNN SDK Root:           {args.qnn_sdk_root or 'NOT CONFIGURED (Will search PATH)'}")
    print(f"Android NDK Root:       {args.ndk_root or 'NOT CONFIGURED'}")
    print(f"Output Directory:       {args.output_dir}")

    try:
        calib_data, onnx_sha256 = verify_prerequisites()

        calib_raw_dir = REPO_ROOT / "models" / "calibration" / "raw_tensors"
        input_list_path, _ = export_calibration_tensors(calib_data, calib_raw_dir)

        qnn_sdk_path = Path(args.qnn_sdk_root) if args.qnn_sdk_root else None
        ndk_path = Path(args.ndk_root) if args.ndk_root else None
        output_dir = Path(args.output_dir)

        out_bin, manifest_data = compile_qnn_pipeline(
            onnx_path=ONNX_MODEL_PATH,
            input_list_path=input_list_path,
            output_dir=output_dir,
            soc=soc,
            htp_arch=htp_arch,
            qnn_sdk_root=qnn_sdk_path,
            ndk_root=ndk_path
        )

        manifest_data["onnx_source_sha256"] = onnx_sha256
        with open(args.manifest, "w", encoding="utf-8") as f:
            json.dump(manifest_data, f, indent=2)

        print(f"SUCCESS: Compiled genuine QNN HTP Context Binary: {out_bin}")
        print(f"Manifest written to: {args.manifest}")

    except QnnCompilationError as e:
        print("\n" + "!" * 75)
        print(f"COMPILATION ERROR: {e}")
        print("!" * 75)
        sys.exit(1)
    except Exception as e:
        logger.exception("Unexpected error during QNN compilation")
        sys.exit(1)


if __name__ == "__main__":
    main()
