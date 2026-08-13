"""
Feature vectorization utilities for converting Task, Node, and SimulationState to NumPy arrays.
"""

from __future__ import annotations
import numpy as np

from pureedgesim.types.node import Node, NodeType
from pureedgesim.types.task import Task
from pureedgesim.types.state import SimulationState


def task_to_array(task: Task) -> np.ndarray:
    """
    Vectorize a Task object into a 1D float32 NumPy array.

    Fields (9):
        [length_mi, input_size_mb, output_size_mb, container_size_mb,
         deadline, app_id, origin_location_x, origin_location_y,
         origin_cpu_utilization]
    """
    if task is None:
        return np.zeros(9, dtype=np.float32)

    return np.array([
        float(task.length_mi),
        float(task.input_size_mb),
        float(task.output_size_mb),
        float(task.container_size_mb),
        float(task.deadline),
        float(task.app_id),
        float(task.origin_location.x) if task.origin_location else 0.0,
        float(task.origin_location.y) if task.origin_location else 0.0,
        float(task.origin_cpu_utilization),
    ], dtype=np.float32)


def node_to_array(node: Node) -> np.ndarray:
    """
    Vectorize a Node's dynamic state into a 1D float32 NumPy array.

    Fields (9):
        [cpu_utilization, avg_cpu_utilization, available_ram_fraction,
         available_storage_fraction, is_idle, is_alive, queued_tasks,
         current_location_x, current_location_y]
    """
    if node is None:
        return np.zeros(9, dtype=np.float32)

    ram_frac = node.available_ram_mb / max(1.0, node.total_ram_mb)
    storage_frac = node.available_storage_mb / max(1.0, node.total_storage_mb)

    return np.array([
        float(node.cpu_utilization),
        float(node.avg_cpu_utilization),
        float(ram_frac),
        float(storage_frac),
        1.0 if node.is_idle else 0.0,
        1.0 if node.is_alive else 0.0,
        float(node.queued_tasks),
        float(node.current_location.x) if node.current_location else 0.0,
        float(node.current_location.y) if node.current_location else 0.0,
    ], dtype=np.float32)


def node_static_to_array(node: Node) -> np.ndarray:
    """
    Vectorize a Node's static properties into a 1D float32 NumPy array.

    Fields (11):
        [total_mips, mips_per_core, num_cores, total_ram_mb,
         total_storage_mb, is_cloud, is_edge_server, is_edge_device,
         is_peripheral, base_location_x, base_location_y]
    """
    if node is None:
        return np.zeros(11, dtype=np.float32)

    return np.array([
        float(node.total_mips),
        float(node.mips_per_core),
        float(node.num_cores),
        float(node.total_ram_mb),
        float(node.total_storage_mb),
        1.0 if node.type == NodeType.CLOUD else 0.0,
        1.0 if node.type == NodeType.EDGE_SERVER else 0.0,
        1.0 if node.type == NodeType.EDGE_DEVICE else 0.0,
        1.0 if node.is_peripheral else 0.0,
        float(node.base_location.x) if node.base_location else 0.0,
        float(node.base_location.y) if node.base_location else 0.0,
    ], dtype=np.float32)


def state_to_array(
    state: SimulationState,
    include_pending: bool = True,
    max_pending: int = 10,
) -> np.ndarray:
    """
    Flatten SimulationState into a 1D float32 NumPy array.

    Layout:
        Global: [clock, tasks_in_flight, wan_utilization] (3)
        Nodes: len(nodes) * 9
        Pending tasks: max_pending * 10 (zero-padded)

    Total shape: (3 + len(nodes)*9 + max_pending*10,)
    """
    if state is None:
        global_arr = np.zeros(3, dtype=np.float32)
        return global_arr

    global_arr = np.array([
        float(state.clock),
        float(state.tasks_in_flight),
        float(state.network.wan_uplink_utilization) if state.network else 0.0,
    ], dtype=np.float32)

    nodes_arr_list = [node_to_array(n) for n in state.nodes]
    nodes_arr = np.concatenate(nodes_arr_list) if nodes_arr_list else np.array([], dtype=np.float32)

    pending_list = []
    if include_pending and max_pending > 0:
        tasks = state.pending_tasks[:max_pending]
        for t in tasks:
            pt_vec = np.array([
                float(t.scheduled_arrival),
                float(t.length_mi),
                float(t.input_size_mb),
                float(t.output_size_mb),
                float(t.container_size_mb),
                float(t.deadline),
                float(t.app_id),
                float(t.origin_node_index),
                0.0,
                0.0,
            ], dtype=np.float32)
            pending_list.append(pt_vec)

        # Pad with zeros if fewer than max_pending
        while len(pending_list) < max_pending:
            pending_list.append(np.zeros(10, dtype=np.float32))

        pending_arr = np.concatenate(pending_list)
    else:
        pending_arr = np.array([], dtype=np.float32)

    return np.concatenate([global_arr, nodes_arr, pending_arr]).astype(np.float32)
