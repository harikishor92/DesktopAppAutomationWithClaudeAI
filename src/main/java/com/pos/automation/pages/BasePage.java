package com.pos.automation.pages;

import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.remote.RemoteWebDriver;

import java.util.Collections;
import java.util.List;

/**
 * Base class for all page objects.
 *
 * Provides the shared driver reference and a small set of common interaction
 * helpers so subclasses do not duplicate try/catch boilerplate or direct
 * driver calls for routine operations.
 *
 * Driver type is RemoteWebDriver (not the WebDriver interface) because WinAppDriver
 * requires RemoteWebDriver-specific APIs (getWindowHandles, manage().timeouts(), etc.)
 * and all sessions in this framework are RemoteWebDriver instances.
 */
public abstract class BasePage {

    protected final RemoteWebDriver driver;

    protected BasePage(RemoteWebDriver driver) {
        this.driver = driver;
    }

    /**
     * Returns the first element matching {@code locator}, or {@code null} if none found.
     * Prefers this over driver.findElement() to avoid unchecked exceptions in lookup code.
     */
    protected WebElement findElement(By locator) {
        List<WebElement> results = findElements(locator);
        return results.isEmpty() ? null : results.get(0);
    }

    /**
     * Returns all elements matching {@code locator}, or an empty list on any error.
     */
    protected List<WebElement> findElements(By locator) {
        try {
            return driver.findElements(locator);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /**
     * Returns true if at least one element matching {@code locator} exists.
     */
    protected boolean isElementPresent(By locator) {
        return !findElements(locator).isEmpty();
    }

    /**
     * Returns the current window title, or {@code null} on error.
     * Subclasses that need richer fallback behaviour (e.g. AlbertaPOSPage) override this.
     */
    public String getWindowTitle() {
        try {
            return driver.getTitle();
        } catch (Exception e) {
            return null;
        }
    }
}
