package com.phonehands.hid;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** US-QWERTY HID usage mapping. */
public final class Keys {
    static final int SHIFT = 0x02;
    static final Map<String, Integer> NAMED = new HashMap<>();
    static final Map<String, Integer> MODS = new HashMap<>();
    static {
        MODS.put("ctrl", 0x01); MODS.put("control", 0x01); MODS.put("lctrl", 0x01); MODS.put("rctrl", 0x10);
        MODS.put("shift", 0x02); MODS.put("lshift", 0x02); MODS.put("rshift", 0x20);
        MODS.put("alt", 0x04); MODS.put("lalt", 0x04); MODS.put("option", 0x04); MODS.put("opt", 0x04);
        MODS.put("ralt", 0x40); MODS.put("altgr", 0x40);
        for (String g : new String[]{"win", "gui", "meta", "super", "cmd", "command", "windows", "lwin"}) MODS.put(g, 0x08);
        MODS.put("rwin", 0x80); MODS.put("rgui", 0x80); MODS.put("rcmd", 0x80);
        String[][] n = {
            {"enter", "40"}, {"return", "40"}, {"esc", "41"}, {"escape", "41"}, {"backspace", "42"}, {"bksp", "42"},
            {"tab", "43"}, {"space", "44"}, {"spacebar", "44"}, {"minus", "45"}, {"equal", "46"}, {"equals", "46"},
            {"leftbracket", "47"}, {"rightbracket", "48"}, {"backslash", "49"}, {"semicolon", "51"},
            {"quote", "52"}, {"apostrophe", "52"}, {"grave", "53"}, {"backtick", "53"}, {"comma", "54"},
            {"period", "55"}, {"dot", "55"}, {"slash", "56"}, {"capslock", "57"}, {"caps", "57"},
            {"printscreen", "70"}, {"prtsc", "70"}, {"print", "70"}, {"sysrq", "70"}, {"scrolllock", "71"},
            {"pause", "72"}, {"break", "72"}, {"insert", "73"}, {"ins", "73"}, {"home", "74"},
            {"pageup", "75"}, {"pgup", "75"}, {"delete", "76"}, {"del", "76"}, {"end", "77"},
            {"pagedown", "78"}, {"pgdn", "78"}, {"right", "79"}, {"left", "80"}, {"down", "81"}, {"up", "82"},
            {"numlock", "83"}, {"menu", "101"}, {"app", "101"}, {"apps", "101"}, {"contextmenu", "101"},
            {"plus", "46"}, {"mute", "127"}, {"volumeup", "128"}, {"volumedown", "129"},
            {"kpenter", "88"}, {"kp0", "98"},
        };
        for (String[] e : n) NAMED.put(e[0], Integer.parseInt(e[1]));
        for (int i = 1; i <= 12; i++) NAMED.put("f" + i, 0x3A + i - 1);
        for (int i = 13; i <= 24; i++) NAMED.put("f" + i, 0x68 + i - 13);
        for (int i = 1; i <= 9; i++) NAMED.put("kp" + i, 0x59 + i - 1);
    }

    /** Returns {usage, modifiers} for an ASCII char, or null. */
    static int[] ascii(char c) {
        if (c >= 'a' && c <= 'z') return new int[]{0x04 + (c - 'a'), 0};
        if (c >= 'A' && c <= 'Z') return new int[]{0x04 + (c - 'A'), SHIFT};
        if (c >= '1' && c <= '9') return new int[]{0x1E + (c - '1'), 0};
        switch (c) {
            case '0': return new int[]{0x27, 0};
            case '\n': return new int[]{0x28, 0};
            case '\t': return new int[]{0x2B, 0};
            case ' ': return new int[]{0x2C, 0};
            case '!': return new int[]{0x1E, SHIFT}; case '@': return new int[]{0x1F, SHIFT};
            case '#': return new int[]{0x20, SHIFT}; case '$': return new int[]{0x21, SHIFT};
            case '%': return new int[]{0x22, SHIFT}; case '^': return new int[]{0x23, SHIFT};
            case '&': return new int[]{0x24, SHIFT}; case '*': return new int[]{0x25, SHIFT};
            case '(': return new int[]{0x26, SHIFT}; case ')': return new int[]{0x27, SHIFT};
            case '-': return new int[]{0x2D, 0}; case '_': return new int[]{0x2D, SHIFT};
            case '=': return new int[]{0x2E, 0}; case '+': return new int[]{0x2E, SHIFT};
            case '[': return new int[]{0x2F, 0}; case '{': return new int[]{0x2F, SHIFT};
            case ']': return new int[]{0x30, 0}; case '}': return new int[]{0x30, SHIFT};
            case '\\': return new int[]{0x31, 0}; case '|': return new int[]{0x31, SHIFT};
            case ';': return new int[]{0x33, 0}; case ':': return new int[]{0x33, SHIFT};
            case '\'': return new int[]{0x34, 0}; case '"': return new int[]{0x34, SHIFT};
            case '`': return new int[]{0x35, 0}; case '~': return new int[]{0x35, SHIFT};
            case ',': return new int[]{0x36, 0}; case '<': return new int[]{0x36, SHIFT};
            case '.': return new int[]{0x37, 0}; case '>': return new int[]{0x37, SHIFT};
            case '/': return new int[]{0x38, 0}; case '?': return new int[]{0x38, SHIFT};
        }
        return null;
    }

    /** Parse "ctrl+alt+del" -> {mods, key1, key2...}. */
    static int[] parseCombo(String s) {
        String[] toks = s.toLowerCase(Locale.US).split("\\+");
        int mods = 0; int[] keys = new int[6]; int nk = 0;
        for (String t : toks) {
            t = t.trim();
            if (t.isEmpty()) continue;
            Integer m = MODS.get(t);
            if (m != null) { mods |= m; continue; }
            Integer k = NAMED.get(t);
            int usage;
            if (k != null) usage = k;
            else if (t.length() == 1 && ascii(t.charAt(0)) != null) {
                int[] a = ascii(t.charAt(0)); usage = a[0]; mods |= a[1];
            } else if (t.startsWith("0x")) usage = Integer.parseInt(t.substring(2), 16);
            else throw new IllegalArgumentException("unknown key '" + t + "'");
            if (nk < 6) keys[nk++] = usage;
        }
        int[] out = new int[nk + 1]; out[0] = mods; System.arraycopy(keys, 0, out, 1, nk);
        return out;
    }
}
