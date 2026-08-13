"""
Event loop and message dispatcher routing protocol requests to Python orchestrators.
"""

import sys
from typing import Any, Dict, List
from pureedgesim._bridge.connection import Connection
from pureedgesim._bridge.protocol import (
    decode,
    encode,
    build_nodes_from_episode_init,
    build_state_from_decision_request,
    build_outcome_from_task_result,
)
from pureedgesim.types.episode import EpisodeContext, EpisodeSummary
from pureedgesim.types.node import Node


class Dispatcher:
    """
    Message dispatcher loop.

    Reads framed JSON messages from the Java process over Connection, parses them,
    and dispatches them to orchestrator lifecycle methods using typed data objects.
    """

    def __init__(
        self,
        conn: Connection,
        orchestrator: Any,
        strict: bool = True,
        heartbeat_interval: float = 0.0
    ) -> None:
        """
        Initialize the Dispatcher.

        Args:
            conn: Connected socket connection to Java.
            orchestrator: User orchestrator instance.
            strict: Whether to raise on invalid decisions (default True).
            heartbeat_interval: Heartbeat interval in seconds (0 = disabled).
        """
        self._conn = conn
        self._orch = orchestrator
        self._strict = strict
        self._heartbeat_interval = heartbeat_interval
        self._nodes: List[Node] = []
        self._task_cache = {}

    def run(self) -> None:
        """
        Block, reading and dispatching incoming JSON messages until SHUTDOWN or EOF.
        """
        # Step 1: Send initial READY message to Java to signal Python bridge is connected and listening.
        self._conn.send(encode({'type': 'READY'}))

        while True:
            try:
                raw = self._conn.recv()
            except EOFError:
                print("[bridge] Connection closed by Java.", file=sys.stderr)
                break

            msg = decode(raw)
            msg_type = msg.get('type')

            if msg_type == 'EPISODE_INIT':
                self._handle_episode_init(msg)
            elif msg_type == 'DECISION_REQUEST':
                self._handle_decision_request(msg)
            elif msg_type == 'TASK_RESULT':
                self._handle_task_result(msg)
            elif msg_type == 'EPISODE_END':
                self._handle_episode_end(msg)
                break
            elif msg_type == 'SHUTDOWN':
                if hasattr(self._orch, 'on_shutdown'):
                    self._orch.on_shutdown()
                break
            elif msg_type == 'HEARTBEAT':
                pass
            else:
                print(f"[bridge] Unknown message type received: '{msg_type}'", file=sys.stderr)

    def _handle_episode_init(self, msg: Dict[str, Any]) -> None:
        """Handle EPISODE_INIT message from Java."""
        PROTOCOL_VERSION = "1.0"
        received_version = msg.get('protocol_version', '1.0')
        if received_version != PROTOCOL_VERSION:
            import warnings
            warnings.warn(
                f"Protocol version mismatch: bridge expects {PROTOCOL_VERSION}, "
                f"Java sent {received_version}. Some fields may be missing."
            )

        self._nodes = build_nodes_from_episode_init(msg)
        episode_id = msg.get('episode_id', 0)
        
        ep_ctx = EpisodeContext(
            episode_id=episode_id,
            algorithm_name=msg.get('algorithm_name', 'PYTHON'),
            architecture_name=msg.get('architecture_name', 'ALL'),
            num_devices=len(self._nodes),
            sim_duration=0.0,
            num_candidate_nodes=len(self._nodes),
            nodes=self._nodes,
        )

        if hasattr(self._orch, 'on_episode_begin'):
            try:
                self._orch.on_episode_begin(ep_ctx, self._nodes)
            except Exception as e:
                print(f"[bridge] Error in on_episode_begin: {e}", file=sys.stderr)
                if self._strict:
                    raise

        self._conn.send(encode({
            'type': 'READY_ACK',
            'episode_id': episode_id,
            'status': 'READY',
        }))

    def _validate_decision(self, result: Any, task: Any, state: Any) -> int:
        """
        Validate decision returned by orchestrator's select_node method.

        Returns target node index, or -1 if result is None.
        Raises InvalidDecisionError if decision is invalid.
        """
        import math
        from pureedgesim.types.decision import PlacementDecision, InvalidDecisionError
        from pureedgesim.types.node import Node

        if result is None:
            return -1

        if isinstance(result, PlacementDecision):
            node = result.node
            if node is None:
                return -1
        elif isinstance(result, Node):
            node = result
        elif isinstance(result, int):
            if result == -1:
                return -1
            matching = [n for n in state.nodes if n.index == result]
            if not matching:
                raise InvalidDecisionError(
                    f"Node index {result} is not in the candidate list for this episode.",
                    task=task
                )
            node = matching[0]
        elif hasattr(result, 'index'):
            node = result
        elif hasattr(result, 'node') and getattr(result, 'node') is not None:
            node = result.node
        else:
            raise TypeError(
                f"select_node() returned {type(result).__name__}, "
                f"expected Node, PlacementDecision, int, or None"
            )

        valid_indices = {n.index for n in state.nodes}
        if node.index not in valid_indices:
            raise InvalidDecisionError(
                f"Node (index={node.index}) is not in the candidate list for this episode. "
                "Did you return a stale reference from a previous episode?",
                node=node, task=task
            )

        if not node.is_alive:
            raise InvalidDecisionError(
                f"Selected node (index={node.index}) is dead (battery depleted). "
                "Check node.is_alive before returning.",
                node=node, task=task
            )

        if math.isnan(node.available_ram_mb) or math.isnan(task.ram_required_mb):
            raise InvalidDecisionError(
                f"Node RAM or task RAM requirement contains NaN.",
                node=node, task=task
            )

        if node.available_ram_mb < task.ram_required_mb:
            raise InvalidDecisionError(
                f"Node (index={node.index}) has insufficient RAM: "
                f"needs {task.ram_required_mb:.1f} MB, has {node.available_ram_mb:.1f} MB.",
                node=node, task=task
            )

        if math.isnan(node.available_storage_mb) or math.isnan(task.container_size_mb):
            raise InvalidDecisionError(
                f"Node storage or task container size contains NaN.",
                node=node, task=task
            )

        if node.available_storage_mb < task.container_size_mb:
            raise InvalidDecisionError(
                f"Node (index={node.index}) has insufficient storage: "
                f"needs {task.container_size_mb:.1f} MB, has {node.available_storage_mb:.1f} MB.",
                node=node, task=task
            )

        return node.index

    def _handle_decision_request(self, msg: Dict[str, Any]) -> None:
        """Handle DECISION_REQUEST message from Java."""
        req_id = msg.get('request_id', 0)
        task, state = build_state_from_decision_request(msg, self._nodes)
        self._task_cache[task.id] = task

        chosen_node_index = -1
        if hasattr(self._orch, 'select_node'):
            try:
                res = self._orch.select_node(task, state)
                chosen_node_index = self._validate_decision(res, task, state)
            except Exception as e:
                if self._strict:
                    raise
                else:
                    import warnings
                    warnings.warn(f"Invalid decision (lenient mode): {e}")
                    chosen_node_index = -1

        self._conn.send(encode({
            'type': 'DECISION_RESPONSE',
            'request_id': req_id,
            'node_index': chosen_node_index,
        }))

    def _handle_task_result(self, msg: Dict[str, Any]) -> None:
        """Handle TASK_RESULT message from Java (fire-and-forget)."""
        outcome = build_outcome_from_task_result(msg, self._task_cache, self._nodes)
        if hasattr(self._orch, 'on_task_complete'):
            try:
                self._orch.on_task_complete(outcome)
            except Exception as e:
                print(f"[bridge] Error in on_task_complete: {e}", file=sys.stderr)

    def _handle_episode_end(self, msg: Dict[str, Any]) -> None:
        """Handle EPISODE_END message from Java."""
        summary = EpisodeSummary(
            episode_id=msg.get('episode_id', 0),
            total_tasks=msg.get('total_tasks', 0),
            successful_tasks=msg.get('successful_tasks', 0),
            failed_tasks=msg.get('failed_tasks', 0),
            cached_tasks=msg.get('cached_tasks', 0),
            sim_duration=msg.get('sim_duration', 0.0),
        )

        if hasattr(self._orch, 'on_episode_end'):
            try:
                self._orch.on_episode_end(summary)
            except Exception as e:
                print(f"[bridge] Error in on_episode_end: {e}", file=sys.stderr)

        self._conn.send(encode({
            'type': 'SHUTDOWN_ACK',
            'status': 'OK',
        }))
