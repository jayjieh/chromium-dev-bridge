package dev.cdb.protocol;

import java.util.List;
import java.util.Map;

public record ElementDetails(String reference, String tag, String role, String name, String text, boolean visible,
                             boolean enabled, boolean editable, Boolean checked, Map<String, String> attributes,
                             Map<String, String> computedStyles, Bounds bounds, FormFieldInfo formField,
                             List<String> ancestors, int childCount) {
}
