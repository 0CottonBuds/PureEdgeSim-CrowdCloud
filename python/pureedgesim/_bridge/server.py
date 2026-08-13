"""
CLI entry point for launching the Python orchestrator bridge process.

Usage:
    python3 -m pureedgesim._bridge.server \
        --socket /tmp/pureedgesim_orch_1234.sock \
        --orchestrator module.ClassName
"""

import argparse
import importlib
import sys
from pureedgesim._bridge.connection import Connection
from pureedgesim._bridge.dispatcher import Dispatcher


def main() -> None:
    """
    Main entry point parsing CLI arguments, instantiating the requested orchestrator,
    connecting to the Unix domain socket, and starting the dispatcher event loop.
    """
    parser = argparse.ArgumentParser(description="PureEdgeSim Python Orchestrator Bridge Server")
    parser.add_argument("--socket", required=True,
                        help="Unix domain socket path created by Java")
    parser.add_argument("--orchestrator", required=True,
                        help="Dotted module.ClassName of Orchestrator subclass")
    parser.add_argument("--strict", action="store_true", default=True,
                        help="Raise on invalid placement decisions (default: True)")
    parser.add_argument("--heartbeat", type=float, default=0.0,
                        help="Heartbeat interval in simulated seconds (0 = disabled)")

    args = parser.parse_args()

    # Dynamically import the orchestrator class
    try:
        if '.' in args.orchestrator:
            module_name, class_name = args.orchestrator.rsplit('.', 1)
            module = importlib.import_module(module_name)
            orchestrator_cls = getattr(module, class_name)
        else:
            print(f"[bridge] Invalid orchestrator specification '{args.orchestrator}'. Expected 'module.ClassName'.",
                  file=sys.stderr)
            sys.exit(1)
    except (ValueError, ImportError, AttributeError) as e:
        print(f"[bridge] Failed to import orchestrator class '{args.orchestrator}': {e}",
              file=sys.stderr)
        sys.exit(1)

    try:
        orchestrator = orchestrator_cls()
    except Exception as e:
        print(f"[bridge] Failed to instantiate orchestrator class '{args.orchestrator}': {e}",
              file=sys.stderr)
        sys.exit(1)

    try:
        conn = Connection(args.socket)
    except ConnectionError as e:
        print(f"[bridge] Connection error: {e}", file=sys.stderr)
        sys.exit(1)

    dispatcher = Dispatcher(conn, orchestrator, strict=args.strict, heartbeat_interval=args.heartbeat)

    try:
        dispatcher.run()
    except EOFError:
        print("[bridge] Java closed socket connection.", file=sys.stderr)
    except Exception as e:
        print(f"[bridge] Fatal error in dispatcher: {e}", file=sys.stderr)
        sys.exit(1)
    finally:
        conn.close()


if __name__ == "__main__":
    main()
