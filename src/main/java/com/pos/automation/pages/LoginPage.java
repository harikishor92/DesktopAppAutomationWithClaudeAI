package com.pos.automation.pages;

import com.pos.automation.utils.ProcessUtil;
import org.openqa.selenium.By;
import org.openqa.selenium.NoSuchSessionException;
import org.openqa.selenium.NoSuchWindowException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.PointerInput;
import org.openqa.selenium.interactions.Sequence;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.FluentWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

/**
 * Page object for the AlbertaPOS login screen (FrmLog).
 *
 * FrmLog uses a custom WinForms Pane-based numpad PIN-entry UI — every control
 * (digit buttons, display areas, LOG IN) is ControlType.Pane. No Edit controls exist.
 * Standard Win32 mouse_event and WScript.Shell.SendKeys are NOT processed by the
 * custom Pane buttons; the app requires UIAutomation or WinAppDriver W3C input.
 *
 * Architecture note: FrmLog's HWND stays alive as a background window after a successful
 * login. The WinAppDriver session bound to FrmLog's HWND always returns 'FrmLog' from
 * getTitle() — the new main window opens at a completely different HWND that this session
 * cannot see. Login success is detected via PowerShell Win32 main-window presence check.
 *
 * <h3>Login strategy execution order</h3>
 * <ol>
 *   <li><b>Strategy A — AutomationId element click</b>: finds User ID pane, digit buttons,
 *       and LOG IN by developer-assigned AutomationIds via WinAppDriver UIAutomation.
 *       This is the primary path and succeeds when the developer has deployed AutomationIds.</li>
 *   <li><b>Strategy B — W3C PointerInput absolute coordinate click</b>: fallback for
 *       environments where AutomationIds are absent. Uses hardcoded screen coordinates
 *       derived from UIAutomation BoundingRectangle inspection. Maintains UIPI bypass
 *       through WinAppDriver's UIAutomation cross-process COM channel.</li>
 * </ol>
 */
public class LoginPage extends BasePage {

    private static final Logger log = LoggerFactory.getLogger(LoginPage.class);

    // Named inter-action delays — necessary WinForms processing delays, not polling waits
    private static final long FOCUS_DELAY_MS  = 100;   // pane focus registration
    private static final long DIGIT_DELAY_MS  = 80;    // numpad key registration
    private static final long SUBMIT_DELAY_MS = 300;   // login submission processing
    private static final long COORD_DELAY_MS  = 150;   // Strategy B coordinate click spacing

    // ── FrmLog field locators (AutomationIds assigned by developer) ──────────────
    // Update these constants if the developer uses different AutomationId values.
    private static final By USER_ID_PANE  = By.xpath("//*[@AutomationId='txtUserName']");
    private static final By PASSWORD_PANE = By.xpath("//*[@AutomationId='txtPassword']");

    // ── LOG IN button — primary AutomationId locator ─────────────────────────────
    private static final By[] LOGIN_BUTTON_LOCATORS = {
            By.xpath("//*[@AutomationId='btnLogin']")
    };

    // ── Session restore popup — AutomationId confirmed via WinAppDriver inspection ──
    // Popup container: AutomationId='popupSessionRestore'
    // YES button:      AutomationId='btnYes'  (Name='btnRestoreSession')
    // NO button:       AutomationId='btnNo'   (Name='btnCloseSessionPopup')
    private static final By SESSION_YES_BUTTON =
            By.xpath("//*[@AutomationId='btnYes']");

    public LoginPage(RemoteWebDriver driver) {
        super(driver);
    }

    // ── Public entry point ────────────────────────────────────────────────────────

    /**
     * Logs in to AlbertaPOS. Tries Strategy A (AutomationId element click) first;
     * falls back to Strategy B (W3C coordinate click) if any element is not found.
     */
    public void login(String username, String password) {
        log.info("Logging in — username='{}', password length={}",
                username, password == null ? 0 : password.length());
        try {
            boolean stratA = loginViaAutomationId(username, password);
            log.info("Strategy A (AutomationId element click) → {}", stratA);
            if (stratA) {
                handleSessionPopup();
                if (!isStillOnLoginScreen()) {
                    log.info("Login succeeded via Strategy A");
                    return;
                }
                log.warn("Strategy A claimed success but still on FrmLog — falling through to Strategy B");
            }

            boolean stratB = loginViaWinAppDriverClick(username, password);
            log.info("Strategy B (W3C coordinate fallback) → {}", stratB);
            if (stratB) {
                handleSessionPopup();
                if (!isStillOnLoginScreen()) {
                    log.info("Login succeeded via Strategy B");
                    return;
                }
            }

            log.error("All login strategies exhausted — login may have succeeded asynchronously");

        } catch (NoSuchWindowException e) {
            log.info("Login window closed (session auto-restored)");
        } catch (NoSuchSessionException e) {
            log.warn("WinAppDriver session expired during login: {}", e.getMessage());
        }
    }

    // ── Strategy A: WinAppDriver UIAutomation element click with AutomationIds ────

    /**
     * Primary login strategy. Locates User ID pane, digit buttons, Password pane, and
     * LOG IN button by developer-assigned AutomationIds via WinAppDriver UIAutomation.
     *
     * <p>WinAppDriver routes element.click() through the UIAutomation cross-process COM
     * bridge — UIAutomationCore.dll executes the action inside AlbertaPOS's own process,
     * bypassing UIPI when the app runs elevated.
     *
     * @return {@code true} if all elements were found and clicked; {@code false} if any
     *         required element was absent (triggers Strategy B fallback).
     */
    private boolean loginViaAutomationId(String username, String password) {
        log.info("Strategy A — UIAutomation element click via AutomationId");

        // Focus User ID display pane
        WebElement userIdPane = findFirst(USER_ID_PANE);
        if (userIdPane == null) {
            log.info("  User ID pane not found by AutomationId ('txtUserName') — abandoning Strategy A");
            return false;
        }
        userIdPane.click();
        sleep(FOCUS_DELAY_MS);

        // Enter each username digit
        for (char d : username.toCharArray()) {
            String ds = String.valueOf(d);
            WebElement btn = findFirst(
                    By.xpath("//*[@AutomationId='btn" + ds + "']"),
                    By.xpath("//Button[@Name='" + ds + "']"),
                    By.xpath("//*[@Name='" + ds + "']")
            );
            if (btn == null) {
                log.info("  Digit '{}' not found — abandoning Strategy A", ds);
                return false;
            }
            btn.click();
            sleep(DIGIT_DELAY_MS);
        }

        // Focus Password display pane
        WebElement passwordPane = findFirst(PASSWORD_PANE);
        if (passwordPane == null) {
            log.info("  Password pane not found by AutomationId ('txtPassword') — abandoning Strategy A");
            return false;
        }
        passwordPane.click();
        sleep(FOCUS_DELAY_MS);

        // Enter each password digit
        for (char d : (password == null ? "" : password).toCharArray()) {
            String ds = String.valueOf(d);
            WebElement btn = findFirst(
                    By.xpath("//*[@AutomationId='btn" + ds + "']"),
                    By.xpath("//Button[@Name='" + ds + "']"),
                    By.xpath("//*[@Name='" + ds + "']")
            );
            if (btn == null) {
                log.info("  Password digit '{}' not found — abandoning Strategy A", ds);
                return false;
            }
            btn.click();
            sleep(DIGIT_DELAY_MS);
        }

        // Click LOG IN
        clickLoginButton();
        sleep(SUBMIT_DELAY_MS);
        return true;
    }

    // ── Strategy B: WinAppDriver W3C Actions absolute coordinate clicks ───────────

    /**
     * Fallback login strategy. Clicks each numpad digit and LOG IN using WinAppDriver's
     * W3C PointerInput with Origin.viewport() (absolute screen coordinates).
     *
     * <p>Used when Strategy A cannot find elements by AutomationId. Coordinates are derived
     * from UIAutomation BoundingRectangle inspection of FrmLog at 1920×1080 resolution.
     *
     * <p>Screen coordinates:
     * <ul>
     *   <li>User-ID pane   xy=997,164 wh=598x67  → center (1296, 197)</li>
     *   <li>Password pane  xy=997,265 wh=598x67  → center (1296, 298)</li>
     *   <li>LOG IN pane    xy=1642,159 wh=257x102 → center (1770, 210)</li>
     *   <li>Digits 0–9     see {@link #getDigitButtonCenter(char)}</li>
     * </ul>
     */
    private boolean loginViaWinAppDriverClick(String username, String password) {
        log.info("Strategy B — W3C Actions absolute screen coordinate clicks");
        try {
            winAppClick(1296, 197);
            sleep(COORD_DELAY_MS);

            for (char d : username.toCharArray()) {
                int[] c = getDigitButtonCenter(d);
                if (c == null) { log.warn("  No coordinate for digit '{}' — abandoning", d); return false; }
                winAppClick(c[0], c[1]);
                sleep(DIGIT_DELAY_MS);
            }

            winAppClick(1296, 298);
            sleep(COORD_DELAY_MS);

            for (char d : (password == null ? "" : password).toCharArray()) {
                int[] c = getDigitButtonCenter(d);
                if (c == null) { log.warn("  No coordinate for digit '{}' — abandoning", d); return false; }
                winAppClick(c[0], c[1]);
                sleep(DIGIT_DELAY_MS);
            }

            winAppClick(1770, 210);
            sleep(SUBMIT_DELAY_MS);
            return true;

        } catch (Exception e) {
            log.warn("  Strategy B failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Sends a single left-click at absolute screen coordinates via the W3C PointerInput API.
     * WinAppDriver routes this through the UIAutomation cross-process bridge — bypasses UIPI.
     */
    private void winAppClick(int screenX, int screenY) {
        // WinAppDriver 1.x supports only "touch" and "pen" pointer kinds, not "mouse".
        PointerInput touch = new PointerInput(PointerInput.Kind.TOUCH, "touch");
        Sequence seq = new Sequence(touch, 0)
                .addAction(touch.createPointerMove(Duration.ZERO,
                        PointerInput.Origin.viewport(), screenX, screenY))
                .addAction(touch.createPointerDown(0))
                .addAction(touch.createPointerUp(0));
        driver.perform(Collections.singletonList(seq));
        log.info("  winAppClick({},{})", screenX, screenY);
    }

    /** Returns the screen-coordinate center [x, y] of the numpad button for a given digit. */
    private static int[] getDigitButtonCenter(char digit) {
        // Coordinates from UIAutomation BoundingRectangle at 1920×1080: xy=left,top wh=widthxheight
        return switch (digit) {
            case '1' -> new int[]{1094, 418};  // xy=994,362 wh=201x112
            case '2' -> new int[]{1295, 418};  // xy=1195,362 wh=201x112
            case '3' -> new int[]{1497, 418};  // xy=1396,362 wh=202x112
            case '4' -> new int[]{1094, 530};  // xy=994,474 wh=201x112
            case '5' -> new int[]{1295, 530};  // xy=1195,474 wh=201x112
            case '6' -> new int[]{1497, 530};  // xy=1396,474 wh=202x112
            case '7' -> new int[]{1094, 642};  // xy=994,586 wh=201x112
            case '8' -> new int[]{1295, 642};  // xy=1195,586 wh=201x112
            case '9' -> new int[]{1497, 642};  // xy=1396,586 wh=202x112
            case '0' -> new int[]{1295, 755};  // xy=1195,698 wh=201x114
            default  -> null;
        };
    }

    // ── LOG IN button ─────────────────────────────────────────────────────────────

    private void clickLoginButton() {
        WebElement btn = findFirst(LOGIN_BUTTON_LOCATORS);
        if (btn != null) {
            btn.click();
            log.info("Clicked LOG IN (AutomationId='btnLogin') via UIAutomation");
        } else {
            log.warn("LOG IN button not found (AutomationId='btnLogin') — login may not complete");
        }
    }

    // ── Session restore popup ─────────────────────────────────────────────────────

    private void handleSessionPopup() {
        log.info("Checking for session restore popup…");
        try {
            new FluentWait<>(driver)
                    .withTimeout(Duration.ofSeconds(3))
                    .pollingEvery(Duration.ofMillis(500))
                    .ignoring(Exception.class)
                    .until(d -> {
                        List<WebElement> buttons = d.findElements(SESSION_YES_BUTTON);
                        if (!buttons.isEmpty()) { buttons.get(0).click(); return true; }
                        return false;
                    });
            log.info("Session popup dismissed");
        } catch (Exception e) {
            log.info("No session popup within 3 s — continuing");
        }
    }

    // ── Login verification ────────────────────────────────────────────────────────

    private boolean isStillOnLoginScreen() {
        // FrmLog stays alive as a background HWND after login; driver.getTitle() always returns
        // the login screen title from this HWND-bound session. Use PowerShell Win32 to check
        // whether a non-login AlbertaPOS main window has appeared — same pattern used in
        // AlbertaPOSPage.getMainWindowInfoExcludingLauncher().
        // Poll up to 8 s (8 × 1 s) to give the main window time to initialise after login.
        String script =
                "$p = Get-Process AlbertaPOS -ErrorAction SilentlyContinue | " +
                "Where-Object { $_.MainWindowHandle -ne 0 " +
                "-and $_.MainWindowTitle -notlike '*Login*' } | " +
                "Select-Object -First 1; if ($p) { 'main' } else { 'login' }";
        for (int attempt = 1; attempt <= 8; attempt++) {
            String output = ProcessUtil.runPowerShell(script);
            if ("main".equals(output.trim())) {
                log.info("  login-verify: powershell='main' → still-on-login-screen=false (attempt {}/8)", attempt);
                return false;
            }
            if (attempt < 8) sleep(1000);
        }
        log.info("  login-verify: powershell='login' after 8 attempts → still-on-login-screen=true");
        return true;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────

    private WebElement findFirst(By... locators) {
        driver.manage().timeouts().implicitlyWait(Duration.ZERO);
        try {
            for (By loc : locators) {
                try {
                    List<WebElement> els = driver.findElements(loc);
                    if (!els.isEmpty()) return els.get(0);
                } catch (Exception ignored) {}
            }
            return null;
        } finally {
            driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(15));
        }
    }

    private void sleep(long millis) {
        try { Thread.sleep(millis); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
