"""Tests for SparkShield Simulation Lab Engine (python_core/simulation_engine.py)."""

import json
import numpy as np
import pytest

from python_core.frame_protocol import (
    FLAG_EMP,
    FLAG_NORMAL,
    FLAG_OPTICAL,
    FLAG_SURGE,
    FRAME_LENGTH,
)
from python_core.simulation_engine import (
    EmpParameters,
    NormalParameters,
    OpticalParameters,
    PRESETS,
    SimulationEngine,
    SimulationMode,
    SurgeParameters,
)


@pytest.fixture
def engine():
    return SimulationEngine()


def test_all_modes_generate_valid_simulation(engine):
    """Verifies that all four simulation modes generate valid results."""
    modes = [
        (SimulationMode.NORMAL, NormalParameters()),
        (SimulationMode.EMP, EmpParameters()),
        (SimulationMode.OPTICAL, OpticalParameters()),
        (SimulationMode.SURGE, SurgeParameters()),
    ]

    for mode, params in modes:
        result = engine.generate_signal(mode, params, seed=42)
        assert result.mode == mode.value
        assert result.expected_class == mode.value
        assert result.frame_length == FRAME_LENGTH == 29
        assert result.crc_valid is True
        assert len(result.fft_energy_bins) == 8
        assert len(result.voltage_samples_mv) == 256
        assert len(result.time_points_us) == 256
        assert 0.0 <= result.confidence <= 1.0
        assert len(result.probabilities) == 4
        assert result.alert_threshold == 0.85


def test_presets_validity_and_execution(engine):
    """Validates that all documented presets load and execute cleanly."""
    for preset_name, (mode, params) in PRESETS.items():
        errors = params.validate()
        assert len(errors) == 0, f"Preset '{preset_name}' has validation errors: {errors}"
        result = engine.generate_signal(mode, params, seed=42)
        assert result.expected_class == mode.value
        assert result.crc_valid is True


def test_same_seed_produces_deterministic_signal(engine):
    """Proves that identical seeds produce bit-for-bit identical outputs."""
    p1 = EmpParameters(seed=12345)
    p2 = EmpParameters(seed=12345)

    res1 = engine.generate_signal(SimulationMode.EMP, p1, seed=12345)
    res2 = engine.generate_signal(SimulationMode.EMP, p2, seed=12345)

    assert res1.hex_frame == res2.hex_frame
    assert res1.crc16 == res2.crc16
    assert np.allclose(res1.voltage_samples_mv, res2.voltage_samples_mv)
    assert res1.features == res2.features


def test_different_seed_produces_different_noise(engine):
    """Proves that different seeds produce distinct synthetic noise patterns."""
    p1 = NormalParameters(seed=101)
    p2 = NormalParameters(seed=999)

    res1 = engine.generate_signal(SimulationMode.NORMAL, p1, seed=101)
    res2 = engine.generate_signal(SimulationMode.NORMAL, p2, seed=999)

    assert not np.array_equal(res1.voltage_samples_mv, res2.voltage_samples_mv)


def test_tamper_alert_gating_behavior(engine):
    """Validates that NORMAL never triggers tamperDetected, while high-confidence attacks do."""
    normal_res = engine.generate_signal(SimulationMode.NORMAL, NormalParameters(), seed=42)
    assert normal_res.tamper_detected is False, "NORMAL should never trigger tamper alert"

    emp_res = engine.generate_signal(SimulationMode.EMP, EmpParameters(), seed=42)
    assert emp_res.observed_class == "EMP"
    assert emp_res.confidence >= 0.85
    assert emp_res.tamper_detected is True, "High-confidence EMP must trigger tamper alert"

    opt_res = engine.generate_signal(SimulationMode.OPTICAL, OpticalParameters(), seed=42)
    assert opt_res.tamper_detected is True

    surge_res = engine.generate_signal(SimulationMode.SURGE, SurgeParameters(), seed=42)
    assert surge_res.tamper_detected is True


def test_event_flags_mapping(engine):
    """Confirms exact 1-to-1 event flag bitmask mapping in the 29-byte frame."""
    norm_res = engine.generate_signal(SimulationMode.NORMAL, NormalParameters())
    assert norm_res.event_flags == FLAG_NORMAL

    emp_res = engine.generate_signal(SimulationMode.EMP, EmpParameters())
    assert emp_res.event_flags == FLAG_EMP

    opt_res = engine.generate_signal(SimulationMode.OPTICAL, OpticalParameters())
    assert opt_res.event_flags == FLAG_OPTICAL

    surge_res = engine.generate_signal(SimulationMode.SURGE, SurgeParameters())
    assert surge_res.event_flags == FLAG_SURGE


def test_export_json_and_csv(engine):
    """Verifies that JSON and CSV exports contain correct schemas and parse cleanly."""
    res = engine.generate_signal(SimulationMode.EMP, EmpParameters(), seed=42)

    # JSON export
    json_str = engine.export_json(res)
    parsed = json.loads(json_str)
    assert parsed["mode"] == "EMP"
    assert parsed["hex_frame"] == res.hex_frame
    assert "voltage_samples_mv" in parsed
    assert "SAFETY BOUNDARY" in parsed["safety_notice"] or "SOFTWARE SIMULATION" in parsed["safety_notice"]

    # CSV export
    csv_str = engine.export_csv(res)
    lines = csv_str.strip().split("\n")
    assert lines[0].startswith("# SparkShield Simulation Lab Export")
    assert "time_us,voltage_mv" in lines
    header_idx = lines.index("time_us,voltage_mv")
    data_lines = lines[header_idx + 1 :]
    assert len(data_lines) == len(res.time_points_us)


def test_signal_comparison(engine):
    """Verifies signal comparison between baseline NORMAL and attack."""
    base_res = engine.generate_signal(SimulationMode.NORMAL, NormalParameters(), seed=42)
    emp_res = engine.generate_signal(SimulationMode.EMP, EmpParameters(), seed=42)

    comp = engine.compare_signals(base_res, emp_res)
    assert comp["baseline_class"] == "NORMAL"
    assert comp["attack_class"] == "EMP"
    assert comp["delta_peak_mv"] > 10000.0  # EMP peak is significantly higher
    assert comp["delta_rise_ns"] < 0.0      # EMP rise is much faster (smaller nanoseconds)
    assert comp["spectral_shift"] == "High-Frequency RF"
