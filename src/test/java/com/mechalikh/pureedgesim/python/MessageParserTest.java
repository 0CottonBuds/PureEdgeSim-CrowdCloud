package com.mechalikh.pureedgesim.python;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MessageParserTest {

    @Test
    void testParseType() {
        String json = "{\"type\":\"DECISION_RESPONSE\",\"request_id\":5,\"node_index\":2}";
        assertEquals("DECISION_RESPONSE", MessageParser.getType(json));
    }

    @Test
    void testParseNodeIndex() {
        String json = "{\"type\":\"DECISION_RESPONSE\",\"request_id\":5,\"node_index\":2}";
        assertEquals(2, MessageParser.getNodeIndex(json));

        String jsonNegative = "{\"type\":\"DECISION_RESPONSE\",\"request_id\":5,\"node_index\":-1}";
        assertEquals(-1, MessageParser.getNodeIndex(jsonNegative));
    }

    @Test
    void testParseStatus() {
        String json = "{\"type\":\"READY_ACK\",\"episode_id\":0,\"status\":\"READY\"}";
        assertEquals("READY", MessageParser.getStatus(json));
    }

    @Test
    void testParseEpisodeId() {
        String json = "{\"type\":\"READY_ACK\",\"episode_id\":3,\"status\":\"READY\"}";
        assertEquals(3, MessageParser.getEpisodeId(json));
    }

    @Test
    void testMissingFields() {
        String json = "{\"foo\":\"bar\"}";
        assertNull(MessageParser.getType(json));
        assertEquals(-1, MessageParser.getNodeIndex(json));
        assertNull(MessageParser.getStatus(json));
        assertEquals(-1, MessageParser.getEpisodeId(json));
    }
}
