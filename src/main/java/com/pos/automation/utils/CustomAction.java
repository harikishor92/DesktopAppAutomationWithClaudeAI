package com.pos.automation.utils;

import org.openqa.selenium.By;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.FluentWait;
import org.openqa.selenium.WebElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * Centralized library of reusable UI interaction methods for WinAppDriver automation.
 *
 * All waits use {@link FluentWait} — no {@code Thread.sleep}. All methods add SLF4J
 * logging and wrap failures with descriptive {@link RuntimeException} messages.
 *
 * WinAppDriver-specific design notes:
 * <ul>
 *   <li>{@link #highlightElement} — logs element attributes; JS execution is not supported</li>
 *   <li>{@link #selectDropdown} — clicks the combo to open, then locates ListItem by exact Name</li>
 *   <li>{@link #acceptAlert}/{@link #dismissAlert} — WinForms dialogs are Window elements,
 *       not browser alerts; {@code driver.switchTo().alert()} is not supported</li>
 *   <li>{@link #scrollToElement} — attempts Actions.moveToElement; warns on failure (partial support)</li>
 * </ul>
 */
public class CustomAction {

    private static final Logger log = LoggerFactory.getLogger(CustomAction.class);

    private static final int DEFAULT_TIMEOUT_SECONDS = 10;

    private final RemoteWebDriver driver;

    public CustomAction(RemoteWebDriver driver) {
        this.driver = driver;
    }

    // ── Click ────────────────────────────────────────────────────────────────────

    /** Waits up to {@value #DEFAULT_TIMEOUT_SECONDS}s for the element then clicks it. */
    public void click(By locator) {
        click(locator, DEFAULT_TIMEOUT_SECONDS);
    }

    /** Waits up to {@code timeoutSeconds} for the element then clicks it. */
    public void click(By locator, int timeoutSeconds) {
        log.info("Clicking element: {}", locator);
        WebElement element = findWithWait(locator, timeoutSeconds);
        if (element == null) {
            throw new RuntimeException("Click failed — element not found within " + timeoutSeconds + "s: " + locator);
        }
        element.click();
        log.debug("Click successful: {}", locator);
    }

    // ── Text Input ───────────────────────────────────────────────────────────────

    /** Sends {@code text} to the element without clearing it first. */
    public void enterText(By locator, String text) {
        log.info("Entering text '{}' into: {}", text, locator);
        WebElement element = findWithWait(locator, DEFAULT_TIMEOUT_SECONDS);
        if (element == null) {
            throw new RuntimeException("enterText failed — element not found: " + locator);
        }
        element.sendKeys(text);
        log.debug("Text entered successfully");
    }

    /** Clears the element then sends {@code text}. */
    public void clearAndEnterText(By locator, String text) {
        log.info("Clearing and entering text '{}' into: {}", text, locator);
        WebElement element = findWithWait(locator, DEFAULT_TIMEOUT_SECONDS);
        if (element == null) {
            throw new RuntimeException("clearAndEnterText failed — element not found: " + locator);
        }
        element.clear();
        element.sendKeys(text);
        log.debug("Clear-and-enter successful");
    }

    // ── Waits ────────────────────────────────────────────────────────────────────

    /**
     * Waits up to {@code timeoutSeconds} for the element to be present in the UI tree.
     * Returns the element, or {@code null} if the timeout elapses.
     */
    public WebElement waitForElement(By locator, int timeoutSeconds) {
        log.debug("Waiting up to {}s for element: {}", timeoutSeconds, locator);
        return findWithWait(locator, timeoutSeconds);
    }

    /**
     * Waits up to {@code timeoutSeconds} for the element to be present and enabled.
     * Returns the element, or {@code null} if the timeout elapses.
     */
    public WebElement waitForElementToBeClickable(By locator, int timeoutSeconds) {
        log.debug("Waiting up to {}s for clickable element: {}", timeoutSeconds, locator);
        try {
            return new FluentWait<>(driver)
                    .withTimeout(Duration.ofSeconds(timeoutSeconds))
                    .pollingEvery(Duration.ofMillis(500))
                    .ignoring(Exception.class)
                    .until(d -> {
                        List<WebElement> elements = d.findElements(locator);
                        if (elements.isEmpty()) return null;
                        WebElement el = elements.get(0);
                        return el.isEnabled() ? el : null;
                    });
        } catch (Exception e) {
            log.debug("waitForElementToBeClickable timed out for: {}", locator);
            return null;
        }
    }

    // ── State Checks ─────────────────────────────────────────────────────────────

    /** Returns {@code true} if the element is present and {@code isDisplayed()} returns true. */
    public boolean isElementDisplayed(By locator) {
        try {
            List<WebElement> elements = driver.findElements(locator);
            return !elements.isEmpty() && elements.get(0).isDisplayed();
        } catch (Exception e) {
            log.debug("isElementDisplayed check failed for {}: {}", locator, e.getMessage());
            return false;
        }
    }

    /** Returns {@code true} if the element is present and {@code isEnabled()} returns true. */
    public boolean isElementEnabled(By locator) {
        try {
            List<WebElement> elements = driver.findElements(locator);
            return !elements.isEmpty() && elements.get(0).isEnabled();
        } catch (Exception e) {
            log.debug("isElementEnabled check failed for {}: {}", locator, e.getMessage());
            return false;
        }
    }

    // ── Dropdown ─────────────────────────────────────────────────────────────────

    /**
     * Selects an option from a WinForms ComboBox.
     * Clicks the dropdown to open it, then locates the list item by exact Name and clicks it.
     * Does NOT use the Selenium {@code Select} class — that is HTML-only and not supported.
     */
    public void selectDropdown(By dropdownLocator, String optionText) {
        log.info("Selecting '{}' from dropdown: {}", optionText, dropdownLocator);
        click(dropdownLocator);
        By optionLocator = By.xpath("//ListItem[@Name='" + optionText + "']");
        WebElement option = findWithWait(optionLocator, DEFAULT_TIMEOUT_SECONDS);
        if (option == null) {
            throw new RuntimeException(
                    "Dropdown option '" + optionText + "' not found after opening dropdown: " + dropdownLocator);
        }
        option.click();
        log.info("Dropdown option '{}' selected", optionText);
    }

    // ── Keyboard ─────────────────────────────────────────────────────────────────

    /** Sends a key (or key combination) to the element without clearing it first. */
    public void pressKey(By locator, CharSequence key) {
        log.debug("Sending key '{}' to: {}", key, locator);
        WebElement element = findWithWait(locator, DEFAULT_TIMEOUT_SECONDS);
        if (element == null) {
            throw new RuntimeException("pressKey failed — element not found: " + locator);
        }
        element.sendKeys(key);
    }

    // ── Scroll ───────────────────────────────────────────────────────────────────

    /**
     * Attempts to scroll to the element using {@link Actions#moveToElement}.
     * WinAppDriver has partial Actions support — this is best-effort and logs a warning on failure.
     */
    public void scrollToElement(WebElement element) {
        try {
            new Actions(driver).moveToElement(element).perform();
            log.debug("Scrolled to element successfully");
        } catch (Exception e) {
            log.warn("scrollToElement failed (WinAppDriver Actions support is limited): {}", e.getMessage());
        }
    }

    // ── Visual Debug ─────────────────────────────────────────────────────────────

    /**
     * Logs the element's Name and AutomationId attributes for visual debugging.
     * JavaScript execution is not supported by WinAppDriver, so DOM-based highlighting
     * is not possible. Attribute logging provides equivalent traceability in CI logs.
     */
    public void highlightElement(WebElement element) {
        try {
            String name         = element.getDomAttribute("Name");
            String automationId = element.getDomAttribute("AutomationId");
            String controlType  = element.getDomAttribute("ControlType");
            log.info("[HIGHLIGHT] Element — Name='{}' AutomationId='{}' ControlType='{}'",
                    name, automationId, controlType);
        } catch (Exception e) {
            log.debug("highlightElement attribute read failed: {}", e.getMessage());
        }
    }

    // ── Screenshot ───────────────────────────────────────────────────────────────

    /**
     * Captures a full-desktop screenshot via {@link ScreenshotUtil} and returns the file path.
     * @param label descriptive label embedded in the filename
     * @return absolute path of the saved PNG, or {@code null} if capture failed
     */
    public String takeScreenshot(String label) {
        return ScreenshotUtil.capture(label);
    }

    // ── Window Handling ──────────────────────────────────────────────────────────

    /**
     * Switches to the first window handle whose title contains {@code titleFragment}.
     * Iterates all current window handles and compares titles.
     */
    public void switchToWindow(String titleFragment) {
        log.info("Switching to window with title fragment: '{}'", titleFragment);
        Set<String> handles = driver.getWindowHandles();
        for (String handle : handles) {
            try {
                driver.switchTo().window(handle);
                String title = driver.getTitle();
                if (title != null && title.contains(titleFragment)) {
                    log.info("Switched to window: '{}' (handle={})", title, handle);
                    return;
                }
            } catch (Exception e) {
                log.debug("Window handle {} unreachable: {}", handle, e.getMessage());
            }
        }
        throw new RuntimeException(
                "No window with title containing '" + titleFragment + "' found among " + handles.size() + " handles");
    }

    // ── Alert Handling ───────────────────────────────────────────────────────────

    /**
     * Accepts a WinForms dialog by clicking a Yes/OK button.
     * WinAppDriver does not support {@code driver.switchTo().alert()} — dialogs are Window elements.
     */
    public void acceptAlert() {
        log.info("Accepting dialog (clicking Yes/OK)");
        By acceptLocator = By.xpath("//Button[@Name='OK' or @Name='Yes' or @Name='&Yes']");
        WebElement btn = findWithWait(acceptLocator, DEFAULT_TIMEOUT_SECONDS);
        if (btn == null) {
            throw new RuntimeException("Accept button (OK/Yes) not found in any visible dialog");
        }
        btn.click();
        log.info("Dialog accepted");
    }

    /**
     * Dismisses a WinForms dialog by clicking a Cancel/No button.
     */
    public void dismissAlert() {
        log.info("Dismissing dialog (clicking Cancel/No)");
        By dismissLocator = By.xpath("//Button[@Name='Cancel' or @Name='No' or @Name='&No']");
        WebElement btn = findWithWait(dismissLocator, DEFAULT_TIMEOUT_SECONDS);
        if (btn == null) {
            throw new RuntimeException("Dismiss button (Cancel/No) not found in any visible dialog");
        }
        btn.click();
        log.info("Dialog dismissed");
    }

    // ── Getters ──────────────────────────────────────────────────────────────────

    /** Returns the visible text of the element, or empty string on failure. */
    public String getElementText(By locator) {
        log.debug("Getting text from: {}", locator);
        WebElement element = findWithWait(locator, DEFAULT_TIMEOUT_SECONDS);
        if (element == null) {
            log.warn("getElementText — element not found: {}", locator);
            return "";
        }
        String text = element.getText();
        log.debug("Element text: '{}'", text);
        return text != null ? text : "";
    }

    /** Returns the named attribute value of the element, or empty string on failure. */
    public String getAttribute(By locator, String attributeName) {
        log.debug("Getting attribute '{}' from: {}", attributeName, locator);
        WebElement element = findWithWait(locator, DEFAULT_TIMEOUT_SECONDS);
        if (element == null) {
            log.warn("getAttribute — element not found: {}", locator);
            return "";
        }
        String value = element.getDomAttribute(attributeName);
        log.debug("Attribute '{}' = '{}'", attributeName, value);
        return value != null ? value : "";
    }

    // ── Private ──────────────────────────────────────────────────────────────────

    private WebElement findWithWait(By locator, int timeoutSeconds) {
        try {
            return new FluentWait<>(driver)
                    .withTimeout(Duration.ofSeconds(timeoutSeconds))
                    .pollingEvery(Duration.ofMillis(500))
                    .ignoring(Exception.class)
                    .until(d -> {
                        List<WebElement> elements = d.findElements(locator);
                        return elements.isEmpty() ? null : elements.get(0);
                    });
        } catch (Exception e) {
            log.debug("findWithWait timed out ({}s) for: {}", timeoutSeconds, locator);
            return null;
        }
    }
}
