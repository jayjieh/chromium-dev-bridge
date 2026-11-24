package dev.cdb.core.console;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cdb.protocol.StackFrame;
import java.util.List;
import org.junit.jupiter.api.Test;

class StackTraceParserTest {
    @Test
    void parsesV8Stacks() {
        String stack = """
                TypeError: Cannot read properties of undefined (reading 'map')
                    at renderList (http://localhost:3000/static/js/main.js:120:15)
                    at async loadDashboard (http://localhost:3000/static/js/main.js:88:3)
                    at http://localhost:3000/static/js/vendor.js:1:2000
                """;
        List<StackFrame> frames = StackTraceParser.parse(stack);
        assertThat(frames).hasSize(3);
        assertThat(frames.get(0).function()).isEqualTo("renderList");
        assertThat(frames.get(0).location().file()).isEqualTo("main.js");
        assertThat(frames.get(0).location().line()).isEqualTo(120);
        assertThat(frames.get(0).location().column()).isEqualTo(15);
        assertThat(frames.get(1).function()).isEqualTo("loadDashboard");
        assertThat(frames.get(2).function()).isNull();
        assertThat(StackTraceParser.message(stack)).startsWith("TypeError: Cannot read");
    }

    @Test
    void parsesGeckoStacks() {
        List<StackFrame> frames = StackTraceParser.parse("render@http://localhost:3000/app.js:10:5\n@http://localhost:3000/app.js:1:1");
        assertThat(frames).hasSize(2);
        assertThat(frames.get(0).function()).isEqualTo("render");
    }
}
