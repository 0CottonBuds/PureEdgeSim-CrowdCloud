import pytest
import warnings
from unittest.mock import MagicMock
from pureedgesim._bridge.dispatcher import Dispatcher
from pureedgesim.types.decision import InvalidDecisionError
from pureedgesim.types.node import Node, NodeType, Location


def make_node(index, alive=True, ram=1000.0, storage=5000.0):
    return Node(
        index=index,
        id=index,
        type=NodeType.EDGE_SERVER,
        total_mips=1000.0,
        mips_per_core=500.0,
        num_cores=2,
        total_ram_mb=2048.0,
        total_storage_mb=10240.0,
        is_peripheral=True,
        base_location=Location(0, 0),
        available_ram_mb=ram,
        available_storage_mb=storage,
        cpu_utilization=0.3,
        avg_cpu_utilization=0.3,
        is_idle=False,
        is_alive=alive,
        queued_tasks=1,
        current_location=Location(0, 0),
    )


def make_dispatcher(orch, strict=True):
    conn = MagicMock()
    return Dispatcher(conn, orch, strict=strict, heartbeat_interval=0.0)


def make_task(ram=50.0, storage=100.0):
    t = MagicMock()
    t.ram_required_mb = ram
    t.container_size_mb = storage
    return t


def make_state(nodes):
    from pureedgesim.types.state import SimulationState
    from pureedgesim.types.network import NetworkState
    return SimulationState(
        clock=0.0,
        nodes=nodes,
        pending_tasks=[],
        tasks_in_flight=0,
        network=NetworkState(wan_uplink_utilization=0.0),
    )


def test_none_return_is_not_an_error():
    class NullOrch:
        def select_node(self, t, s):
            return None

    d = make_dispatcher(NullOrch())
    task = make_task()
    state = make_state([make_node(0)])
    idx = d._validate_decision(None, task, state)
    assert idx == -1


def test_minus_one_return_is_valid():
    d = make_dispatcher(MagicMock())
    task = make_task()
    state = make_state([make_node(0)])
    idx = d._validate_decision(-1, task, state)
    assert idx == -1


def test_stale_node_raises_strict():
    d = make_dispatcher(MagicMock(), strict=True)
    task = make_task()
    state = make_state([make_node(0)])
    with pytest.raises(InvalidDecisionError, match="not in the candidate list"):
        d._validate_decision(make_node(999), task, state)


def test_dead_node_raises_strict():
    dead = make_node(0, alive=False)
    d = make_dispatcher(MagicMock())
    with pytest.raises(InvalidDecisionError, match="dead"):
        d._validate_decision(dead, make_task(), make_state([dead]))


def test_insufficient_ram_raises():
    stingy = make_node(0, ram=10.0)
    d = make_dispatcher(MagicMock())
    with pytest.raises(InvalidDecisionError, match="RAM"):
        d._validate_decision(stingy, make_task(ram=500.0), make_state([stingy]))


def test_insufficient_storage_raises():
    stingy = make_node(0, storage=10.0)
    d = make_dispatcher(MagicMock())
    with pytest.raises(InvalidDecisionError, match="storage"):
        d._validate_decision(stingy, make_task(storage=500.0), make_state([stingy]))


def test_lenient_mode_sends_minus_one():
    class BadOrch:
        def select_node(self, t, s):
            return make_node(999)

    conn = MagicMock()
    d = Dispatcher(conn, BadOrch(), strict=False, heartbeat_interval=0.0)
    d._nodes = [make_node(0)]

    with warnings.catch_warnings(record=True) as w:
        warnings.simplefilter("always")
        task = make_task()
        state = make_state([make_node(0)])
        try:
            res = BadOrch().select_node(task, state)
            idx = d._validate_decision(res, task, state)
        except Exception as e:
            if not d._strict:
                warnings.warn(f"Invalid decision (lenient mode): {e}")
                idx = -1
            else:
                raise
    assert idx == -1
    assert len(w) > 0
    assert "Invalid decision" in str(w[0].message)


def test_protocol_version_mismatch_warning():
    conn = MagicMock()
    d = Dispatcher(conn, MagicMock())
    with warnings.catch_warnings(record=True) as w:
        warnings.simplefilter("always")
        d._handle_episode_init({'protocol_version': '99.0', 'episode_id': 1, 'nodes': []})
        assert len(w) > 0
        assert "Protocol version mismatch" in str(w[0].message)


def test_nan_ram_raises():
    nan_node = make_node(0, ram=float('nan'))
    d = make_dispatcher(MagicMock())
    with pytest.raises(InvalidDecisionError, match="NaN"):
        d._validate_decision(nan_node, make_task(ram=50.0), make_state([nan_node]))


def test_nan_storage_raises():
    nan_node = make_node(0, storage=float('nan'))
    d = make_dispatcher(MagicMock())
    with pytest.raises(InvalidDecisionError, match="NaN"):
        d._validate_decision(nan_node, make_task(storage=50.0), make_state([nan_node]))


def test_payload_length_too_large_raises():
    from pureedgesim._bridge.connection import Connection
    conn = MagicMock()
    # Mock header with 100MB length (100_000_000 bytes > 64MB limit)
    import struct
    header = struct.pack('>I', 100_000_000)
    conn._recv_exactly = MagicMock(return_value=header)
    with pytest.raises(ValueError, match="exceeds 64MB limit"):
        Connection.recv(conn)


def test_strict_mode_episode_begin_raises():
    class FailingOrch:
        def on_episode_begin(self, ctx, nodes):
            raise RuntimeError("Episode initialization failure")

    conn = MagicMock()
    d = Dispatcher(conn, FailingOrch(), strict=True)
    with pytest.raises(RuntimeError, match="Episode initialization failure"):
        d._handle_episode_init({'episode_id': 1, 'nodes': []})

