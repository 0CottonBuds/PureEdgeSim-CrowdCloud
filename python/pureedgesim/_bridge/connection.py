"""
Unix Domain Socket connection handling for Python side of the bridge.
"""

import os
import socket
import struct
import time


class Connection:
    """
    Unix domain socket I/O with 4-byte big-endian length-prefix framing.

    Supports both Server mode (listening for Java client) and Client mode.
    """

    def __init__(self, socket_path: str, connect_timeout: float = 10.0, server: bool = True) -> None:
        """
        Initialize socket connection.

        Args:
            socket_path: Absolute path to the Unix domain socket file.
            connect_timeout: Maximum time in seconds to connect or accept.
            server: If True (default), bind/listen as server and accept client.
                    If False, connect as client to an existing socket server.

        Raises:
            ConnectionError: If connection cannot be established.
        """
        self._socket_path = socket_path

        if server:
            if os.path.exists(socket_path):
                try:
                    os.unlink(socket_path)
                except OSError:
                    pass

            server_sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            server_sock.bind(socket_path)
            server_sock.listen(1)
            server_sock.settimeout(connect_timeout)

            try:
                self._sock, _ = server_sock.accept()
            except socket.timeout:
                server_sock.close()
                raise ConnectionError(f"Timed out waiting for Java connection on socket '{socket_path}'")
            except Exception as e:
                server_sock.close()
                raise ConnectionError(f"Failed to accept connection on socket '{socket_path}': {e}")
            finally:
                server_sock.close()
        else:
            deadline = time.monotonic() + connect_timeout
            last_err = None
            while time.monotonic() < deadline:
                try:
                    self._sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
                    self._sock.connect(socket_path)
                    return
                except (FileNotFoundError, ConnectionRefusedError) as e:
                    last_err = e
                    self._sock.close()
                    time.sleep(0.1)
            raise ConnectionError(
                f"Could not connect to Unix socket '{socket_path}' within {connect_timeout}s. Last error: {last_err}"
            )

        self._sock.settimeout(None)

    def send(self, payload: str) -> None:
        """
        Encode payload as UTF-8, prefix with 4-byte big-endian length header, and write to socket.

        Args:
            payload: JSON string message to send to Java.
        """
        data = payload.encode('utf-8')
        header = struct.pack('>I', len(data))
        self._sock.sendall(header + data)

    def recv(self) -> str:
        """
        Read 4-byte big-endian length header, then read exactly N payload bytes. Decode as UTF-8.

        Returns:
            Decoded UTF-8 JSON message string from Java.

        Raises:
            EOFError: If the underlying socket connection is closed by Java.
        """
        header = self._recv_exactly(4)
        n = struct.unpack('>I', header)[0]
        body = self._recv_exactly(n)
        return body.decode('utf-8')

    def _recv_exactly(self, n: int) -> bytes:
        """
        Helper method to read exactly n bytes from the socket stream.

        Args:
            n: Exact number of bytes to read.

        Returns:
            Accumulated bytes.

        Raises:
            EOFError: If EOF is reached before n bytes are accumulated.
        """
        buf = bytearray()
        while len(buf) < n:
            chunk = self._sock.recv(n - len(buf))
            if not chunk:
                raise EOFError("Connection closed by remote Java peer")
            buf.extend(chunk)
        return bytes(buf)

    def close(self) -> None:
        """
        Close the underlying Unix socket connection safely. Idempotent.
        """
        try:
            self._sock.close()
        except Exception:
            pass
