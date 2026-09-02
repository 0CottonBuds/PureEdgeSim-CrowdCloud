"""
Unit tests for Orchestrator base class, RLOrchestrator, rewards, features, and example classes.
"""

from unittest.mock import MagicMock

from pureedgesim import Orchestrator, RLOrchestrator
from pureedgesim.types.node import Node, NodeType, Location
from pureedgesim.types.task import Task, TaskOutcome, TaskStatus
from pureedgesim.types.state import SimulationState
from pureedgesim.types.network import NetworkState
from pureedgesim.rewards import latency_reward, success_reward, deadline_reward, CompositeReward
from pureedgesim.features import task_to_array, node_to_array, node_static_to_array, state_to_array
from examples.run_round_robin import RoundRobinOrchestrator
from examples.run_nearest_node import NearestNodeOrchestrator
from examples.run_dqn_skeleton import DQNOrchestrator


def make_node(index, alive=True, ram=1000.0):
    return Node(
        index=index, id=index, type=NodeType.EDGE_SERVER,
        total_mips=1000.0, mips_per_core=500.0, num_cores=2,
        total_ram_mb=2048.0, total_storage_mb=10240.0,
        is_peripheral=True, base_location=Location(0.0, 0.0),
        available_ram_mb=ram, available_storage_mb=5000.0,
        cpu_utilization=0.3, avg_cpu_utilization=0.3,
        is_idle=False, is_alive=alive,
        queued_tasks=1, current_location=Location(0.0, 0.0),
    )


class ConcreteOrch(Orchestrator):
    def select_node(self, task, state):
        return state.nodes[0] if state.nodes else None


def test_orchestrator_lifecycle():
    o = ConcreteOrch()
    o.on_start(context=None)
    o.on_episode_begin(episode=None, nodes=[])
    o.on_episode_end(summary=None)
    o.on_shutdown()


def test_rl_orchestrator_reward_tracking():
    class ConcreteRL(RLOrchestrator):
        def select_node(self, task, state):
            return state.nodes[0]

    rl = ConcreteRL()
    mock_episode = MagicMock()
    mock_episode.num_candidate_nodes = 3
    nodes = [make_node(i) for i in range(3)]
    rl.on_episode_begin(mock_episode, nodes)
    assert rl.action_space_size == 3
    assert rl.episode_reward == 0.0
    assert rl.episode_steps == 0

    task = Task(
        id=1, length_mi=100.0, input_size_mb=1.0, output_size_mb=1.0,
        container_size_mb=100.0, ram_required_mb=100.0, deadline=2.0,
        app_id=0, app_type='', origin_node_id=0, origin_node_index=0,
        origin_location=Location(0.0, 0.0), origin_cpu_utilization=0.0,
        scheduled_arrival=0.0
    )
    outcome = TaskOutcome(
        task=task, assigned_node=nodes[0], status=TaskStatus.SUCCESS,
        failure_reason=None, total_latency=0.5, computation_time=0.3,
        network_time=0.1, queue_wait_time=0.1, execution_start=0.0,
        execution_end=0.5, result_returned_at=0.5
    )
    rl.on_task_complete(outcome)
    assert rl.episode_steps == 1


def test_round_robin_cycles():
    o = RoundRobinOrchestrator()
    nodes = [make_node(i) for i in range(3)]
    o.on_episode_begin(MagicMock(), nodes)

    mock_task = Task(
        id=1, length_mi=100.0, input_size_mb=1.0, output_size_mb=1.0,
        container_size_mb=100.0, ram_required_mb=100.0, deadline=2.0,
        app_id=0, app_type='', origin_node_id=0, origin_node_index=0,
        origin_location=Location(0.0, 0.0), origin_cpu_utilization=0.0,
        scheduled_arrival=0.0
    )

    state = SimulationState(
        clock=0.0,
        nodes=nodes,
        pending_tasks=[],
        tasks_in_flight=0,
        network=NetworkState(wan_uplink_utilization=0.0),
    )

    results = [o.select_node(mock_task, state) for _ in range(6)]
    indices = [n.index for n in results]
    assert indices == [0, 1, 2, 0, 1, 2]


def test_nearest_node_orchestrator():
    o = NearestNodeOrchestrator()
    n0 = make_node(0)
    n1 = make_node(1)
    n1.current_location = Location(100.0, 100.0)
    state = SimulationState(
        clock=0.0, nodes=[n0, n1], pending_tasks=[],
        tasks_in_flight=0, network=NetworkState(wan_uplink_utilization=0.0)
    )
    mock_task = Task(
        id=1, length_mi=100.0, input_size_mb=1.0, output_size_mb=1.0,
        container_size_mb=100.0, ram_required_mb=100.0, deadline=2.0,
        app_id=0, app_type='', origin_node_id=0, origin_node_index=0,
        origin_location=Location(90.0, 90.0), origin_cpu_utilization=0.0,
        scheduled_arrival=0.0
    )
    selected = o.select_node(mock_task, state)
    assert selected.index == 1


def test_rewards_and_features():
    node = make_node(0)
    task = Task(
        id=1, length_mi=100.0, input_size_mb=1.0, output_size_mb=1.0,
        container_size_mb=100.0, ram_required_mb=100.0, deadline=2.0,
        app_id=0, app_type='', origin_node_id=0, origin_node_index=0,
        origin_location=Location(0.0, 0.0), origin_cpu_utilization=0.0,
        scheduled_arrival=0.0
    )
    state = SimulationState(
        clock=1.0, nodes=[node], pending_tasks=[],
        tasks_in_flight=0, network=NetworkState(wan_uplink_utilization=0.0)
    )

    t_arr = task_to_array(task)
    assert len(t_arr) == 9

    n_arr = node_to_array(node)
    assert len(n_arr) == 9

    s_arr = state_to_array(state, include_pending=True, max_pending=5)
    # 3 (global) + 1*9 (nodes) + 5*10 (pending zero-padded) = 62
    assert len(s_arr) == 62

    comp_reward = CompositeReward([
        (success_reward, 1.0),
        (deadline_reward, 2.0),
    ])
    outcome = TaskOutcome(
        task=task, assigned_node=node, status=TaskStatus.SUCCESS,
        failure_reason=None, total_latency=1.0, computation_time=0.5,
        network_time=0.2, queue_wait_time=0.3, execution_start=0.0,
        execution_end=1.0, result_returned_at=1.0
    )
    r = comp_reward(outcome)
    assert r == 1.0
