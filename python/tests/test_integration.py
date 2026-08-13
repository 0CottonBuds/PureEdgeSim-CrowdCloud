"""
Integration test: launches the Java simulation and checks that it
completes successfully with RoundRobinOrchestrator.

Requires: Maven on PATH, Python bridge package installed/on PYTHONPATH.
"""

import os
import subprocess
import pytest

PROJECT_ROOT = os.environ.get(
    'PUREEDGESIM_ROOT',
    '/home/cotton/Projects/ML/Thesis/PureEdgeSim'
)


@pytest.mark.integration
def test_e2e_round_robin():
    """Full simulation run with Python RoundRobinOrchestrator."""
    cmd = [
        'mvn', 'exec:exec',
        '-Dexec.executable=java',
        '-Dexec.args=-Xmx2g -classpath %classpath examples.ExamplePythonBridgeE2E',
    ]

    result = subprocess.run(
        cmd,
        cwd=PROJECT_ROOT,
        capture_output=True,
        text=True,
        timeout=180,
        env={**os.environ, 'PYTHONPATH': os.path.join(PROJECT_ROOT, 'python')},
    )

    assert result.returncode == 0, (
        f"Simulation failed with exit code {result.returncode}.\n"
        f"STDOUT:\n{result.stdout[-3000:]}\n"
        f"STDERR:\n{result.stderr[-3000:]}"
    )

    # Verify output CSV was created
    output_dir = os.path.join(PROJECT_ROOT, 'PureEdgeSim', 'output')
    assert os.path.exists(output_dir), f"Output directory {output_dir} does not exist"
    csv_files = []
    for root, _, files in os.walk(output_dir):
        for f in files:
            if f.endswith('.csv'):
                csv_files.append(os.path.join(root, f))
    assert len(csv_files) > 0, "No output CSV generated in output directory"
