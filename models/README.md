# SparkShield Edge ML Pipeline & Quantization (Phase 2)

> [!IMPORTANT]
> **Synthetic Data Disclaimer**:
> All models, training sets, calibrations, and evaluation metrics reported here are based strictly on deterministic software simulations of smart-meter telemetry. No production or real-world physical hardware accuracy is claimed. This software does not interact with, control, or damage real electrical meters or physical attack equipment.

---

## 1. Required Dependencies

All Python dependencies are specified in [`requirements-dev.txt`](file:///c:/Users/Chand/Documents/New%20folder/sparkshield/sparkshield/requirements-dev.txt):

```
numpy>=1.26.0
scipy>=1.10.0
pytest>=8.0.0
torch>=2.0.0
onnx>=1.15.0
onnxruntime>=1.17.0
onnxscript>=0.1.0
scikit-learn>=1.4.0
```

Install via pip:
```powershell
pip install -r requirements-dev.txt
```

---

## 2. Model Architecture: `SparkShield1DCNN`

The model is a compact 1D Convolutional Neural Network with static input shape `[1, 1, 128]` tailored for edge microcontrollers and Qualcomm Hexagon NPUs:

```
Layer (type)                       Output Shape         Param #
================================================================
Input                              [Batch, 1, 128]      0
Conv1D(1 -> 16, k=5, pad=2)        [Batch, 16, 128]     96
BatchNorm1d(16) + ReLU             [Batch, 16, 128]     32
MaxPool1d(kernel_size=2)           [Batch, 16, 64]      0
Conv1D(16 -> 32, k=3, pad=1)       [Batch, 32, 64]      1,568
BatchNorm1d(32) + ReLU             [Batch, 32, 64]      64
MaxPool1d(kernel_size=2)           [Batch, 32, 32]      0
Conv1D(32 -> 64, k=3, pad=1)       [Batch, 64, 32]      6,208
BatchNorm1d(64) + ReLU             [Batch, 64, 32]      128
AdaptiveAvgPool1d(1)               [Batch, 64, 1]       0
Flatten                            [Batch, 64]          0
Linear(64 -> 32) + ReLU            [Batch, 32]          2,080
Linear(32 -> 4) (Logits)           [Batch, 4]           132
================================================================
Total parameters: 10,308 (Trainable: 10,308)
```

---

## 3. Feature Tensor Layout `[1, 1, 128]`

The model processes an 8-frame sliding window of 16 normalized telemetry features:

| Index in Frame | Feature Name | Normalization Rule | Description |
| :--- | :--- | :--- | :--- |
| `0` | Linear Peak mV | `peak_mv / 65535.0` | Peak voltage magnitude (0.0–1.0) |
| `1` | Linear Rise Time | `rise_time_code / 65535.0` | Normalized rise-time code |
| `2` | Log Rise Time | `log1p(code) / log1p(65535)` | High-resolution nanosecond EMP transient scale |
| `3` | Linear Decay Time | `decay_time_us / 65535.0` | Normalized decay time in microseconds |
| `4` | Log Decay Time | `log1p(us) / log1p(65535)` | High-resolution µs vs ms decay scale |
| `5` | Linear Optical mV | `optical_sensor_mv / 65535.0` | Ambient optical sensor voltage |
| `6` | Optical Rail Proximity | `min(optical_mv / 5000.0, 1.0)` | Optical saturation rail indicator |
| `7` | High-Frequency Ratio | `sum(bins[4:8]) / sum(bins)` | High-band RF spectral concentration |
| `8 - 15` | FFT Bins 0 through 7 | `fft_energy_bins[0..7] / 255.0` | 8-channel frequency energy envelope |

Window assembly: $8\text{ frames} \times 16\text{ features} = 128\text{ floats}$. Concatenated chronologically into tensor shape `(1, 1, 128)` float32.

---

## 4. Class Mapping

| Label Index | Class Name | Physical Characteristics |
| :--- | :--- | :--- |
| `0` | `NORMAL` | Benign 50/60 Hz AC grid fundamental, low noise, ambient optical sensor |
| `1` | `EMP` | Fast transient (10–30 ns rise, µs decay, high peak mV, broad RF bins 3..7) |
| `2` | `OPTICAL` | Saturated optical sensor (>3200 mV approaching rail), normal grid voltage |
| `3` | `SURGE` | Inductive damped sinusoid (500–5000 µs decay, medium-high peak, resonant bins 1..2) |

---

## 5. Training Split Strategy & Leak-Free Design

To prevent information leakage between dataset partitions:
- **Independent Seeds**: Train (`seed + 101`), Validation (`seed + 202`), and Test (`seed + 303`) use independent RNG streams.
- **Dedicated Feature Extractors**: Each sample runs through a fresh, unshared `FeatureExtractor` instance.
- **Held-out Parameter Ranges**: The test set introduces grid frequency perturbations (48.5–51.5 Hz) to evaluate generalization on unseen parameters.
- **Balanced Partitions**: Default training generates 1000 samples per class (4000 total), 200 per class validation (800 total), and 200 per class test (800 total).

---

## 6. Exact Pipeline Commands

### 6.1. Train Model
```powershell
python -m models.train \
  --epochs 20 \
  --samples-per-class 1000 \
  --seed 42 \
  --output artifacts/sparkshield.pt \
  --metrics artifacts/metrics.json
```
- Trains the model for 20 epochs using CrossEntropyLoss and Adam.
- Selects the best checkpoint based on validation Macro F1.
- Computes and saves full validation and test metrics to `artifacts/metrics.json`.

### 6.2. Evaluate Model
```powershell
python -m models.evaluate \
  --checkpoint artifacts/sparkshield.pt \
  --test-data artifacts/test_data.npz \
  --output artifacts/model_metadata.json
```
- Computes accuracy, macro precision/recall/F1, NORMAL false-positive rate, per-class metrics, and 4x4 confusion matrix.

### 6.3. Export to Static ONNX
```powershell
python -m models.export_onnx \
  --checkpoint artifacts/sparkshield.pt \
  --output artifacts/sparkshield.onnx
```
- Requires a valid trained checkpoint (fails strictly if missing).
- Exports static input name `"input"` with shape `[1, 1, 128]`.
- Exports static output name `"logits"` with shape `[1, 4]`.
- Enforces Opset 17 with constant folding and no dynamic axes.
- Runs parity check between PyTorch and ONNX Runtime across 50 trials (asserts max error $\le 10^{-5}$).
- Saves metadata JSON to `artifacts/sparkshield_metadata.json`.

### 6.4. Generate Calibration Data
```powershell
python -m models.calibration.generate_calibration \
  --output-dir artifacts/calibration \
  --manifest artifacts/calibration/manifest.json \
  --samples-per-class 50 \
  --seed 999
```
- Generates 200 balanced float32 tensors using the identical `SignalGenerator` + `FeatureExtractor` pipeline.
- Writes manifest with file paths, sample tensor shapes `[1, 1, 128]`, class distributions, and seed.

### 6.5. Static INT8 Quantization
```powershell
python -m models.quantize \
  --onnx artifacts/sparkshield.onnx \
  --calibration-manifest artifacts/calibration/manifest.json \
  --output artifacts/sparkshield_int8.onnx
```
- Executes static post-training INT8 quantization using `onnxruntime.quantization.quantize_static` (QDQ format, signed `QInt8`).
- Validates quantized model execution on ONNX Runtime.
- Evaluates float ONNX vs quantized INT8 predictions side-by-side on the test set.
- Records quantization parameters (scales and zero-points) in `artifacts/sparkshield_int8_config.json`.

---

## 7. Quantization Dequantization Policy

> [!CAUTION]
> **Edge Runtime Rule**:
> When deploying signed INT8 models to ONNX Runtime or Qualcomm Hexagon HTP:
> $$\text{float\_value} = (\text{int8\_value} - \text{zero\_point}) \times \text{scale}$$
> **Never** interpret signed INT8 output bytes directly as unsigned bytes without applying the tensor's recorded scale and zero-point.

---

## 8. Automated Tests

Run the complete Phase 2 test suite (13 tests):
```powershell
python -m pytest models/tests -v
```

Run all system tests across Phase 1 & 2 (40 tests):
```powershell
python -m pytest python_core/tests models/tests -v
```

---

## 9. Known Limitations

1. **Synthetic Telemetry Only**: Signals are generated using mathematical and differential physics models for educational and architectural validation. No hardware attacks or field testing.
2. **NPU Profiling**: Quantized ONNX execution is verified on CPU. Direct Hexagon HTP profiling and QNN execution will be implemented in Phase 7.
