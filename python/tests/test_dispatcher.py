"""
Unit tests for Dispatcher event loop routing with typed objects.
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

    def on_episode_begin(self, ep_ctx, nodes):
        self.episode_begun = True

    def select_node(self, task, state):
        self.decisions_count += 1
        return 2

    def on_task_complete(self, outcome):
        pass

    def on_episode_end(self, summary):
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
        send_frame({
            'type': 'EPISODE_INIT',
            'episode_id': 1,
            'nodes': [
                {'node_index': 0, 'node_id': 1, 'node_type': 'CLOUD',
                 'total_mips': 10000.0, 'mips_per_core': 5000.0, 'num_cores': 2,
                 'total_ram_mb': 8192.0, 'total_storage_mb': 102400.0,
                 'is_peripheral': False, 'base_location_x': 0.0, 'base_location_y': 0.0},
                {'node_index': 1, 'node_id': 2, 'node_type': 'EDGE_DATACENTER',
                 'total_mips': 2000.0, 'mips_per_core': 1000.0, 'num_cores': 2,
                 'total_ram_mb': 2048.0, 'total_storage_mb': 20480.0,
                 'is_peripheral': True, 'base_location_x': 100.0, 'base_location_y': 200.0},
                {'node_index': 2, 'node_id': 3, 'node_type': 'EDGE_DEVICE',
                 'total_mips': 1000.0, 'mips_per_core': 500.0, 'num_cores': 2,
                 'total_ram_mb': 1024.0, 'total_storage_mb': 10240.0,
                 'is_peripheral': True, 'base_location_x': 50.0, 'base_location_y': 50.0},
            ]
        })
        ack = recv_frame()
        assert ack.get('type') == 'READY_ACK'
        assert ack.get('status') == 'READY'

        # Step 3: send DECISION_REQUEST and receive DECISION_RESPONSE
        send_frame({
            'type': 'DECISION_REQUEST',
            'request_id': 100,
            'sim_clock_s': 5.0,
            'current_task': {
                'task_id': 1, 'length_mips': 500.0, 'task_file_size_bits': 8_000_000,
                'task_output_bits': 1_000_000, 'task_container_mb': 50.0,
                'max_latency_s': 2.0, 'application_id': 0, 'task_type': 'TEST',
                'edge_device_index': 2, 'edge_device_id': 3,
                'current_location_x': 50.0, 'current_location_y': 50.0,
            },
            'node_states': [
                {'node_id': 1, 'available_ram_mb': 8192.0, 'available_storage_mb': 102400.0,
                 'current_cpu_pct': 0.0, 'avg_cpu_pct': 0.0, 'is_idle': True, 'is_dead': False,
                 'queue_length': 0, 'current_location_x': 0.0, 'current_location_y': 0.0},
                {'node_id': 2, 'available_ram_mb': 2048.0, 'available_storage_mb': 20480.0,
                 'current_cpu_pct': 0.0, 'avg_cpu_pct': 0.0, 'is_idle': True, 'is_dead': False,
                 'queue_length': 0, 'current_location_x': 100.0, 'current_location_y': 200.0},
                {'node_id': 3, 'available_ram_mb': 1024.0, 'available_storage_mb': 10240.0,
                 'current_cpu_pct': 0.0, 'avg_cpu_pct': 0.0, 'is_idle': True, 'is_dead': False,
                 'queue_length': 0, 'current_location_x': 50.0, 'current_location_y': 50.0},
            ],
            'pending_tasks': [],
            'wan_uplink_utilization': 0.0,
            'tasks_in_flight': 0,
        })
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
