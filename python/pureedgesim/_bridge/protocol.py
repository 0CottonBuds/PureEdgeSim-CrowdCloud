"""
JSON serialization and deserialization helpers for the PureEdgeSim bridge protocol.
"""

import json
from typing import Any, Dict


def decode(raw: str) -> Dict[str, Any]:
    """
    Parse a JSON string into a Python dict.

    Args:
        raw: Raw JSON string received from Java.

    Returns:
        Parsed dictionary.

    Raises:
        ValueError: If raw is malformed or invalid JSON.
    """
    return json.loads(raw)


def encode(msg: Dict[str, Any]) -> str:
    """
    Serialize a Python dict into a compact JSON string (no whitespace separators).

    Args:
        msg: Dictionary message to serialize.

    Returns:
        Compact UTF-8 JSON string.
    """
    return json.dumps(msg, separators=(',', ':'))
