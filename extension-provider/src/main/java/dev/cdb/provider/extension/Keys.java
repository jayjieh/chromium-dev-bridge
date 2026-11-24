package dev.cdb.provider.extension;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Translates Playwright-style key strings ("Enter", "Control+A", "Shift+Tab") into CDP key events. */
final class Keys {
    record Key(String key, String code, int keyCode, String text) { }

    record Press(List<Key> modifiers, int modifierMask, Key key) { }

    private static final Map<String, Key> NAMED = Map.ofEntries(
            Map.entry("enter", new Key("Enter", "Enter", 13, "\r")),
            Map.entry("tab", new Key("Tab", "Tab", 9, null)),
            Map.entry("escape", new Key("Escape", "Escape", 27, null)),
            Map.entry("backspace", new Key("Backspace", "Backspace", 8, null)),
            Map.entry("delete", new Key("Delete", "Delete", 46, null)),
            Map.entry("arrowup", new Key("ArrowUp", "ArrowUp", 38, null)),
            Map.entry("arrowdown", new Key("ArrowDown", "ArrowDown", 40, null)),
            Map.entry("arrowleft", new Key("ArrowLeft", "ArrowLeft", 37, null)),
            Map.entry("arrowright", new Key("ArrowRight", "ArrowRight", 39, null)),
            Map.entry("home", new Key("Home", "Home", 36, null)),
            Map.entry("end", new Key("End", "End", 35, null)),
            Map.entry("pageup", new Key("PageUp", "PageUp", 33, null)),
            Map.entry("pagedown", new Key("PageDown", "PageDown", 34, null)),
            Map.entry("space", new Key(" ", "Space", 32, " ")),
            Map.entry(" ", new Key(" ", "Space", 32, " ")));
    private static final Map<String, Object[]> MODIFIERS = Map.of(
            "alt", new Object[]{new Key("Alt", "AltLeft", 18, null), 1},
            "control", new Object[]{new Key("Control", "ControlLeft", 17, null), 2},
            "ctrl", new Object[]{new Key("Control", "ControlLeft", 17, null), 2},
            "meta", new Object[]{new Key("Meta", "MetaLeft", 91, null), 4},
            "shift", new Object[]{new Key("Shift", "ShiftLeft", 16, null), 8});

    private Keys() {
    }

    static Press parse(String spec) {
        if (spec == null || spec.isEmpty()) throw new IllegalArgumentException("key is required");
        String[] parts = spec.equals("+") ? new String[]{"+"} : spec.split("\\+(?!$)");
        List<Key> mods = new ArrayList<>();
        int mask = 0;
        for (int i = 0; i < parts.length - 1; i++) {
            Object[] m = MODIFIERS.get(parts[i].toLowerCase(Locale.ROOT));
            if (m == null) throw new IllegalArgumentException("Unknown modifier: " + parts[i]);
            mods.add((Key) m[0]);
            mask |= (Integer) m[1];
        }
        return new Press(mods, mask, key(parts[parts.length - 1], (mask & 8) != 0));
    }

    static Key key(String name, boolean shift) {
        String lower = name.toLowerCase(Locale.ROOT);
        Key named = NAMED.get(lower);
        if (named != null) return named;
        if (lower.matches("f([1-9]|1[0-2])")) {
            int n = Integer.parseInt(lower.substring(1));
            return new Key("F" + n, "F" + n, 111 + n, null);
        }
        if (name.length() == 1) {
            char c = name.charAt(0);
            String text = shift ? name.toUpperCase(Locale.ROOT) : name;
            if (Character.isLetter(c)) {
                return new Key(text, "Key" + Character.toUpperCase(c), Character.toUpperCase(c), text);
            }
            if (Character.isDigit(c)) return new Key(name, "Digit" + c, c, name);
            return new Key(name, "", c, name);
        }
        throw new IllegalArgumentException("Unknown key: " + name);
    }

    /** Key for typing one character (no named-key lookup). */
    static Key character(char c) {
        String s = String.valueOf(c);
        if (c == '\n') return NAMED.get("enter");
        if (Character.isLetter(c)) return new Key(s, "Key" + Character.toUpperCase(c), Character.toUpperCase(c), s);
        if (Character.isDigit(c)) return new Key(s, "Digit" + c, c, s);
        return new Key(s, "", 0, s);
    }
}
