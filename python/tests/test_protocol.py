"""
Unit tests for protocol encoding/decoding.
"""

import pytest
from pureedgesim._bridge.protocol import encode, decode


def test_encode_decode_round_trip():
    original = {"type": "DECISION_RESPONSE", "request_id": 3, "node_index": 1}
    assert decode(encode(original)) == original


def test_decode_raises_on_invalid_json():
    with pytest.raises(Exception):
        decode("not valid json{")
