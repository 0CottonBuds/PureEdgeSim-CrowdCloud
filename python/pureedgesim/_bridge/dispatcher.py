"""
Event loop and message dispatcher routing protocol requests to Python orchestrators.
"""

import sys
from typing import Any, Dict
from pureedgesim._bridge.connection import Connection
from pureedgesim._bridge.protocol import decode, encode


class Dispatcher:
    """
    Message dispatcher loop.

    Reads framed JSON messages from the Java process over Connection, parses them,
    and dispatches them to orchestrator lifecycle methods.

    Routing Table:
        EPISODE_INIT     -> on_episode_begin()  -> send READY_ACK
        DECISION_REQUEST -> select_node()       -> send DECISION_RESPONSE
        TASK_RESULT      -> on_task_complete()  -> (no response)
        EPISODE_END      -> on_episode_end()    -> send SHUTDOWN_ACK
        SHUTDOWN         -> on_shutdown()       -> break loop
        HEARTBEAT        -> on_tick()           -> (no response)
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
        self._nodes = []
        self._context = None
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
        """
        Handle EPISODE_INIT message from Java.

        Args:
            msg: Decoded JSON dictionary for EPISODE_INIT.
        """
        # Factory deserialization is hooked up in Phase 4.5.
        if hasattr(self._orch, 'on_episode_begin'):
            try:
                self._orch.on_episode_begin(msg)
            except Exception as e:
                print(f"[bridge] Error in on_episode_begin: {e}", file=sys.stderr)

        self._conn.send(encode({
            'type': 'READY_ACK',
            'episode_id': msg.get('episode_id', 0),
            'status': 'READY',
        }))

    def _handle_decision_request(self, msg: Dict[str, Any]) -> None:
        """
        Handle DECISION_REQUEST message from Java.

        Args:
            msg: Decoded JSON dictionary for DECISION_REQUEST.
        """
        node_index = 0
        if hasattr(self._orch, 'select_node'):
            try:
                res = self._orch.select_node(msg)
                if isinstance(res, int):
                    node_index = res
                elif hasattr(res, 'node_index'):
                    node_index = res.node_index
            except Exception as e:
                print(f"[bridge] Error in select_node: {e}", file=sys.stderr)

        self._conn.send(encode({
            'type': 'DECISION_RESPONSE',
            'request_id': msg.get('request_id', 0),
            'node_index': node_index,
        }))

    def _handle_task_result(self, msg: Dict[str, Any]) -> None:
        """
        Handle TASK_RESULT message from Java (fire-and-forget).

        Args:
            msg: Decoded JSON dictionary for TASK_RESULT.
        """
        if hasattr(self._orch, 'on_task_complete'):
            try:
                self._orch.on_task_complete(msg)
            except Exception as e:
                print(f"[bridge] Error in on_task_complete: {e}", file=sys.stderr)

    def _handle_episode_end(self, msg: Dict[str, Any]) -> None:
        """
        Handle EPISODE_END message from Java.

        Args:
            msg: Decoded JSON dictionary for EPISODE_END.
        """
        if hasattr(self._orch, 'on_episode_end'):
            try:
                self._orch.on_episode_end(msg)
            except Exception as e:
                print(f"[bridge] Error in on_episode_end: {e}", file=sys.stderr)

        self._conn.send(encode({
            'type': 'SHUTDOWN_ACK',
            'status': 'OK',
        }))
