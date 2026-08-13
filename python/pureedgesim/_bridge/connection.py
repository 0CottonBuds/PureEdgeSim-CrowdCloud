"""
Unix Domain Socket connection handling for Python side of the bridge.
"""

import socket
import struct
import time


class Connection:
    """
    Unix domain socket I/O with 4-byte big-endian length-prefix framing.

    Connects to an existing Unix socket file (created by Java's ProcessBuilder / JavaBridge).
    """

    def __init__(self, socket_path: str, connect_timeout: float = 10.0) -> None:
        """
        Connect to the Unix socket at socket_path.

        Retries every 100ms until connect_timeout seconds elapse.
        Raises ConnectionError if the socket never becomes available.

        Args:
            socket_path: Absolute path to the Unix domain socket file created by Java.
            connect_timeout: Maximum time in seconds to wait for socket file connection.

        Raises:
            ConnectionError: If connection cannot be established within connect_timeout seconds.
        """
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
