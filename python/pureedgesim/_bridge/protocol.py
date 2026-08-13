"""
JSON serialization and deserialization helpers, plus typed object factory functions.
"""

import json
from typing import Any, Dict, List, Tuple, Optional

from pureedgesim.types.node import Node, NodeType, Location, node_type_from_java
from pureedgesim.types.task import Task, TaskOutcome, TaskStatus, FailureReason, _JAVA_FAILURE_MAP
from pureedgesim.types.state import SimulationState
from pureedgesim.types.network import NetworkState
from pureedgesim.types.episode import EpisodeContext, EpisodeSummary


def decode(raw: str) -> Dict[str, Any]:
    """Parse a JSON string into a Python dict."""
    return json.loads(raw)


def encode(msg: Dict[str, Any]) -> str:
    """Serialize a Python dict into a compact JSON string."""
    return json.dumps(msg, separators=(',', ':'))


def build_nodes_from_episode_init(msg: Dict[str, Any]) -> List[Node]:
    """Convert EPISODE_INIT.nodes[] to List[Node] with static capacity fields."""
    nodes = []
    for n in msg.get('nodes', []):
        node = Node(
            index=n.get('node_index', 0),
            id=n.get('node_id', 0),
            type=node_type_from_java(n.get('node_type', '')),
            total_mips=float(n.get('total_mips', 0.0)),
            mips_per_core=float(n.get('mips_per_core', 0.0)),
            num_cores=int(n.get('num_cores', 1)),
            total_ram_mb=float(n.get('total_ram_mb', 0.0)),
            total_storage_mb=float(n.get('total_storage_mb', 0.0)),
            is_peripheral=bool(n.get('is_peripheral', False)),
            base_location=Location(float(n.get('base_location_x', 0.0)), float(n.get('base_location_y', 0.0))),
            available_ram_mb=0.0,
            available_storage_mb=0.0,
            cpu_utilization=0.0,
            avg_cpu_utilization=0.0,
            is_idle=True,
            is_alive=True,
            queued_tasks=0,
            current_location=Location(float(n.get('base_location_x', 0.0)), float(n.get('base_location_y', 0.0))),
        )
        nodes.append(node)
    return nodes


def build_state_from_decision_request(
    msg: Dict[str, Any],
    master_nodes: List[Node],
) -> Tuple[Task, SimulationState]:
    """
    Convert DECISION_REQUEST JSON to (Task, SimulationState).
    Creates NEW Node instances by overlaying dynamic node_states on static master_nodes.
    """
    ct = msg.get('current_task', {})
    
    # Input/Output sizes converted from bits to Megabytes (bits / 8 / 1_000_000)
    input_bits = float(ct.get('task_file_size_bits', 0.0))
    output_bits = float(ct.get('task_output_bits', 0.0))
    container_mb = float(ct.get('task_container_mb', 0.0))
    
    task = Task(
        id=int(ct.get('task_id', -1)),
        length_mi=float(ct.get('length_mips', 0.0)),
        input_size_mb=input_bits / 8.0 / 1_000_000.0,
        output_size_mb=output_bits / 8.0 / 1_000_000.0,
        container_size_mb=container_mb,
        ram_required_mb=container_mb,
        deadline=float(ct.get('max_latency_s', 0.0)),
        app_id=int(ct.get('application_id', 0)),
        app_type=str(ct.get('task_type', '')),
        origin_node_id=int(ct.get('edge_device_id', -1)),
        origin_node_index=int(ct.get('edge_device_index', -1)),
        origin_location=Location(0.0, 0.0),
        origin_cpu_utilization=0.0,
        scheduled_arrival=float(msg.get('sim_clock_s', 0.0)),
        metadata={'request_id': msg.get('request_id', -1)},
    )

    # Build fresh dynamic nodes
    node_states = msg.get('node_states', [])
    live_nodes = []
    for master, ns in zip(master_nodes, node_states):
        loc_x = float(ns.get('current_location_x', master.base_location.x))
        loc_y = float(ns.get('current_location_y', master.base_location.y))
        
        live_node = Node(
            index=master.index,
            id=master.id,
            type=master.type,
            total_mips=master.total_mips,
            mips_per_core=master.mips_per_core,
            num_cores=master.num_cores,
            total_ram_mb=master.total_ram_mb,
            total_storage_mb=master.total_storage_mb,
            is_peripheral=master.is_peripheral,
            base_location=master.base_location,
            available_ram_mb=float(ns.get('available_ram_mb', 0.0)),
            available_storage_mb=float(ns.get('available_storage_mb', 0.0)),
            cpu_utilization=float(ns.get('current_cpu_pct', 0.0)),
            avg_cpu_utilization=float(ns.get('avg_cpu_pct', 0.0)),
            is_idle=bool(ns.get('is_idle', True)),
            is_alive=not bool(ns.get('is_dead', False)),
            queued_tasks=int(ns.get('queue_length', 0)),
            current_location=Location(loc_x, loc_y),
        )
        live_nodes.append(live_node)

        # Update origin location if this node is origin
        if master.id == task.origin_node_id:
            task = Task(
                id=task.id,
                length_mi=task.length_mi,
                input_size_mb=task.input_size_mb,
                output_size_mb=task.output_size_mb,
                container_size_mb=task.container_size_mb,
                ram_required_mb=task.ram_required_mb,
                deadline=task.deadline,
                app_id=task.app_id,
                app_type=task.app_type,
                origin_node_id=task.origin_node_id,
                origin_node_index=task.origin_node_index,
                origin_location=Location(loc_x, loc_y),
                origin_cpu_utilization=live_node.cpu_utilization,
                scheduled_arrival=task.scheduled_arrival,
                metadata=task.metadata,
            )

    # Build pending tasks list
    pending = []
    for pt in msg.get('pending_tasks', []):
        pt_input_bits = float(pt.get('task_file_size_bits', 0.0))
        pt_output_bits = float(pt.get('task_output_bits', 0.0))
        pt_container_mb = float(pt.get('task_container_mb', 0.0))
        pending.append(Task(
            id=int(pt.get('task_id', -1)),
            length_mi=float(pt.get('length_mips', 0.0)),
            input_size_mb=pt_input_bits / 8.0 / 1_000_000.0,
            output_size_mb=pt_output_bits / 8.0 / 1_000_000.0,
            container_size_mb=pt_container_mb,
            ram_required_mb=pt_container_mb,
            deadline=float(pt.get('max_latency_s', 0.0)),
            app_id=int(pt.get('application_id', 0)),
            app_type=str(pt.get('task_type', '')),
            origin_node_id=int(pt.get('edge_device_id', -1)),
            origin_node_index=int(pt.get('edge_device_index', -1)),
            origin_location=Location(0.0, 0.0),
            origin_cpu_utilization=0.0,
            scheduled_arrival=float(msg.get('sim_clock_s', 0.0)),
        ))

    state = SimulationState(
        clock=float(msg.get('sim_clock_s', 0.0)),
        nodes=live_nodes,
        pending_tasks=pending,
        tasks_in_flight=int(msg.get('tasks_in_flight', 0)),
        network=NetworkState(
            wan_uplink_utilization=float(msg.get('wan_uplink_utilization', 0.0))
        ),
    )
    return task, state


def build_outcome_from_task_result(
    msg: Dict[str, Any],
    task_cache: Dict[int, Task],
    master_nodes: List[Node],
) -> TaskOutcome:
    """Convert TASK_RESULT JSON message into TaskOutcome."""
    task_id = int(msg.get('task_id', -1))
    task = task_cache.get(task_id)
    if task is None:
        # Dummy fallback task if task_id not in cache
        task = Task(
            id=task_id, length_mi=0.0, input_size_mb=0.0, output_size_mb=0.0,
            container_size_mb=0.0, ram_required_mb=0.0, deadline=0.0, app_id=0,
            app_type='', origin_node_id=-1, origin_node_index=-1,
            origin_location=Location(0.0, 0.0), origin_cpu_utilization=0.0,
            scheduled_arrival=0.0
        )

    chosen_idx = int(msg.get('chosen_node_index', -1))
    assigned_node = master_nodes[chosen_idx] if 0 <= chosen_idx < len(master_nodes) else None

    status_str = str(msg.get('status', 'UNKNOWN'))
    status = TaskStatus.SUCCESS if status_str == 'SUCCESS' else TaskStatus.FAILED

    failure_java = msg.get('failure_reason')
    failure = _JAVA_FAILURE_MAP.get(failure_java) if failure_java else None

    return TaskOutcome(
        task=task,
        assigned_node=assigned_node,
        status=status,
        failure_reason=failure,
        total_latency=float(msg.get('total_delay_s', 0.0)),
        computation_time=float(msg.get('execution_time_s', 0.0)),
        network_time=float(msg.get('network_time_s', 0.0)),
        queue_wait_time=float(msg.get('waiting_time_s', 0.0)),
        execution_start=0.0,
        execution_end=0.0,
        result_returned_at=0.0,
        was_cached=(int(msg.get('request_id', 0)) == -1),
        request_id=int(msg.get('request_id', -1)),
    )
