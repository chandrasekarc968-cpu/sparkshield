"""Automated tests for MockStreamer and transport layers."""

import asyncio
from python_core.ble_peripheral import MockLoopbackTransport
from python_core.frame_protocol import (
    FRAME_LENGTH,
    FLAG_EMP,
    FLAG_NORMAL,
    FLAG_OPTICAL,
    FLAG_SURGE,
    unpack_frame,
    validate_frame,
    SequenceTracker,
)
from python_core.mock_stream import MockStreamer
from python_core.signal_models import SignalClass


def test_mock_streamer_deterministic_pacing():
    """Streamer emits requested number of valid 29-byte frames."""
    async def run_test():
        transport = MockLoopbackTransport()
        streamer = MockStreamer(rate_hz=100.0, seed=42, transport=transport)

        frames = []
        tracker = SequenceTracker()

        async for frame, packed, waveform, tensor in streamer.stream_generator(max_frames=20):
            frames.append(frame)
            assert len(packed) == FRAME_LENGTH
            is_valid, err = validate_frame(packed)
            assert is_valid is True, f"Frame invalid: {err}"
            assert tensor.shape == (1, 1, 128)

            ok, dropped = tracker.process_sequence(frame.sequence_id)
            assert ok is True
            assert dropped == 0

        assert len(frames) == 20
        assert tracker.total_received == 20
        assert tracker.total_dropped == 0

    asyncio.run(run_test())


def test_tamper_burst_injection_lifecycle():
    """Tamper burst activates for specified count and then automatically returns to NORMAL."""
    streamer = MockStreamer(rate_hz=200.0, seed=123)

    # Initial frame is NORMAL
    f0, _, _, _ = streamer.next_frame()
    assert f0.is_normal is True

    # Inject EMP for 3 frames
    streamer.trigger_tamper(SignalClass.EMP, burst_count=3)

    f1, _, _, _ = streamer.next_frame()
    f2, _, _, _ = streamer.next_frame()
    f3, _, _, _ = streamer.next_frame()
    assert f1.is_emp is True
    assert f2.is_emp is True
    assert f3.is_emp is True

    # Subsequent frames must revert to NORMAL
    f4, _, _, _ = streamer.next_frame()
    f5, _, _, _ = streamer.next_frame()
    assert f4.is_normal is True
    assert f5.is_normal is True


def test_transport_packet_loss_simulation():
    """MockLoopbackTransport simulates channel loss when packet_loss_ratio is set."""
    async def run_test():
        # 50% packet drop rate
        transport = MockLoopbackTransport(packet_loss_ratio=0.5)
        await transport.start()

        streamer = MockStreamer(rate_hz=500.0, seed=456, transport=transport)
        for _ in range(50):
            _, packed, _, _ = streamer.next_frame()
            await transport.send_frame(packed)

        assert transport.sent_count == 50
        # With 50% loss over 50 packets, some packets should be dropped and some received
        assert transport.dropped_count > 0
        assert not transport.queue.empty()

        await transport.stop()

    asyncio.run(run_test())
