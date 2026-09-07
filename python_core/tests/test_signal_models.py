"""Automated tests for deterministic signal generators."""

import numpy as np
import pytest
from python_core.frame_protocol import (
    FLAG_EMP,
    FLAG_NORMAL,
    FLAG_OPTICAL,
    FLAG_SURGE,
)
from python_core.signal_models import SignalClass, SignalGenerator


def test_signal_generator_determinism():
    """Identical seeds must generate bit-for-bit identical frames and waveforms."""
    gen1 = SignalGenerator(seed=42)
    gen2 = SignalGenerator(seed=42)

    for sc in [SignalClass.NORMAL, SignalClass.EMP, SignalClass.OPTICAL, SignalClass.SURGE]:
        f1, w1 = gen1.generate(sc, seq=100)
        f2, w2 = gen2.generate(sc, seq=100)

        assert f1.sequence_id == f2.sequence_id
        assert f1.peak_mv == f2.peak_mv
        assert f1.rise_time_code == f2.rise_time_code
        assert f1.decay_time_us == f2.decay_time_us
        assert f1.optical_sensor_mv == f2.optical_sensor_mv
        assert f1.fft_energy_bins == f2.fft_energy_bins
        assert np.array_equal(w1, w2)


def test_normal_grid_signal_characteristics():
    """NORMAL class produces 50/60 Hz benign grid parameters."""
    gen = SignalGenerator(seed=101)
    frame, waveform = gen.generate_normal(seq=1)

    assert frame.event_flags == FLAG_NORMAL
    assert frame.is_normal is True
    assert frame.is_emp is False
    assert 2800 <= frame.peak_mv <= 3600  # Nominal AC peak
    assert 1000 <= frame.rise_time_code <= 65535
    assert 1000 <= frame.decay_time_us <= 65535
    assert 50 <= frame.optical_sensor_mv <= 400  # Baseline ambient
    assert frame.fft_energy_bins[0] > 150  # Bin 0 dominant (fundamental)
    assert len(waveform) == 128


def test_emp_signal_characteristics():
    """EMP class produces short high-amplitude transient with ultrafast rise and RF spectrum."""
    gen = SignalGenerator(seed=202)
    frame, waveform = gen.generate_emp(seq=2)

    assert frame.event_flags == FLAG_EMP
    assert frame.is_emp is True
    assert 20000 <= frame.peak_mv <= 65535  # High voltage transient
    # Rise time must be 10 - 30 ns -> code 1 to 3
    assert 1 <= frame.rise_time_code <= 3
    assert 10 <= frame.rise_time_ns <= 30
    # Decay time microsecond scale: 1 to 15 µs
    assert 1 <= frame.decay_time_us <= 15
    assert frame.decay_time_ms <= 0.015
    # RF energy concentrated in high bins (bins 3-7)
    assert frame.fft_energy_bins[3] > 150
    assert frame.fft_energy_bins[4] > 150
    assert len(waveform) == 128


def test_optical_signal_characteristics():
    """OPTICAL class produces rail-saturated optical sensor and slow front."""
    gen = SignalGenerator(seed=303)
    frame, waveform = gen.generate_optical(seq=3)

    assert frame.event_flags == FLAG_OPTICAL
    assert frame.is_optical is True
    assert 2800 <= frame.peak_mv <= 3600  # Normal grid electrical voltage
    assert 3200 <= frame.optical_sensor_mv <= 5000  # Saturated near rail
    # Rise time slow front, must not overflow uint16
    assert 0 <= frame.rise_time_code <= 65535
    assert 0 <= frame.decay_time_us <= 65535
    # FFT energy is low-frequency dominant (bin 0)
    assert frame.fft_energy_bins[0] > 180
    assert len(waveform) == 128


def test_surge_signal_characteristics():
    """SURGE class produces medium-high peak damped oscillation with millisecond decay."""
    gen = SignalGenerator(seed=404)
    frame, waveform = gen.generate_surge(seq=4)

    assert frame.event_flags == FLAG_SURGE
    assert frame.is_surge is True
    assert 6000 <= frame.peak_mv <= 25000
    # Rise time ~10 to 50 µs -> 1,000 to 5,000
    assert 1000 <= frame.rise_time_code <= 5001
    # Millisecond decay: 500 to 5,000 µs
    assert 500 <= frame.decay_time_us <= 5001
    assert 0.5 <= frame.decay_time_ms <= 5.001
    # Resonant ring energy prominent in bins 1 and 2
    assert frame.fft_energy_bins[1] > 150
    assert frame.fft_energy_bins[2] > 150
    assert len(waveform) == 128


def test_waveform_validity_all_classes():
    """Waveforms must have 128 elements without NaNs or Infs across all classes."""
    gen = SignalGenerator(seed=505)
    for sc in SignalClass:
        _, waveform = gen.generate(sc)
        assert len(waveform) == 128
        assert not np.isnan(waveform).any()
        assert not np.isinf(waveform).any()
