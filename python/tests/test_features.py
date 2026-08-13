import numpy as np
import pytest
from pureedgesim.features import (
    task_to_array,
    node_to_array,
    node_static_to_array,
    state_to_array,
)
from pureedgesim._bridge.protocol import (
    build_nodes_from_episode_init,
    build_state_from_decision_request,
)
from tests.test_types import SAMPLE_EPISODE_INIT, SAMPLE_DECISION_REQUEST


def get_sample_state():
    static = build_nodes_from_episode_init(SAMPLE_EPISODE_INIT)
    task, state = build_state_from_decision_request(SAMPLE_DECISION_REQUEST, static)
    return task, state, static


def test_task_to_array_shape_and_values():
    task, _, _ = get_sample_state()
    arr = task_to_array(task)
    assert arr.dtype == np.float32
    assert arr.shape == (9,)
    assert np.all(np.isfinite(arr))
    # Verify task values mapped
    assert arr[0] == float(task.length_mi)
    assert arr[3] == float(task.container_size_mb)


def test_task_to_array_none_returns_zeros():
    arr = task_to_array(None)
    assert arr.dtype == np.float32
    assert arr.shape == (9,)
    assert np.all(arr == 0.0)


def test_node_to_array_shape_and_values():
    _, state, _ = get_sample_state()
    arr = node_to_array(state.nodes[0])
    assert arr.dtype == np.float32
    assert arr.shape == (9,)
    assert np.all(np.isfinite(arr))


def test_node_to_array_none_returns_zeros():
    arr = node_to_array(None)
    assert arr.dtype == np.float32
    assert arr.shape == (9,)
    assert np.all(arr == 0.0)


def test_node_static_to_array_shape_and_values():
    _, state, _ = get_sample_state()
    arr = node_static_to_array(state.nodes[0])
    assert arr.dtype == np.float32
    assert arr.shape == (11,)
    assert np.all(np.isfinite(arr))


def test_node_static_to_array_none_returns_zeros():
    arr = node_static_to_array(None)
    assert arr.dtype == np.float32
    assert arr.shape == (11,)
    assert np.all(arr == 0.0)


def test_state_to_array_shape():
    _, state, _ = get_sample_state()
    max_pending = 5
    arr = state_to_array(state, include_pending=True, max_pending=max_pending)
    assert arr.dtype == np.float32
    expected_len = 3 + len(state.nodes) * 9 + max_pending * 10
    assert arr.shape == (expected_len,)
    assert np.all(np.isfinite(arr))


def test_state_to_array_none_returns_zeros():
    arr = state_to_array(None)
    assert arr.dtype == np.float32
    assert arr.shape == (3,)
    assert np.all(arr == 0.0)


def test_ram_and_storage_fractions_bounded():
    _, state, _ = get_sample_state()
    for node in state.nodes:
        arr = node_to_array(node)
        ram_frac = arr[2]
        storage_frac = arr[3]
        assert 0.0 <= ram_frac <= 1.0 + 1e-6
        assert 0.0 <= storage_frac <= 1.0 + 1e-6
