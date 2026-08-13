"""
Compares wall-clock time of Java-only simulation vs. Java+Python bridge simulation.
Run: python python/tests/bench_e2e.py
"""
import subprocess
import os
import time

PROJECT_ROOT = os.environ.get(
    'PUREEDGESIM_ROOT',
    '/home/cotton/Projects/ML/Thesis/PureEdgeSim'
)
PYTHONPATH = os.path.join(PROJECT_ROOT, 'python')


def run_simulation(main_class: str, args: str, label: str) -> float:
    print(f"Running {label}...")
    cmd = [
        'mvn', '-q', 'exec:exec',
        '-Dexec.executable=java',
        f'-Dexec.args=-classpath %classpath {main_class} {args}'
    ]
    t0 = time.monotonic()
    result = subprocess.run(
        cmd,
        cwd=PROJECT_ROOT,
        env={**os.environ, 'PYTHONPATH': PYTHONPATH},
        capture_output=True,
        text=True,
        timeout=300,
    )
    elapsed = time.monotonic() - t0
    if result.returncode != 0:
        print(f"  FAILED: {result.stderr[-500:]}")
        return float('inf')
    print(f"  Completed in {elapsed:.2f}s")
    return elapsed


def main():
    print("=" * 60)
    print("PureEdgeSim Java/Python Bridge Macro End-to-End Benchmark")
    print("=" * 60)

    # 1. Java-only baseline (ExamplePythonBridge stub)
    t_java = run_simulation(
        'examples.ExamplePythonBridge',
        '',
        'Java-only baseline (ExamplePythonBridge)'
    )

    # 2. Java+Python bridge with RoundRobinOrchestrator
    t_round_robin = run_simulation(
        'examples.ExamplePythonBridgeE2E',
        'examples.run_round_robin.RoundRobinOrchestrator',
        'Java+Python bridge (RoundRobinOrchestrator)'
    )

    # 3. Java+Python bridge with DQNOrchestrator
    t_dqn = run_simulation(
        'examples.ExamplePythonBridgeE2E',
        'examples.run_dqn_skeleton.DQNOrchestrator',
        'Java+Python bridge (DQNOrchestrator)'
    )

    N_DECISIONS = 44  # exact offloading decision count in settings_bridge_test

    overhead_rr = (t_round_robin - t_java)
    overhead_rr_per_decision = (overhead_rr / N_DECISIONS) * 1000 if N_DECISIONS > 0 else 0

    overhead_dqn = (t_dqn - t_java)
    overhead_dqn_per_decision = (overhead_dqn / N_DECISIONS) * 1000 if N_DECISIONS > 0 else 0

    print()
    print("=" * 60)
    print(f"Java-only baseline wall-clock:     {t_java:.2f}s")
    print(f"RoundRobin bridge wall-clock:      {t_round_robin:.2f}s (Overhead: {overhead_rr:.2f}s)")
    print(f"DQN bridge wall-clock:             {t_dqn:.2f}s (Overhead: {overhead_dqn:.2f}s)")
    print(f"Per-decision overhead (RoundRobin): ~{overhead_rr_per_decision:.2f} ms")
    print(f"Per-decision overhead (DQN):        ~{overhead_dqn_per_decision:.2f} ms")
    print(f"Performance Budget:                <= 0.5 ms / decision")
    print("=" * 60)


if __name__ == '__main__':
    main()
