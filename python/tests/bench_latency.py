"""
Measures Unix domain socket round-trip latency for a representative DECISION_REQUEST payload.
Run: python python/tests/bench_latency.py
"""
import threading
import socket
import struct
import time
import statistics
import json

DECISION_REQUEST_PAYLOAD = json.dumps({
    "type": "DECISION_REQUEST",
    "request_id": 0,
    "sim_clock_s": 10.0,
    "current_task": {
        "task_id": 1, "task_length_mi": 500.0, "task_file_size_bits": 8000000,
        "task_output_bits": 1000000, "task_container_mb": 50.0,
        "task_max_latency_s": 2.0, "task_app_id": 0,
        "task_app_type": "AUGMENTED_REALITY",
        "src_device_index": -1, "src_device_id": 10,
        "src_location_x": 50.0, "src_location_y": 100.0,
        "src_avg_cpu_pct": 0.3, "src_current_cpu_pct": 0.5,
    },
    "node_states": [
        {"node_index": i, "available_ram_mb": 1000.0, "available_storage_mb": 5000.0,
         "current_cpu_pct": 0.3, "avg_cpu_pct": 0.3, "is_idle": False, "is_dead": False,
         "queue_length": 2, "current_location_x": float(i * 10), "current_location_y": 0.0}
        for i in range(10)
    ],
    "pending_tasks": [],
    "wan_uplink_utilization": 0.1,
    "tasks_in_flight": 5,
}, separators=(',', ':'))

RESPONSE_PAYLOAD = json.dumps(
    {"type": "DECISION_RESPONSE", "request_id": 0, "node_index": 3},
    separators=(',', ':')
)


def run_bench(sock_path: str, n_samples: int = 10_000):
    def echo_server():
        srv = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        srv.bind(sock_path)
        srv.listen(1)
        try:
            conn, _ = srv.accept()
            for _ in range(n_samples + 100):
                try:
                    header = b''
                    while len(header) < 4:
                        chunk = conn.recv(4 - len(header))
                        if not chunk:
                            break
                        header += chunk
                    if len(header) < 4:
                        break
                    n = struct.unpack('>I', header)[0]
                    body = b''
                    while len(body) < n:
                        chunk = conn.recv(n - len(body))
                        if not chunk:
                            break
                        body += chunk
                    if len(body) < n:
                        break
                    resp = RESPONSE_PAYLOAD.encode('utf-8')
                    conn.sendall(struct.pack('>I', len(resp)) + resp)
                except Exception:
                    break
            conn.close()
        except Exception:
            pass
        finally:
            srv.close()

    t = threading.Thread(target=echo_server, daemon=True)
    t.start()
    time.sleep(0.1)

    from pureedgesim._bridge.connection import Connection
    c = Connection(sock_path, server=False)

    # Warm up
    for _ in range(100):
        c.send(DECISION_REQUEST_PAYLOAD)
        c.recv()

    latencies = []
    for _ in range(n_samples):
        t0 = time.perf_counter()
        c.send(DECISION_REQUEST_PAYLOAD)
        c.recv()
        latencies.append((time.perf_counter() - t0) * 1000)

    c.close()

    median = statistics.median(latencies)
    mean = statistics.mean(latencies)
    p95 = sorted(latencies)[int(0.95 * n_samples)]
    p99 = sorted(latencies)[int(0.99 * n_samples)]
    max_lat = max(latencies)

    print(f"Round-trip latency over Unix socket ({n_samples:,} samples):")
    print(f"  Payload size: {len(DECISION_REQUEST_PAYLOAD)} bytes")
    print(f"  Median:  {median:.3f} ms")
    print(f"  Mean:    {mean:.3f} ms")
    print(f"  p95:     {p95:.3f} ms")
    print(f"  p99:     {p99:.3f} ms")
    print(f"  Max:     {max_lat:.3f} ms")
    print()
    if median <= 0.5:
        print(f"  PASS: median {median:.3f} ms <= 0.5 ms budget")
    else:
        print(f"  FAIL: median {median:.3f} ms exceeds 0.5 ms budget")

    return {
        "n_samples": n_samples,
        "payload_bytes": len(DECISION_REQUEST_PAYLOAD),
        "median_ms": median,
        "mean_ms": mean,
        "p95_ms": p95,
        "p99_ms": p99,
        "max_ms": max_lat,
    }


if __name__ == '__main__':
    import tempfile, os
    with tempfile.TemporaryDirectory() as d:
        run_bench(os.path.join(d, 'bench.sock'))
