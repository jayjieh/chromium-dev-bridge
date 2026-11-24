package dev.cdb.provider.playwright;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import dev.cdb.core.BridgeException;
import dev.cdb.protocol.Locator;
import java.util.Locale;

/** Converts protocol locators to Playwright locators. Semantic strategies first; XPath last. */
final class Locators {
    private Locators() {
    }

    static com.microsoft.playwright.Locator resolve(Page page, Locator l) {
        if (l == null || l.isEmpty()) throw BridgeException.validation("locator is required");
        boolean exact = Boolean.TRUE.equals(l.exact());
        com.microsoft.playwright.Locator loc;
        if (notBlank(l.ref())) {
            if (!l.ref().matches("element-\\d+")) throw BridgeException.validation("Invalid ref: " + l.ref());
            loc = page.locator("cdbref=" + l.ref());
        } else if (notBlank(l.role())) {
            Page.GetByRoleOptions o = new Page.GetByRoleOptions();
            if (l.name() != null) o.setName(l.name()).setExact(exact);
            loc = page.getByRole(role(l.role()), o);
        } else if (notBlank(l.testId())) {
            loc = page.getByTestId(l.testId());
        } else if (notBlank(l.label())) {
            loc = page.getByLabel(l.label(), new Page.GetByLabelOptions().setExact(exact));
        } else if (notBlank(l.placeholder())) {
            loc = page.getByPlaceholder(l.placeholder(), new Page.GetByPlaceholderOptions().setExact(exact));
        } else if (notBlank(l.text())) {
            loc = page.getByText(l.text(), new Page.GetByTextOptions().setExact(exact));
        } else if (notBlank(l.css())) {
            loc = page.locator(l.css());
        } else {
            loc = page.locator("xpath=" + l.xpath());
        }
        return l.nth() != null ? loc.nth(l.nth()) : loc;
    }

    static AriaRole role(String role) {
        try {
            return AriaRole.valueOf(role.trim().toUpperCase(Locale.ROOT).replace("-", ""));
        } catch (IllegalArgumentException e) {
            throw BridgeException.validation("Unknown ARIA role: " + role);
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
