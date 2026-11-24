package dev.cdb.core.page;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** The shared page helper script (snapshot, inspect, audit, locators, actions) as evaluable expressions. */
public final class PageScript {
    public static final String HELPERS = load();

    private PageScript() {
    }

    /** Expression calling helpers.fn(args...) where args are already JSON literals. */
    public static String call(String fn, String... jsonArgs) {
        return "(" + HELPERS + ")." + fn + "(" + String.join(", ", jsonArgs) + ")";
    }

    /** Expression applying a JS function source "H => ..." to the helpers object. */
    public static String wrap(String functionSource) {
        return "(" + functionSource + ")(" + HELPERS + ")";
    }

    private static String load() {
        try (InputStream in = PageScript.class.getResourceAsStream("/cdb/page-helpers.js")) {
            if (in == null) throw new IllegalStateException("Missing /cdb/page-helpers.js");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
