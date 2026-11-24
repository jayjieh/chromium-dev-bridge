package dev.cdb.protocol;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Element locator. Resolution order: ref, role (+name), testId, label, placeholder, text, css, xpath.
 * Semantic locators are preferred; XPath is a last resort.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Locator(String ref, String role, String name, Boolean exact, String testId, String label,
                      String placeholder, String text, String css, String xpath, Integer nth) {

    public static Locator ref(String ref) { return new Locator(ref, null, null, null, null, null, null, null, null, null, null); }
    public static Locator role(String role, String name) { return new Locator(null, role, name, null, null, null, null, null, null, null, null); }
    public static Locator testId(String id) { return new Locator(null, null, null, null, id, null, null, null, null, null, null); }
    public static Locator text(String text) { return new Locator(null, null, null, null, null, null, null, text, null, null, null); }
    public static Locator label(String label) { return new Locator(null, null, null, null, null, label, null, null, null, null, null); }
    public static Locator css(String css) { return new Locator(null, null, null, null, null, null, null, null, css, null, null); }
    public static Locator xpath(String xpath) { return new Locator(null, null, null, null, null, null, null, null, null, xpath, null); }

    @JsonIgnore
    public boolean isEmpty() {
        return blank(ref) && blank(role) && blank(testId) && blank(label) && blank(placeholder) && blank(text)
                && blank(css) && blank(xpath);
    }

    /** Short human-readable description, safe for logs (no values). */
    public String describe() {
        if (!blank(ref)) return "ref=" + ref;
        if (!blank(role)) return "role=" + role + (name != null ? "[name=\"" + name + "\"]" : "");
        if (!blank(testId)) return "testId=" + testId;
        if (!blank(label)) return "label=" + label;
        if (!blank(placeholder)) return "placeholder=" + placeholder;
        if (!blank(text)) return "text=" + text;
        if (!blank(css)) return "css=" + css;
        if (!blank(xpath)) return "xpath=" + xpath;
        return "<empty>";
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
