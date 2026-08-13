"""
Unit tests for Dispatcher event loop routing.
"""

import socket
import struct
import threading
import time
import pytest

from pureedgesim._bridge.connection import Connection
from pureedgesim._bridge.dispatcher import Dispatcher
from pureedgesim._bridge.protocol import encode, decode


class DummyOrchestrator:
    def __init__(self):
        self.episode_begun = False
        self.episode_ended = False
        self.decisions_count = 0

    def on_episode_begin(self, msg):
        self.episode_begun = True

    def select_node(self, msg):
        self.decisions_count += 1
        return 2

    def on_episode_end(self, msg):
        self.episode_ended = True


def test_dispatcher_lifecycle(tmp_path):
    sock_path = str(tmp_path / "dispatcher.sock")

    def mock_java_process():
        srv = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        srv.bind(sock_path)
        srv.listen(1)
        conn, _ = srv.accept()

        def send_frame(msg_dict):
            payload = encode(msg_dict).encode('utf-8')
            header = struct.pack('>I', len(payload))
            conn.sendall(header + payload)

        def recv_frame():
            header = conn.recv(4)
            n = struct.unpack('>I', header)[0]
            body = b''
            while len(body) < n:
                body += conn.recv(n - len(body))
            return decode(body.decode('utf-8'))

        # Step 1: receive READY from Python
        ready = recv_frame()
        assert ready.get('type') == 'READY'

        # Step 2: send EPISODE_INIT and receive READY_ACK
        send_frame({'type': 'EPISODE_INIT', 'episode_id': 1})
        ack = recv_frame()
        assert ack.get('type') == 'READY_ACK'
        assert ack.get('status') == 'READY'

        # Step 3: send DECISION_REQUEST and receive DECISION_RESPONSE
        send_frame({'type': 'DECISION_REQUEST', 'request_id': 100})
        resp = recv_frame()
        assert resp.get('type') == 'DECISION_RESPONSE'
        assert resp.get('node_index') == 2

        # Step 4: send EPISODE_END and receive SHUTDOWN_ACK
        send_frame({'type': 'EPISODE_END', 'episode_id': 1})
        shutdown_ack = recv_frame()
        assert shutdown_ack.get('type') == 'SHUTDOWN_ACK'

        conn.close()
        srv.close()

    t = threading.Thread(target=mock_java_process, daemon=True)
    t.start()
    time.sleep(0.05)

    conn = Connection(sock_path)
    orch = DummyOrchestrator()
    dispatcher = Dispatcher(conn, orch)
    dispatcher.run()

    assert orch.episode_begun is True
    assert orch.decisions_count == 1
    assert orch.episode_ended is True
    conn.close()
