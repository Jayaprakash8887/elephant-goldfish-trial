package org.cce.admin.e2e;

import org.springframework.http.MediaType;

/**
 * Flips the stand-in's {@code /_stub/mode} between {@code ok} and {@code fail} (AS-REQ-23). Every
 * E2E test calls {@link #reset()} in {@code @AfterEach}.
 */
public final class StubMode {

    private StubMode() {
    }

    public static void set(String mode) {
        E2eClients.get().stub().put().uri("/_stub/mode")
                .contentType(MediaType.TEXT_PLAIN)
                .body(mode)
                .retrieve()
                .toBodilessEntity();
    }

    public static String current() {
        String body = E2eClients.get().stub().get().uri("/_stub/mode").retrieve().body(String.class);
        return body == null ? null : body.trim();
    }

    public static void fail() {
        set("fail");
    }

    public static void ok() {
        set("ok");
    }

    /** Back to {@code ok}, the mode the stand-in starts in. */
    public static void reset() {
        ok();
    }
}
