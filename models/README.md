# SparkShield Edge ML Models

## Pipeline

1. `train.py`: Generates deterministic synthetic data using `python_core.signal_models`, trains the 1D CNN with input shape `(1, 1, 128)`, evaluates metrics (confusion matrix, precision/recall/F1, false positive rate on NORMAL), and saves the PyTorch checkpoint.
2. `export_onnx.py`: Exports PyTorch model to static ONNX graph.
3. `quantize.py`: Produces INT8 quantized model and `calibration/manifest.json` for embedded/Qualcomm Hexagon NPU deployment.
