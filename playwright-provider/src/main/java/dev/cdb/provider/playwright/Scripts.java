package dev.cdb.provider.playwright;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** JavaScript resources evaluated in pages. */
final class Scripts {
    static final String HELPERS = load("/cdb/page-helpers.js");
    static final String SNAPSHOT = "(args) => (" + HELPERS + ").snapshot(args)";
    static final String INSPECT = "(el, props) => (" + HELPERS + ").inspect(el, props)";
    static final String AUDIT = "() => (" + HELPERS + ").audit()";
    static final String READY_STATE = "() => document.readyState";
    static final String REJECTION_HOOK = """
            (() => {
              if (window.__cdbRejectionHook) return;
              Object.defineProperty(window, '__cdbRejectionHook', { value: true, enumerable: false });
              window.addEventListener('unhandledrejection', (e) => {
                try {
                  const r = e.reason;
                  const message = r instanceof Error ? (r.name + ': ' + r.message) : String(r);
                  const stack = r instanceof Error ? (r.stack || '') : '';
                  if (typeof window.__cdbReportRejection === 'function') window.__cdbReportRejection(message, stack);
                } catch (_) { /* never break the page */ }
              });
            })();
            """;

    private Scripts() {
    }

    private static String load(String path) {
        try (InputStream in = Scripts.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("Missing resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
