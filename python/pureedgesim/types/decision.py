"""
PlacementDecision dataclass and InvalidDecisionError exception.
"""

from __future__ import annotations
from dataclasses import dataclass, field
from typing import Optional, Any, Dict, TYPE_CHECKING

if TYPE_CHECKING:
    from pureedgesim.types.node import Node


@dataclass
class PlacementDecision:
    """
    Structured placement decision returned by select_node().
    """
    node: Optional[Node]
    confidence: Optional[float] = None
    metadata: Dict[str, Any] = field(default_factory=dict)


class InvalidDecisionError(Exception):
    """
    Raised when select_node() returns an invalid/unacceptable node.
    """
    def __init__(self, message: str, node=None, task=None):
        self.node = node
        self.task = task
        super().__init__(message)
