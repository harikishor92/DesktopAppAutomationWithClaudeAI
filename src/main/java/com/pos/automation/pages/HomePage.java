package com.pos.automation.pages;

import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Page object for the AlbertaPOS main Home Page.
 *
 * Must be constructed with a driver obtained from {@link AlbertaPOSPage#attachToMainWindow()}
 * because AlbertaPOS's main window lives in a separate process from the login launcher.
 *
 * All locators use WinAppDriver UIAutomation AutomationId strategies confirmed via
 * WinAppDriver REST API inspection against the live AlbertaPOS Home window.
 *
 * <h3>Toolbar architecture</h3>
 * The toolbar container is {@code AutomationId='toolbar'} (Name='Action Toolbar').
 * Individual toolbar buttons are individually accessible by AutomationId:
 * {@code toolbar_Home}, {@code toolbar_Back} (the Next button), {@code toolbar_Settings}, etc.
 * Navigation uses direct UIAutomation element clicks — no Robot or screen coordinates needed.
 *
 * <h3>Implicit-wait strategy</h3>
 * WinAppDriver does not honour {@code implicitlyWait(Duration.ZERO)}; the minimum effective
 * polling floor is 500 ms. All multi-element lookups use {@link #withReducedWait(Supplier)}
 * which drops the wait to {@value #ELEMENT_LOOKUP_TIMEOUT_MS} ms and restores it to
 * {@value #DEFAULT_IMPLICIT_WAIT_S} s in a {@code finally} block.
 */
public class HomePage extends BasePage {

    private static final Logger log = LoggerFactory.getLogger(HomePage.class);

    // ── Implicit-wait constants ───────────────────────────────────────────────────
    private static final int ELEMENT_LOOKUP_TIMEOUT_MS = 500;
    private static final int DEFAULT_IMPLICIT_WAIT_S   = 15;

    // ── Generic locators ──────────────────────────────────────────────────────────
    static final By ALL_BUTTONS     = By.xpath("//Button");
    static final By ALL_MENU_BARS   = By.xpath("//Menu");
    static final By ALL_TEXT_LABELS = By.xpath("//Text");
    static final By ALL_WINDOWS     = By.xpath("//Window");

    // ── Toolbar container — AutomationId confirmed via WinAppDriver inspection ────
    static final By TOOLBAR_CONTAINER = By.xpath("//*[@AutomationId='toolbar']");

    // ── Toolbar navigation buttons — individually accessible by AutomationId ──────
    private static final By TOOLBAR_HOME = By.xpath("//*[@AutomationId='toolbar_Home']");
    private static final By TOOLBAR_NEXT = By.xpath("//*[@AutomationId='toolbar_Back']");

    // ── Search bar — AutomationId confirmed ──────────────────────────────────────
    static final By SEARCH_BAR = By.xpath("//*[@AutomationId='txtItemLookup']");

    // ── Payment area — AutomationId confirmed via WinAppDriver inspection ─────────
    static final By PAY_BUTTON       = By.xpath("//*[@AutomationId='btnCashpay']");
    static final By CARD_BUTTON      = By.xpath("//*[@AutomationId='btnCardpay']");
    static final By NEXT_DOLLAR_BTN  = By.xpath("//*[@AutomationId='btnNext']");
    static final By EXACT_DOLLAR_BTN = By.xpath("//*[@AutomationId='btnExact']");
    static final By REFUND_BTN       = By.xpath("//*[@AutomationId='btnRefund']");

    // ── Group navigation — AutomationId confirmed (GROUP1–GROUP5) ────────────────
    private static final By GROUP_A = By.xpath("//*[@AutomationId='GROUP1']");
    private static final By GROUP_B = By.xpath("//*[@AutomationId='GROUP2']");
    private static final By GROUP_C = By.xpath("//*[@AutomationId='GROUP3']");
    private static final By GROUP_D = By.xpath("//*[@AutomationId='GROUP4']");
    private static final By GROUP_E = By.xpath("//*[@AutomationId='GROUP5']");

    public static final List<By> GROUP_NAV_LOCATORS = List.of(
            GROUP_A, GROUP_B, GROUP_C, GROUP_D, GROUP_E);

    // ── Quick-pay amounts — AutomationId confirmed via WinAppDriver inspection ────
    // Grouped under AutomationId='currencyAmountButtons' (Name='Currency Amount Buttons')
    private static final By SHADOW_BTN_1 = By.xpath("//*[@AutomationId='btnTenderOne']");
    private static final By SHADOW_BTN_2 = By.xpath("//*[@AutomationId='btnTenderFive']");
    private static final By SHADOW_BTN_3 = By.xpath("//*[@AutomationId='btnTenderTen']");
    private static final By SHADOW_BTN_4 = By.xpath("//*[@AutomationId='btnTenderTwenty']");
    private static final By SHADOW_BTN_5 = By.xpath("//*[@AutomationId='btnTenderFifty']");
    private static final By SHADOW_BTN_6 = By.xpath("//*[@AutomationId='btnTenderHundred']");

    public static final List<By> QUICK_PAY_LOCATORS = List.of(
            SHADOW_BTN_1, SHADOW_BTN_2, SHADOW_BTN_3,
            SHADOW_BTN_4, SHADOW_BTN_5, SHADOW_BTN_6);

    // ── Error dialog detection ────────────────────────────────────────────────────
    private static final Set<String> KNOWN_ERROR_DIALOG_TITLES = Set.of(
            "Error", "Warning", "Alert", "AlbertaPOS Error", "AlbertaPOS Warning");

    private static final Set<String> ERROR_KEYWORDS = Set.of(
            "error", "warning", "alert", "exception");

    // ─────────────────────────────────────────────────────────────────────────────

    public HomePage(RemoteWebDriver driver) {
        super(driver);
    }

    // ── State checks ──────────────────────────────────────────────────────────────

    /**
     * Returns {@code true} when the home page window is active and has a non-blank title.
     */
    public boolean isDisplayed() {
        String title = getWindowTitle();
        if (title == null || title.isBlank()) {
            log.warn("Home page getTitle() returned blank/null");
            return false;
        }
        return true;
    }

    /**
     * Returns {@code true} if the toolbar container element is present in the UIAutomation tree.
     */
    public boolean isToolbarVisible() {
        return isElementPresent(TOOLBAR_CONTAINER);
    }

    /**
     * Checks whether an error dialog is currently visible.
     *
     * <p>Two-pass strategy:
     * <ol>
     *   <li>Fast exact-match against {@link #KNOWN_ERROR_DIALOG_TITLES}.</li>
     *   <li>Heuristic keyword scan for unknown runtime error titles.</li>
     * </ol>
     */
    public boolean isErrorDialogVisible() {
        for (WebElement window : findElements(ALL_WINDOWS)) {
            try {
                String name = window.getDomAttribute("Name");
                if (name == null || name.isBlank()) continue;

                if (KNOWN_ERROR_DIALOG_TITLES.contains(name)) {
                    log.warn("Error dialog detected — exact title match: '{}'", name);
                    return true;
                }
                String lower = name.toLowerCase();
                for (String keyword : ERROR_KEYWORDS) {
                    if (lower.contains(keyword)) {
                        log.warn("Error dialog detected — keyword '{}' in title: '{}'", keyword, name);
                        return true;
                    }
                }
            } catch (Exception ignored) {}
        }
        return false;
    }

    // ── Header navigation ──────────────────────────────────────────────────────────

    /**
     * Navigates to the next toolbar row by clicking the Next button (AutomationId='toolbar_Back').
     */
    public void clickNextHeader() {
        clickToolbarButton(TOOLBAR_NEXT, "Next");
    }

    /**
     * Resets the toolbar to Row 1 by clicking the Home button (AutomationId='toolbar_Home').
     */
    public void clickHomeHeader() {
        clickToolbarButton(TOOLBAR_HOME, "Home");
    }

    private void clickToolbarButton(By locator, String label) {
        WebElement btn = findElement(locator);
        if (btn == null) {
            log.warn("Toolbar '{}' button not found (AutomationId='{}')", label, locator);
            return;
        }
        btn.click();
        log.info("Clicked toolbar '{}' via UIAutomation element click", label);
    }

    // ── Batch locator validation ───────────────────────────────────────────────────

    /**
     * Checks each {@link By} locator in {@code locators} against the current UIAutomation tree
     * and returns the subset that was NOT found.
     *
     * <p>Uses {@value #ELEMENT_LOOKUP_TIMEOUT_MS} ms implicit wait per entry — the shortest
     * setting WinAppDriver honours — so missing elements return in ~600 ms rather than
     * blocking for {@value #DEFAULT_IMPLICIT_WAIT_S} s each.
     */
    public List<By> findMissingLocators(List<By> locators) {
        return withReducedWait(() -> {
            List<By> missing = new ArrayList<>();
            for (By loc : locators) {
                if (driver.findElements(loc).isEmpty()) missing.add(loc);
            }
            return missing;
        });
    }

    // ── Specific element presence — payment area (AutomationId-backed) ────────────

    public boolean isSearchBarVisible()   { return isElementPresent(SEARCH_BAR);      }
    public boolean isPayButtonVisible()   { return isElementPresent(PAY_BUTTON);       }
    public boolean isCardButtonVisible()  { return isElementPresent(CARD_BUTTON);      }
    public boolean isNextDollarVisible()  { return isElementPresent(NEXT_DOLLAR_BTN);  }
    public boolean isExactDollarVisible() { return isElementPresent(EXACT_DOLLAR_BTN); }
    public boolean isRefundVisible()      { return isElementPresent(REFUND_BTN);       }

    // ── Generic element discovery ─────────────────────────────────────────────────

    public List<WebElement> getButtons()      { return findElements(ALL_BUTTONS);     }
    public List<WebElement> getMenuBars()     { return findElements(ALL_MENU_BARS);   }
    public List<WebElement> getTextElements() { return findElements(ALL_TEXT_LABELS); }

    public int getButtonCount() { return getButtons().size(); }

    // ── Private helpers ───────────────────────────────────────────────────────────

    /**
     * Executes {@code action} with implicit wait reduced to {@value #ELEMENT_LOOKUP_TIMEOUT_MS} ms,
     * restoring it to {@value #DEFAULT_IMPLICIT_WAIT_S} s in a {@code finally} block.
     */
    private <T> T withReducedWait(Supplier<T> action) {
        driver.manage().timeouts().implicitlyWait(Duration.ofMillis(ELEMENT_LOOKUP_TIMEOUT_MS));
        try {
            return action.get();
        } finally {
            driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(DEFAULT_IMPLICIT_WAIT_S));
        }
    }
}
