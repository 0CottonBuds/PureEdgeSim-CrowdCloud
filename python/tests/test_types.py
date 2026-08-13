"""
Unit tests for PureEdgeSim Python SDK data types and protocol factory functions.
"""

from pureedgesim.types.node import Node, NodeType, Location
from pureedgesim.types.task import Task, TaskStatus, TaskOutcome, FailureReason
from pureedgesim.types.state import SimulationState
from pureedgesim.types.network import NetworkState
from pureedgesim._bridge.protocol import (
    build_nodes_from_episode_init,
    build_state_from_decision_request,
)

SAMPLE_EPISODE_INIT = {
    'type': 'EPISODE_INIT',
    'episode_id': 0,
    'nodes': [
        {'node_index': 0, 'node_id': 1, 'node_type': 'CLOUD',
         'total_mips': 10000.0, 'mips_per_core': 5000.0, 'num_cores': 2,
         'total_ram_mb': 8192.0, 'total_storage_mb': 102400.0,
         'is_peripheral': False, 'base_location_x': 0.0, 'base_location_y': 0.0},
        {'node_index': 1, 'node_id': 2, 'node_type': 'EDGE_DATACENTER',
         'total_mips': 2000.0, 'mips_per_core': 1000.0, 'num_cores': 2,
         'total_ram_mb': 2048.0, 'total_storage_mb': 20480.0,
         'is_peripheral': True, 'base_location_x': 100.0, 'base_location_y': 200.0},
    ],
}

SAMPLE_DECISION_REQUEST = {
    'type': 'DECISION_REQUEST',
    'request_id': 5,
    'sim_clock_s': 10.0,
    'current_task': {
        'task_id': 42, 'length_mips': 500.0, 'task_file_size_bits': 8_000_000,
        'task_output_bits': 1_000_000, 'task_container_mb': 50.0,
        'max_latency_s': 2.0, 'application_id': 0, 'task_type': 'AUGMENTED_REALITY',
        'edge_device_index': -1, 'edge_device_id': 10,
        'current_location_x': 50.0, 'current_location_y': 100.0,
    },
    'node_states': [
        {'node_id': 1, 'available_ram_mb': 6000.0, 'available_storage_mb': 80000.0,
         'current_cpu_pct': 0.2, 'avg_cpu_pct': 0.3, 'is_idle': False, 'is_dead': False,
         'queue_length': 2, 'current_location_x': 0.0, 'current_location_y': 0.0},
        {'node_id': 2, 'available_ram_mb': 1500.0, 'available_storage_mb': 15000.0,
         'current_cpu_pct': 0.6, 'avg_cpu_pct': 0.5, 'is_idle': False, 'is_dead': False,
         'queue_length': 5, 'current_location_x': 100.0, 'current_location_y': 200.0},
    ],
    'pending_tasks': [],
    'wan_uplink_utilization': 0.1,
    'tasks_in_flight': 3,
}


def test_build_nodes():
    nodes = build_nodes_from_episode_init(SAMPLE_EPISODE_INIT)
    assert len(nodes) == 2
    assert nodes[0].type == NodeType.CLOUD
    assert nodes[1].type == NodeType.EDGE_SERVER
    assert nodes[1].base_location.x == 100.0


def test_build_state():
    static_nodes = build_nodes_from_episode_init(SAMPLE_EPISODE_INIT)
    task, state = build_state_from_decision_request(SAMPLE_DECISION_REQUEST, static_nodes)
    assert task.id == 42
    assert abs(task.input_size_mb - 1.0) < 1e-6  # 8_000_000 bits / 8 / 1e6 = 1.0 MB
    assert len(state.nodes) == 2
    assert state.nodes[0].available_ram_mb == 6000.0
    assert state.clock == 10.0


def test_candidates_for():
    static_nodes = build_nodes_from_episode_init(SAMPLE_EPISODE_INIT)
    task, state = build_state_from_decision_request(SAMPLE_DECISION_REQUEST, static_nodes)
    candidates = state.candidates_for(task)
    assert len(candidates) == 2
    assert all(n.is_alive for n in candidates)
    assert all(n.available_ram_mb >= task.ram_required_mb for n in candidates)


def test_node_properties():
    nodes = build_nodes_from_episode_init(SAMPLE_EPISODE_INIT)
    assert nodes[0].is_cloud
    assert not nodes[0].is_edge_server
    assert nodes[1].is_peripheral


def test_location_distance():
    loc1 = Location(0.0, 0.0)
    loc2 = Location(3.0, 4.0)
    assert abs(loc1.distance_to(loc2) - 5.0) < 1e-6
