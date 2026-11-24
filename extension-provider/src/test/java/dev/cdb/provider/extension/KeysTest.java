package dev.cdb.provider.extension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class KeysTest {
    @Test
    void parsesNamedKeysAndModifiers() {
        Keys.Press enter = Keys.parse("Enter");
        assertThat(enter.key().key()).isEqualTo("Enter");
        assertThat(enter.key().text()).isEqualTo("\r");
        assertThat(enter.modifierMask()).isZero();

        Keys.Press selectAll = Keys.parse("Control+A");
        assertThat(selectAll.modifierMask()).isEqualTo(2);
        assertThat(selectAll.modifiers()).extracting(Keys.Key::key).containsExactly("Control");
        assertThat(selectAll.key().code()).isEqualTo("KeyA");

        assertThat(Keys.parse("Shift+Tab").modifierMask()).isEqualTo(8);
        assertThat(Keys.parse("F5").key().keyCode()).isEqualTo(116);
        assertThat(Keys.parse("+").key().key()).isEqualTo("+");
        assertThatThrownBy(() -> Keys.parse("Hyper+X")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Keys.parse("NotAKey")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void characters() {
        assertThat(Keys.character('a').code()).isEqualTo("KeyA");
        assertThat(Keys.character('7').code()).isEqualTo("Digit7");
        assertThat(Keys.character('@').text()).isEqualTo("@");
    }
}
