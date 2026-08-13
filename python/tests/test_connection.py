"""
Unit tests for Python Connection socket layer.
"""

import socket
import struct
import threading
import time
import pytest
from pureedgesim._bridge.connection import Connection


def test_echo_round_trip(tmp_path):
    sock_path = str(tmp_path / "test.sock")

    def echo_server():
        srv = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        srv.bind(sock_path)
        srv.listen(1)
        conn, _ = srv.accept()
        header = conn.recv(4)
        n = struct.unpack('>I', header)[0]
        body = b''
        while len(body) < n:
            body += conn.recv(n - len(body))
        conn.sendall(header + body)
        conn.close()
        srv.close()

    t = threading.Thread(target=echo_server, daemon=True)
    t.start()
    time.sleep(0.05)

    c = Connection(sock_path, server=False)
    c.send('{"type":"PING"}')
    assert c.recv() == '{"type":"PING"}'
    c.close()


def test_connection_timeout(tmp_path):
    sock_path = str(tmp_path / "nonexistent.sock")
    with pytest.raises(ConnectionError):
        Connection(sock_path, connect_timeout=0.2, server=False)


def test_double_close_is_idempotent(tmp_path):
    sock_path = str(tmp_path / "test_close.sock")

    def dummy_server():
        srv = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        srv.bind(sock_path)
        srv.listen(1)
        conn, _ = srv.accept()
        conn.close()
        srv.close()

    t = threading.Thread(target=dummy_server, daemon=True)
    t.start()
    time.sleep(0.05)

    c = Connection(sock_path, server=False)
    c.close()
    c.close()  # Must not raise
