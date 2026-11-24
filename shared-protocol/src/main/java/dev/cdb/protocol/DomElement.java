package dev.cdb.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DomElement(String reference, String parent, int depth, String tag, String role, String name, String text,
                         boolean visible, boolean interactive, boolean disabled, Boolean checked, String value,
                         Map<String, String> attributes, Bounds bounds) {
}
