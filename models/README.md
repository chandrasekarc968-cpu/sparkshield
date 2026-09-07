# SparkShield Edge ML Pipeline & Quantization (Phase 2)

## 1. Architecture Overview

SparkShield employs a compact 1D Convolutional Neural Network designed for edge microcontrollers and Qualcomm Hexagon NPUs. It operates on a static input tensor of shape `(1, 1, 128)` representing an 8-frame rolling window of 16 normalized telemetry features.

```
Input: Tensor (Batch, 1, 128)
  │
  ├── Conv1D(1 -> 16, kernel=5, padding=2) -> BatchNorm1d -> ReLU -> MaxPool1d(2)  [Shape: (Batch, 16, 64)]
  │
  ├── Conv1D(16 -> 32, kernel=3, padding=1) -> BatchNorm1d -> ReLU -> MaxPool1d(2) [Shape: (Batch, 32, 32)]
  │
  ├── Conv1D(32 -> 64, kernel=3, padding=1) -> BatchNorm1d -> ReLU                  [Shape: (Batch, 64, 32)]
  │
  ├── AdaptiveAvgPool1d(1) -> Flatten                                                [Shape: (Batch, 64)]
  │
  ├── Linear(64 -> 32) -> ReLU                                                      [Shape: (Batch, 32)]
  │
  └── Linear(32 -> 4)                                                               [Shape: (Batch, 4)]
      (Classes: 0=NORMAL, 1=EMP, 2=OPTICAL, 3=SURGE)
```

---

## 2. Pipeline Execution Workflow

### Step 1: Train Model
Generates leak-free balanced datasets (1000 train, 200 val, 200 test per class) with independent seeds, trains the model, tracks validation Macro F1, and saves the best checkpoint and metadata.
```powershell
python models/train.py --epochs 10 --train-samples 1000 --val-samples 200 --test-samples 200
```
- Produces: `models/sparkshield_1d_cnn.pt`, `models/split_metadata.json`, `models/test_data.npz`.

### Step 2: Evaluate Model Checkpoint
Evaluates the saved checkpoint on the held-out test set with held-out parameter perturbation. Computes the multiclass confusion matrix, per-class precision/recall/F1, and false-positive rate on NORMAL.
```powershell
python models/evaluate.py
```
- Produces: `models/model_metadata.json`.

### Step 3: Export to Static ONNX
Exports PyTorch weights into static graph format (`[1, 1, 128]` $\rightarrow$ `[1, 4]`) and verifies numerical parity with ONNX Runtime within $10^{-5}$ tolerance.
```powershell
python models/export_onnx.py
```
- Produces: `models/sparkshield_1d_cnn.onnx`.

### Step 4: Calibrate & Statically Quantize to INT8
Generates 200 representative calibration samples (50 per class) and runs static QDQ INT8 quantization using `onnxruntime.quantization.quantize_static`. Records actual scale and zero-point parameters in the manifest.
```powershell
python models/quantize.py
```
- Produces: `models/sparkshield_1d_cnn_quant.onnx`, `models/calibration/calibration_data.npy`, `models/calibration/manifest.json`.

---

## 3. Quantization & Edge Deployment Policy

> [!CAUTION]
> **Dequantization Formula**:
> When deploying quantized INT8 models to Qualcomm Hexagon HTP or ONNX Runtime:
> $$\text{float\_value} = (\text{int8\_value} - \text{zero\_point}) \times \text{scale}$$
> **Never** interpret signed INT8 output bytes directly as unsigned values without applying the tensor's actual scale and zero-point parameters recorded in `models/calibration/manifest.json`.

---

## 4. Automated Tests

Run the complete ML pipeline automated test suite:
```powershell
python -m pytest models/tests -v
```

Tests cover:
- Data generation shapes `(N, 1, 128)` float32, `(N,)` int64, and class balance.
- Split isolation and absence of cross-split duplicate samples.
- Model forward pass and loss reduction.
- Checkpoint payload completeness.
- Static ONNX input/output dimension enforcement.
- PyTorch vs ONNX Runtime numerical parity.
- Calibration dataset creation and data reader batching.
- Static INT8 model execution and parameter recording.
