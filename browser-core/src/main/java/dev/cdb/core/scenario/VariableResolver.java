package dev.cdb.core.scenario;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Substitutes ${NAME} placeholders. Lookup order: selected fixture, scenario variables, environment variables.
 * Only environment variables prefixed TEST_ or CDB_TEST_ are visible, so scenarios cannot read arbitrary secrets of
 * the driver process. Every substituted value is remembered so reports can mask it.
 */
public class VariableResolver {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");
    private final Map<String, String> vars = new HashMap<>();
    private final Function<String, String> env;
    private final Set<String> substituted = new LinkedHashSet<>();

    public VariableResolver(Map<String, String> fixture, Map<String, String> variables, Function<String, String> env) {
        if (variables != null) vars.putAll(variables);
        if (fixture != null) vars.putAll(fixture);
        this.env = env;
    }

    public String resolve(String input) {
        if (input == null || !input.contains("${")) return input;
        Matcher m = PLACEHOLDER.matcher(input);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String value = vars.get(name);
            if (value == null && (name.startsWith("TEST_") || name.startsWith("CDB_TEST_"))) value = env.apply(name);
            if (value == null) throw new IllegalArgumentException("Unresolved variable ${" + name + "}");
            if (!value.isEmpty()) substituted.add(value);
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Replaces every substituted value in text with ***. */
    public String mask(String text) {
        if (text == null) return null;
        String out = text;
        for (String v : substituted) {
            if (v.length() >= 3) out = out.replace(v, "***");
        }
        return out;
    }
}
