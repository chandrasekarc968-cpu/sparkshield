.PHONY: help train-model evaluate-model export-onnx generate-calibration quantize-model test-models test-all run-mock run-mock-tcp android-build android-test android-lint android-install-debug clean

PYTHON ?= python
GRADLE ?= $(if $(filter Windows_NT,$(OS)),gradlew.bat,./gradlew)

help:
	@echo "SparkShield ML Pipeline, Android & Protocol Build Targets:"
	@echo "  make train-model          - Train SparkShield 1D CNN with validation/test metrics"
	@echo "  make evaluate-model       - Evaluate checkpoint on held-out test data"
	@echo "  make export-onnx          - Export trained model to static ONNX & verify numerical parity"
	@echo "  make generate-calibration - Generate 200 balanced calibration tensors & manifest"
	@echo "  make quantize-model       - Static INT8 quantization with ONNX Runtime & compare on test set"
	@echo "  make test-models          - Run ML pipeline, ONNX, and quantization unit tests"
	@echo "  make test-all             - Run complete test suite (Phase 1, Phase 2, and parity)"
	@echo "  make android-build        - Assemble Android debug APK via Gradle"
	@echo "  make android-test         - Run Android JVM unit tests via Gradle"
	@echo "  make android-lint         - Run Android code quality lint checks via Gradle"
	@echo "  make android-install-debug - Install debug APK onto connected Android device"
	@echo "  make run-mock             - Run interactive mock telemetry stream"
	@echo "  make run-mock-tcp         - Run mock telemetry TCP server on port 9002"
	@echo "  make clean                - Remove build, cache, and temporary test artifacts"

train-model:
	$(PYTHON) -m models.train --epochs 20 --samples-per-class 1000 --seed 42 --output artifacts/sparkshield.pt --metrics artifacts/metrics.json

evaluate-model:
	$(PYTHON) -m models.evaluate --checkpoint artifacts/sparkshield.pt --test-data artifacts/test_data.npz --output artifacts/model_metadata.json

export-onnx:
	$(PYTHON) -m models.export_onnx --checkpoint artifacts/sparkshield.pt --output artifacts/sparkshield.onnx
	@cp -f artifacts/sparkshield.onnx android_app/app/src/main/assets/sparkshield_1d_cnn.onnx 2>/dev/null || copy /y artifacts\sparkshield.onnx android_app\app\src\main\assets\sparkshield_1d_cnn.onnx || true

generate-calibration:
	$(PYTHON) -m models.calibration.generate_calibration --output-dir artifacts/calibration --manifest artifacts/calibration/manifest.json --samples-per-class 50 --seed 999

quantize-model:
	$(PYTHON) -m models.quantize --onnx artifacts/sparkshield.onnx --calibration-manifest artifacts/calibration/manifest.json --output artifacts/sparkshield_int8.onnx

test-models:
	$(PYTHON) -m pytest models/tests -v

test-all:
	$(PYTHON) -m pytest python_core/tests models/tests -v
	$(PYTHON) tests/verify_android_parity.py

android-build:
	cd android_app && $(GRADLE) assembleDebug

android-test:
	cd android_app && $(GRADLE) test

android-lint:
	cd android_app && $(GRADLE) lint

android-install-debug:
	cd android_app && $(GRADLE) installDebug

run-mock:
	$(PYTHON) -m python_core.mock_stream --rate 10 --count 20

run-mock-tcp:
	$(PYTHON) -m python_core.mock_stream --rate 10 --tcp --port 9002

clean:
	find . -type d -name "__pycache__" -exec rm -rf {} + 2>/dev/null || true
	find . -type d -name ".pytest_cache" -exec rm -rf {} + 2>/dev/null || true
