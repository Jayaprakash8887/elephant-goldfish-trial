package org.cce.gatewaystub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The runtime switch of AS-REQ-23. */
class ModeHandlerTest {

    @Test
    void startsAsOk() {
        ModeHandler mode = new ModeHandler();

        assertFalse(mode.isFailing());
        GatewayStubMain.Reply reply = mode.handle("GET", "");
        assertEquals(200, reply.status());
        assertEquals("ok", reply.body());
    }

    @Test
    void switchesToFailAndBack() {
        ModeHandler mode = new ModeHandler();

        assertEquals(200, mode.handle("PUT", "fail").status());
        assertTrue(mode.isFailing());
        assertEquals("fail", mode.handle("GET", "").body());

        assertEquals(200, mode.handle("PUT", "ok\n").status());
        assertFalse(mode.isFailing());
        assertEquals("ok", mode.handle("GET", "").body());
    }

    @Test
    void anyOtherBodyGets400AndKeepsTheMode() {
        ModeHandler mode = new ModeHandler();
        mode.handle("PUT", "fail");

        assertEquals(400, mode.handle("PUT", "broken").status());
        assertEquals(400, mode.handle("PUT", "").status());
        assertEquals(400, mode.handle("PUT", "FAIL").status());
        assertTrue(mode.isFailing());
    }

    @Test
    void otherMethodsGet405() {
        assertEquals(405, new ModeHandler().handle("POST", "ok").status());
    }
}
