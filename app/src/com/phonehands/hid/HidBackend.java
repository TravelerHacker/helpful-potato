package com.phonehands.hid;

import org.json.JSONObject;

/** A transport that can emit keyboard + mouse reports to a target computer. */
public interface HidBackend {
    String name();
    /** True when reports will reach a host right now. */
    boolean ready();
    /** 8-byte boot keyboard report: mods, reserved, 6 keys. Throws IllegalStateException if not ready. */
    void keyboard(byte[] report);
    void mouseRel(int buttons, int dx, int dy, int wheel);
    /** x,y in 0..4095. Throws UnsupportedOperationException if not supported. */
    void mouseAbs(int buttons, int x, int y, int wheel);
    boolean supportsAbsolute();
    JSONObject status() throws Exception;
}
