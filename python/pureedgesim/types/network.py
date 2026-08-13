"""
NetworkState dataclass representing communication link utilization.
"""

from __future__ import annotations
from dataclasses import dataclass, field
from typing import Any, Dict


@dataclass(frozen=True)
class NetworkState:
    """
    Snapshot of network conditions at decision time.
    """
    wan_uplink_utilization: float   # WAN uplink load fraction (0.0 to 1.0)
    metadata: Dict[str, Any] = field(default_factory=dict)
