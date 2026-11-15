package org.onetwoone.gateway.sip;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SimSelectionHeaderTest {
    @Test
    public void parsesSimOne() {
        assertEquals(1, SimSelectionHeader.parse("INVITE sip:+79123456789@example SIP/2.0\r\nX-GSM-SIM: 1\r\n\r\n"));
    }

    @Test
    public void parsesSimTwoCaseInsensitively() {
        assertEquals(2, SimSelectionHeader.parse("INVITE sip:+79123456789@example SIP/2.0\r\nx-gsm-sim: 2\r\n\r\n"));
    }

    @Test
    public void missingHeaderKeepsLegacyRouting() {
        assertEquals(0, SimSelectionHeader.parse("INVITE sip:+79123456789@example SIP/2.0\r\nFrom: <sip:101@example>\r\n"));
    }

    @Test
    public void invalidSlotIsIgnored() {
        assertEquals(0, SimSelectionHeader.parse("INVITE sip:+79123456789@example SIP/2.0\r\nX-GSM-SIM: 3\r\n"));
    }

    @Test
    public void nullMessageIsIgnored() {
        assertEquals(0, SimSelectionHeader.parse(null));
    }
}
