package dev.cdb.provider.playwright;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.AriaRole;
import dev.cdb.core.BridgeException;
import dev.cdb.protocol.ErrorCode;
import dev.cdb.provider.playwright.discovery.BrowserDiscovery;
import org.junit.jupiter.api.Test;

class PlaywrightUnitTest {
    @Test
    void mapsAriaRoles() {
        assertThat(Locators.role("textbox")).isEqualTo(AriaRole.TEXTBOX);
        assertThat(Locators.role("menuitem-checkbox")).isEqualTo(AriaRole.MENUITEMCHECKBOX);
        assertThatThrownBy(() -> Locators.role("bogus")).isInstanceOf(BridgeException.class);
    }

    @Test
    void translatesErrors() {
        assertThat(PlaywrightErrors.translate(new PlaywrightException("net::ERR_BLOCKED_BY_CLIENT at x\nCall log:\n..."), "nav").code())
                .isEqualTo(ErrorCode.URL_NOT_ALLOWED);
        assertThat(PlaywrightErrors.translate(new PlaywrightException("Error: strict mode violation: 2 elements"), "click").code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(PlaywrightErrors.firstLine(new PlaywrightException("boom happened here\nCall log:\n - waiting")))
                .isEqualTo("boom happened here");
    }

    @Test
    void helperScriptsAreBundled() {
        assertThat(Scripts.SNAPSHOT).contains("snapshot(args)").contains("__cdbRefs");
        assertThat(PlaywrightWorker.REF_ENGINE).contains("queryAll");
    }

    @Test
    void discoveryDoesNotFail() {
        assertThat(BrowserDiscovery.discover()).isNotNull();
    }
}
