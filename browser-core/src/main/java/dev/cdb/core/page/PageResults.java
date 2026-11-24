package dev.cdb.core.page;

import dev.cdb.protocol.Bounds;
import dev.cdb.protocol.DomElement;
import dev.cdb.protocol.DomSnapshot;
import dev.cdb.protocol.ElementDetails;
import dev.cdb.protocol.FormFieldInfo;
import dev.cdb.protocol.SnapshotMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Converts page helper results (plain maps) into protocol records. */
public final class PageResults {
    private PageResults() {
    }

    @SuppressWarnings("unchecked")
    public static DomSnapshot snapshot(Map<String, Object> res, String tabId, SnapshotMode mode) {
        List<Map<String, Object>> raw = (List<Map<String, Object>>) res.getOrDefault("elements", List.of());
        List<DomElement> elements = new ArrayList<>(raw.size());
        for (Map<String, Object> m : raw) {
            elements.add(new DomElement(str(m.get("reference")), str(m.get("parent")), num(m.get("depth")), str(m.get("tag")),
                    str(m.get("role")), str(m.get("name")), str(m.get("text")), Boolean.TRUE.equals(m.get("visible")),
                    Boolean.TRUE.equals(m.get("interactive")), Boolean.TRUE.equals(m.get("disabled")),
                    m.get("checked") instanceof Boolean b ? b : null, str(m.get("value")),
                    strMap((Map<String, Object>) m.get("attributes")), bounds((Map<String, Object>) m.get("bounds"))));
        }
        return new DomSnapshot(str(res.get("title")), str(res.get("url")), tabId, mode, elements.size(),
                Boolean.TRUE.equals(res.get("truncated")), elements, null);
    }

    @SuppressWarnings("unchecked")
    public static ElementDetails details(Map<String, Object> m) {
        Map<String, Object> ff = (Map<String, Object>) m.get("formField");
        FormFieldInfo form = ff == null ? null : new FormFieldInfo(str(ff.get("type")), str(ff.get("name")), str(ff.get("label")),
                Boolean.TRUE.equals(ff.get("required")), str(ff.get("placeholder")), str(ff.get("value")),
                Boolean.TRUE.equals(ff.get("sensitive")), !Boolean.FALSE.equals(ff.get("valid")), str(ff.get("validationMessage")));
        Map<String, String> attrs = strMap((Map<String, Object>) m.get("attributes"));
        if (form != null && form.sensitive() && attrs != null) attrs.remove("value");
        return new ElementDetails(str(m.get("reference")), str(m.get("tag")), str(m.get("role")), str(m.get("name")),
                form != null && form.sensitive() ? null : str(m.get("text")), Boolean.TRUE.equals(m.get("visible")),
                Boolean.TRUE.equals(m.get("enabled")), Boolean.TRUE.equals(m.get("editable")),
                m.get("checked") instanceof Boolean b ? b : null, attrs, strMap((Map<String, Object>) m.get("computedStyles")),
                bounds((Map<String, Object>) m.get("bounds")), form, (List<String>) m.get("ancestors"), num(m.get("childCount")));
    }

    public static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    static int num(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    static Map<String, String> strMap(Map<String, Object> m) {
        if (m == null) return null;
        Map<String, String> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(k, v == null ? "" : String.valueOf(v)));
        return out;
    }

    static Bounds bounds(Map<String, Object> m) {
        if (m == null) return null;
        return new Bounds(dbl(m.get("x")), dbl(m.get("y")), dbl(m.get("width")), dbl(m.get("height")));
    }

    private static double dbl(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }
}
