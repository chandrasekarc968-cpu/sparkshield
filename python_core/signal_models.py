"""SparkShield Deterministic Signal Generators and Feature Extractor.

This module provides:
  1. Deterministic, seedable synthetic signal generators for:
     - NORMAL (50/60 Hz AC grid fundamental + odd harmonics, nominal peak)
     - EMP (Analytic short high-amplitude transient, ultrafast rise, µs decay, broad RF bins)
     - OPTICAL (Slow saturation toward optical sensor rail, sustained high optical mV)
     - SURGE (Medium-amplitude damped sinusoid, millisecond-scale decay, low-medium freq bins)
  2. Safe clamping and explicit unit definitions:
     - rise_time_code: 10 ns per LSB
     - decay_time_us: 1 µs per LSB
     - optical saturation protected against rise-time overflow
  3. FeatureExtractor:
     - Decodes frame
     - Extracts 16 normalized features per frame (sensor values, log scales, spectral ratios, FFT bins)
     - Maintains an 8-frame sliding window (8 x 16 = 128 floats)
     - Produces model input tensor with exact shape (1, 1, 128)
"""

from collections import deque
from enum import Enum, unique
from typing import Dict, List, Optional, Tuple, Union

import numpy as np

from python_core.frame_protocol import (
    FLAG_EMP,
    FLAG_NORMAL,
    FLAG_OPTICAL,
    FLAG_SURGE,
    TelemetryFrame,
    pack_frame,
)


@unique
class SignalClass(Enum):
    NORMAL = 0
    EMP = 1
    OPTICAL = 2
    SURGE = 3

    @property
    def flag(self) -> int:
        if self == SignalClass.EMP:
            return FLAG_EMP
        elif self == SignalClass.OPTICAL:
            return FLAG_OPTICAL
        elif self == SignalClass.SURGE:
            return FLAG_SURGE
        elif self == SignalClass.NORMAL:
            return FLAG_NORMAL
        raise ValueError(f"Unknown signal class: {self}")


class SignalGenerator:
    """Deterministic, seedable generator for synthetic smart-meter telemetry."""

    def __init__(self, seed: Optional[int] = 42, grid_freq_hz: float = 50.0):
        self.rng = np.random.default_rng(seed)
        self.grid_freq_hz = grid_freq_hz
        self.current_seq = 0
        self.start_timestamp_ms = 1_700_000_000_000  # Epoch offset

    def reset_seed(self, seed: int):
        """Resets internal RNG seed for exact reproducible generation."""
        self.rng = np.random.default_rng(seed)

    def generate_normal(
        self,
        seq: Optional[int] = None,
        t_ms: Optional[int] = None,
    ) -> Tuple[TelemetryFrame, np.ndarray]:
        """Generates benign 50/60 Hz grid frame with low noise and odd harmonics.

        Returns:
            Tuple of (TelemetryFrame, synthetic_waveform_samples).
        """
        if seq is None:
            seq = self.current_seq
            self.current_seq = (self.current_seq + 1) & 0xFFFFFFFF
        if t_ms is None:
            t_ms = int(seq * 100)  # 10 Hz telemetry default

        # Normal AC grid parameters (325V peak nominal scaled to ~3250 mV ADC level)
        voltage_variation = float(self.rng.uniform(-100.0, 100.0))
        peak_mv = int(np.clip(3250.0 + voltage_variation, 2800.0, 3600.0))

        # AC quarter-cycle front equivalent (~5 ms = 5,000 µs = 500,000 ns)
        # Clamped to uint16 max for rise_time_code (10 ns/LSB -> 50,000 = 500 µs)
        rise_time_code = int(np.clip(50000 + self.rng.integers(-500, 500), 1000, 65535))
        decay_time_us = int(np.clip(5000 + self.rng.integers(-100, 100), 1000, 65535))

        # Optical sensor baseline ambient noise (100 - 250 mV)
        optical_sensor_mv = int(np.clip(150.0 + self.rng.uniform(-30.0, 30.0), 50.0, 400.0))

        # FFT bins: Fundamental dominant in Bin 0 (50/60Hz), small 3rd/5th harmonics in Bin 1
        fft_bins = bytes([
            int(np.clip(220 + self.rng.integers(-10, 10), 180, 255)),  # Bin 0: Fundamental
            int(np.clip(35 + self.rng.integers(-5, 5), 10, 60)),       # Bin 1: 3rd Harmonic
            int(np.clip(12 + self.rng.integers(-3, 3), 2, 25)),        # Bin 2: 5th Harmonic
            int(np.clip(4 + self.rng.integers(-1, 2), 0, 10)),         # Bin 3
            int(np.clip(2 + self.rng.integers(-1, 1), 0, 6)),          # Bin 4
            1,                                                         # Bin 5
            0,                                                         # Bin 6
            0,                                                         # Bin 7
        ])

        # Generate 128 analytic waveform samples for visual history
        t = np.linspace(0, 0.02, 128)  # 20 ms (one grid cycle)
        waveform = (
            peak_mv * np.sin(2 * np.pi * self.grid_freq_hz * t)
            + (peak_mv * 0.05) * np.sin(2 * np.pi * 3 * self.grid_freq_hz * t)
            + self.rng.normal(0, 15.0, 128)
        )

        frame = TelemetryFrame(
            sequence_id=seq,
            timestamp_ms=t_ms,
            event_flags=FLAG_NORMAL,
            peak_mv=peak_mv,
            rise_time_code=rise_time_code,
            decay_time_us=decay_time_us,
            optical_sensor_mv=optical_sensor_mv,
            fft_energy_bins=fft_bins,
        )
        return frame, waveform

    def generate_emp(
        self,
        seq: Optional[int] = None,
        t_ms: Optional[int] = None,
    ) -> Tuple[TelemetryFrame, np.ndarray]:
        """Generates safe synthetic EMP-like transient frame.

        Analytic feature model:
          - Ultrafast rise time: 10 - 30 ns -> rise_time_code = 1 to 3
          - Microsecond-scale decay: 1 - 15 µs -> decay_time_us = 1 to 15
          - High amplitude peak: 35,000 - 65,000 mV (clamped to uint16)
          - Broad high-frequency spectral energy across bins 3 through 7
          - Optical sensor normal baseline

        Note: Purely analytic software simulation. No RF or HV output.
        """
        if seq is None:
            seq = self.current_seq
            self.current_seq = (self.current_seq + 1) & 0xFFFFFFFF
        if t_ms is None:
            t_ms = int(seq * 100)

        peak_mv = int(np.clip(self.rng.uniform(38000.0, 65000.0), 20000.0, 65535.0))
        # 10 ns per LSB -> 10 to 30 ns = code 1 to 3
        rise_time_code = int(self.rng.integers(1, 4))
        # 1 µs per LSB -> 1 to 15 µs
        decay_time_us = int(self.rng.integers(1, 16))
        optical_sensor_mv = int(np.clip(160.0 + self.rng.uniform(-30.0, 30.0), 50.0, 400.0))

        # FFT bins: Broad spectral distribution, high RF energy in bins 3..7
        fft_bins = bytes([
            int(self.rng.integers(110, 150)),  # Bin 0
            int(self.rng.integers(140, 190)),  # Bin 1
            int(self.rng.integers(170, 220)),  # Bin 2
            int(self.rng.integers(200, 255)),  # Bin 3 (10-15 MHz equivalent)
            int(self.rng.integers(210, 255)),  # Bin 4 (15-25 MHz equivalent)
            int(self.rng.integers(190, 245)),  # Bin 5 (25-35 MHz equivalent)
            int(self.rng.integers(170, 230)),  # Bin 6 (35-45 MHz equivalent)
            int(self.rng.integers(140, 210)),  # Bin 7
        ])

        # Synthetic analytic pulse waveform (double exponential pulse)
        t = np.linspace(0, 50e-6, 128)  # 50 µs window
        alpha = 1.0 / (decay_time_us * 1e-6)
        beta = 1.0 / (max(1, rise_time_code * 10) * 1e-9)
        waveform = peak_mv * (np.exp(-alpha * t) - np.exp(-beta * t))

        frame = TelemetryFrame(
            sequence_id=seq,
            timestamp_ms=t_ms,
            event_flags=FLAG_EMP,
            peak_mv=peak_mv,
            rise_time_code=rise_time_code,
            decay_time_us=decay_time_us,
            optical_sensor_mv=optical_sensor_mv,
            fft_energy_bins=fft_bins,
        )
        return frame, waveform

    def generate_optical(
        self,
        seq: Optional[int] = None,
        t_ms: Optional[int] = None,
    ) -> Tuple[TelemetryFrame, np.ndarray]:
        """Generates optical saturation tamper frame.

        Analytic model:
          - High optical sensor value approaching rail (3,500 - 5,000 mV)
          - Moderate electrical peak voltage (normal AC grid)
          - Slow saturation front, safe clamp to prevent rise_time overflow
          - Low-frequency spectral energy (bin 0 dominant)
        """
        if seq is None:
            seq = self.current_seq
            self.current_seq = (self.current_seq + 1) & 0xFFFFFFFF
        if t_ms is None:
            t_ms = int(seq * 100)

        # Normal AC electrical peak
        peak_mv = int(np.clip(3250.0 + self.rng.uniform(-100.0, 100.0), 2800.0, 3600.0))

        # Optical sensor saturated near rail (3.3V / 5.0V sensor scale)
        optical_sensor_mv = int(np.clip(self.rng.uniform(3800.0, 4950.0), 3200.0, 5000.0))

        # Optical saturation front: clamped to 20,000 - 45,000 (200 - 450 µs)
        rise_time_code = int(np.clip(self.rng.integers(20000, 45000), 0, 65535))
        # Long sustained decay time (30,000 - 60,000 µs)
        decay_time_us = int(np.clip(self.rng.integers(30000, 60000), 0, 65535))

        # Low-frequency spectral concentration (ambient + optical DC component)
        fft_bins = bytes([
            int(np.clip(240 + self.rng.integers(-10, 10), 200, 255)),  # Bin 0
            int(np.clip(30 + self.rng.integers(-5, 5), 10, 50)),       # Bin 1
            int(np.clip(10 + self.rng.integers(-3, 3), 2, 20)),        # Bin 2
            4, 2, 1, 0, 0,
        ])

        # Waveform shows normal grid AC overlaid with high optical DC bias
        t = np.linspace(0, 0.02, 128)
        waveform = peak_mv * np.sin(2 * np.pi * self.grid_freq_hz * t) + self.rng.normal(0, 15.0, 128)

        frame = TelemetryFrame(
            sequence_id=seq,
            timestamp_ms=t_ms,
            event_flags=FLAG_OPTICAL,
            peak_mv=peak_mv,
            rise_time_code=rise_time_code,
            decay_time_us=decay_time_us,
            optical_sensor_mv=optical_sensor_mv,
            fft_energy_bins=fft_bins,
        )
        return frame, waveform

    def generate_surge(
        self,
        seq: Optional[int] = None,
        t_ms: Optional[int] = None,
    ) -> Tuple[TelemetryFrame, np.ndarray]:
        """Generates inductive switching surge frame.

        Analytic model:
          - Medium-high peak voltage (7,000 - 18,000 mV)
          - Damped oscillation, millisecond-scale decay (500 - 5,000 µs)
          - Low-medium frequency spectral concentration (bins 1 & 2 prominent)
          - Normal baseline optical sensor value
        """
        if seq is None:
            seq = self.current_seq
            self.current_seq = (self.current_seq + 1) & 0xFFFFFFFF
        if t_ms is None:
            t_ms = int(seq * 100)

        peak_mv = int(np.clip(self.rng.uniform(7500.0, 18500.0), 6000.0, 25000.0))
        # Rise time ~10 to 50 µs -> 1,000 to 5,000 (10 ns/LSB)
        rise_time_code = int(self.rng.integers(1000, 5001))
        # Millisecond-scale decay (500 to 5,000 µs)
        decay_time_us = int(self.rng.integers(500, 5001))
        optical_sensor_mv = int(np.clip(160.0 + self.rng.uniform(-30.0, 30.0), 50.0, 400.0))

        # FFT bins: Dominant energy in bins 1 and 2 (damped oscillation ring 1-5 kHz)
        fft_bins = bytes([
            int(self.rng.integers(140, 190)),  # Bin 0
            int(self.rng.integers(210, 255)),  # Bin 1: Ring frequency
            int(self.rng.integers(180, 230)),  # Bin 2: Damping envelope
            int(self.rng.integers(60, 110)),   # Bin 3
            int(self.rng.integers(20, 50)),    # Bin 4
            int(self.rng.integers(5, 20)),     # Bin 5
            2, 0,
        ])

        # Damped oscillatory surge waveform
        t = np.linspace(0, 0.005, 128)  # 5 ms window
        decay_rate = 1.0 / (decay_time_us * 1e-6)
        ring_freq = 2500.0  # 2.5 kHz resonant ring
        waveform = peak_mv * np.exp(-decay_rate * t) * np.sin(2 * np.pi * ring_freq * t)

        frame = TelemetryFrame(
            sequence_id=seq,
            timestamp_ms=t_ms,
            event_flags=FLAG_SURGE,
            peak_mv=peak_mv,
            rise_time_code=rise_time_code,
            decay_time_us=decay_time_us,
            optical_sensor_mv=optical_sensor_mv,
            fft_energy_bins=fft_bins,
        )
        return frame, waveform

    def generate(
        self,
        signal_class: SignalClass,
        seq: Optional[int] = None,
        t_ms: Optional[int] = None,
    ) -> Tuple[TelemetryFrame, np.ndarray]:
        """Generate frame and waveform for any requested class."""
        if signal_class == SignalClass.NORMAL:
            return self.generate_normal(seq, t_ms)
        elif signal_class == SignalClass.EMP:
            return self.generate_emp(seq, t_ms)
        elif signal_class == SignalClass.OPTICAL:
            return self.generate_optical(seq, t_ms)
        elif signal_class == SignalClass.SURGE:
            return self.generate_surge(seq, t_ms)
        raise ValueError(f"Unknown signal class {signal_class}")


class FeatureExtractor:
    """Extracts normalized feature vectors and maintains a 128-float sliding window.

    Feature Vector per frame (16 float values, normalized [0.0, 1.0]):
      0: peak_mv / 65535.0 (Linear peak voltage)
      1: rise_time_code / 65535.0 (Linear rise time)
      2: log1p(rise_time_code) / log1p(65535) (Log rise time for nanosecond discrimination)
      3: decay_time_us / 65535.0 (Linear decay time)
      4: log1p(decay_time_us) / log1p(65535) (Log decay time for µs vs ms discrimination)
      5: optical_sensor_mv / 65535.0 (Linear optical sensor)
      6: min(optical_sensor_mv / 5000.0, 1.0) (Optical saturation rail proximity)
      7: hf_energy_ratio: sum(bins[4:8]) / (sum(bins) + 1e-5) (High-frequency RF energy proportion)
      8..15: bin_0/255.0 through bin_7/255.0 (8 normalized FFT energy bins)

    Sliding Window:
      Buffer of 8 frames x 16 features = 128 float values.
      Tensor shape: exactly (1, 1, 128) for 1D CNN input.
    """

    NUM_FEATURES_PER_FRAME = 16
    WINDOW_FRAME_COUNT = 8
    TOTAL_FEATURES = NUM_FEATURES_PER_FRAME * WINDOW_FRAME_COUNT  # 128

    def __init__(self):
        self._history: deque = deque(maxlen=self.WINDOW_FRAME_COUNT)
        self._init_baseline()

    def _init_baseline(self):
        """Pre-fills history with benign normal baseline vectors so shape is always (1, 1, 128)."""
        baseline_frame = TelemetryFrame(
            sequence_id=0,
            timestamp_ms=0,
            event_flags=FLAG_NORMAL,
            peak_mv=3250,
            rise_time_code=50000,
            decay_time_us=5000,
            optical_sensor_mv=150,
            fft_energy_bins=bytes([220, 35, 12, 4, 2, 1, 0, 0]),
        )
        baseline_vec = self.extract_frame_features(baseline_frame)
        self._history.clear()
        for _ in range(self.WINDOW_FRAME_COUNT):
            self._history.append(baseline_vec.copy())

    def extract_frame_features(self, frame: TelemetryFrame) -> np.ndarray:
        """Transforms a single TelemetryFrame into a 16-element normalized feature vector."""
        # 1. Linear normalized scalar fields
        norm_peak = float(np.clip(frame.peak_mv / 65535.0, 0.0, 1.0))
        norm_rise = float(np.clip(frame.rise_time_code / 65535.0, 0.0, 1.0))
        log_rise = float(np.log1p(frame.rise_time_code) / np.log1p(65535.0))
        norm_decay = float(np.clip(frame.decay_time_us / 65535.0, 0.0, 1.0))
        log_decay = float(np.log1p(frame.decay_time_us) / np.log1p(65535.0))
        norm_opt = float(np.clip(frame.optical_sensor_mv / 65535.0, 0.0, 1.0))
        opt_rail = float(np.clip(frame.optical_sensor_mv / 5000.0, 0.0, 1.0))

        # 2. Normalized FFT bins
        bins_arr = np.frombuffer(frame.fft_energy_bins, dtype=np.uint8).astype(np.float32)
        norm_bins = bins_arr / 255.0

        # 3. High-frequency energy ratio (bins 4..7 vs total energy)
        total_energy = float(np.sum(norm_bins))
        hf_energy = float(np.sum(norm_bins[4:]))
        hf_ratio = float(hf_energy / (total_energy + 1e-5))

        vec = np.empty(self.NUM_FEATURES_PER_FRAME, dtype=np.float32)
        vec[0] = norm_peak
        vec[1] = norm_rise
        vec[2] = log_rise
        vec[3] = norm_decay
        vec[4] = log_decay
        vec[5] = norm_opt
        vec[6] = opt_rail
        vec[7] = hf_ratio
        vec[8:16] = norm_bins

        return vec

    def update(self, frame: TelemetryFrame) -> np.ndarray:
        """Pushes new frame into history and returns (1, 1, 128) model tensor.

        Returns:
            np.ndarray of shape (1, 1, 128) with dtype float32.
        """
        vec = self.extract_frame_features(frame)
        self._history.append(vec)

        # Concatenate 8 historical vectors: (8, 16) -> (128,)
        window = np.concatenate(list(self._history), axis=0)
        return window.reshape(1, 1, self.TOTAL_FEATURES).astype(np.float32)

    def reset(self):
        """Resets sliding window to normal baseline."""
        self._init_baseline()
