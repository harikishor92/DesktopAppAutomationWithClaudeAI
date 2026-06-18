package com.pos.automation.pages;

import com.pos.automation.base.WinAppDriverSessionFactory;
import com.pos.automation.utils.ProcessUtil;
import org.openqa.selenium.By;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.FluentWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Duration;
import java.util.List;

/**
 * Page object for AlbertaPOS application-level interactions: startup handling,
 * login-screen waiting, and main-window attachment.
 *
 * <h3>Architecture — three WinAppDriver session types</h3>
 * <ol>
 *   <li><b>App session</b> ({@code driver} field, from BaseTest) — bound to AlbertaPOS.exe;
 *       used for login-screen interaction.</li>
 *   <li><b>Desktop Root session</b> (transient) — bound to the Windows desktop tree; used for
 *       UIAutomation-based HWND lookup when PowerShell is unavailable.</li>
 *   <li><b>Main-window session</b> (returned by {@link #attachToMainWindow()}) — bound directly
 *       to the running main-window HWND; used for Home Page validation.</li>
 * </ol>
 *
 * <h3>Synchronisation policy</h3>
 * All condition waits use {@link FluentWait} with polling so that:
 * <ul>
 *   <li>No hardcoded {@code Thread.sleep} pads an arbitrary timeout.</li>
 *   <li>Long operations (main-window load) return as soon as the condition is met.</li>
 *   <li>Short inter-action delays remain where the underlying WinForms app needs them.</li>
 * </ul>
 */
public class AlbertaPOSPage extends BasePage {

    private static final Logger log = LoggerFactory.getLogger(AlbertaPOSPage.class);

    private static final By PERMISSION_BUTTON_LOCATOR =
            By.xpath("//Button[@Name='Yes' or @Name='&Yes' or @Name='Allow' or @Name='OK']");

    private static final String LAUNCHER_WINDOW_TITLE =
            com.pos.automation.utils.ConfigReader.getString(
                    com.pos.automation.utils.ConfigReader.LOGIN_WINDOW_TITLE, "Alberta POS Login");

    private final String winAppDriverUrl;
    private final String expectedTitleFragment;

    private volatile String confirmedWindowTitle = null;

    public AlbertaPOSPage(RemoteWebDriver driver, String winAppDriverUrl, String expectedTitleFragment) {
        super(driver);
        this.winAppDriverUrl      = winAppDriverUrl;
        this.expectedTitleFragment = expectedTitleFragment;
    }

    // ── Startup handling ─────────────────────────────────────────────────────

    /**
     * Detects and dismisses a Windows UAC or app-level security popup.
     *
     * <p>Two fast paths, no Root WinAppDriver session needed:
     * <ol>
     *   <li>PowerShell consent.exe presence check — detects UAC in ~50 ms</li>
     *   <li>App session {@code findElements} — detects app-level dialogs</li>
     * </ol>
     *
     * <p>Uses {@link FluentWait} with 500 ms polling so the check exits as soon as
     * a popup is dismissed or the timeout expires — no fixed sleep.
     *
     * @param timeoutSeconds how long to poll before assuming no popup will appear
     */
    public void handleWindowsPermissionPopup(int timeoutSeconds) {
        log.info("Checking for Windows permission/security popup (timeout={}s)…", timeoutSeconds);
        try {
            new FluentWait<>(driver)
                    .withTimeout(Duration.ofSeconds(timeoutSeconds))
                    .pollingEvery(Duration.ofMillis(500))
                    .ignoring(Exception.class)
                    .until(d -> {
                        // Check 1: UAC consent.exe running on the Winlogon desktop
                        if (isUacConsentRunning()) {
                            log.info("UAC consent process detected — dismissing via PowerShell UIAutomation");
                            if (clickUacYesViaPowerShell()) {
                                log.info("UAC permission popup dismissed");
                                return true;
                            }
                        }
                        // Check 2: app-level Yes/Allow/OK button in the app session tree.
                        // The popup is an owned dialog inside FrmLog's session tree — clicking it
                        // closes the dialog but keeps the session bound to FrmLog's HWND.
                        // Do NOT call switchToFirstAvailableWindow() here: switching windows
                        // while the dialog HWND is still closing would bind the session to a
                        // stale handle and make all subsequent getTitle() calls throw.
                        List<WebElement> buttons = d.findElements(PERMISSION_BUTTON_LOCATOR);
                        if (!buttons.isEmpty()) {
                            log.info("App-level permission popup found — clicking button");
                            buttons.get(0).click();
                            log.info("App-level permission popup dismissed");
                            return true;
                        }
                        return false;
                    });
        } catch (TimeoutException e) {
            log.info("No permission popup detected within {}s — continuing", timeoutSeconds);
        }
    }

    /**
     * Waits until the login screen (FrmLog) returns a non-blank title.
     *
     * <p>Absorbs the brief "no such window" transition after a UAC dialog is dismissed —
     * without this, an immediately following title assertion can see null from a stale session.
     *
     * @param timeoutSeconds maximum wait
     * @throws TimeoutException if the title remains blank/null
     */
    public void waitForLoginScreen(int timeoutSeconds) {
        log.info("Waiting up to {}s for login screen (FrmLog) to be reachable…", timeoutSeconds);
        new FluentWait<>(driver)
                .withTimeout(Duration.ofSeconds(timeoutSeconds))
                .pollingEvery(Duration.ofMillis(500))
                .ignoring(Exception.class)
                .withMessage("Login screen (FrmLog) title was null/blank after " + timeoutSeconds + "s")
                .until(d -> {
                    try {
                        String title = d.getTitle();
                        return title != null && !title.isBlank();
                    } catch (Exception e) {
                        // Current window handle may be stale (e.g. a dialog closed between
                        // session creation and this poll). Try switching to any valid handle
                        // before the next poll so the session self-recovers.
                        try {
                            java.util.Set<String> handles = d.getWindowHandles();
                            if (!handles.isEmpty()) {
                                d.switchTo().window(handles.iterator().next());
                                log.debug("waitForLoginScreen: switched to handle {} after stale-window exception",
                                        handles.iterator().next());
                            }
                        } catch (Exception ignored) {}
                        return false;
                    }
                });
        log.info("Login screen reachable — title: '{}'", driver.getTitle());
    }

    /**
     * Polls until the AlbertaPOS main window appears, exiting as soon as it is detected.
     *
     * <p>Two fast paths — no Root WinAppDriver session needed:
     * <ol>
     *   <li><b>App session handles</b> — works for single-process startup (FrmLog and main
     *       window share a process; title changes in-place).</li>
     *   <li><b>PowerShell Get-Process</b> — works for multi-process startup (FrmLog exits;
     *       main window runs in a new process). Filters out FrmLog so the launcher window
     *       itself is never matched before it exits.</li>
     * </ol>
     *
     * @param timeoutSeconds maximum wait (60 s recommended)
     * @throws TimeoutException if the window does not appear in time
     */
    public void waitForMainWindowToLoad(int timeoutSeconds) {
        log.info("Waiting up to {}s for AlbertaPOS main window (fragment='{}')…",
                timeoutSeconds, expectedTitleFragment);
        driver.manage().timeouts().implicitlyWait(Duration.ZERO);

        try {
            new FluentWait<>(driver)
                    .withTimeout(Duration.ofSeconds(timeoutSeconds))
                    .pollingEvery(Duration.ofSeconds(1))
                    .ignoring(Exception.class)
                    .withMessage(String.format(
                            "AlbertaPOS main window (fragment '%s') did not appear within %ds",
                            expectedTitleFragment, timeoutSeconds))
                    .until(d -> {
                        // Path 1: app session — single-process startup
                        // Exclude login-screen variants: 'Alberta POS Login', 'Alberta POS - Login', etc.
                        // After a session-restore popup (YES clicked), the login window may briefly
                        // show a hyphenated variant that does not equal LAUNCHER_WINDOW_TITLE exactly.
                        try {
                            for (String handle : d.getWindowHandles()) {
                                try {
                                    d.switchTo().window(handle);
                                    String title = d.getTitle();
                                    if (title != null && !title.isBlank()
                                            && title.contains(expectedTitleFragment)
                                            && !title.toLowerCase().contains("login")) {
                                        confirmedWindowTitle = title;
                                        log.info("Main window via app session — title: '{}'", title);
                                        return true;
                                    }
                                } catch (Exception e) {
                                    log.debug("Handle unreachable: {}", e.getMessage());
                                }
                            }
                        } catch (Exception e) {
                            log.debug("App session enumeration failed ({}); trying PowerShell", e.getMessage());
                        }

                        // Path 2: PowerShell — multi-process startup
                        String[] info = getMainWindowInfoExcludingLauncher();
                        if (info != null) {
                            String actualTitle = info[1];
                            confirmedWindowTitle = !actualTitle.isBlank() ? actualTitle : expectedTitleFragment;
                            log.info("Main window via PowerShell — HWND={} title='{}'",
                                    info[0], confirmedWindowTitle);
                            return true;
                        }
                        return false;
                    });
        } finally {
            driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(15));
        }
    }

    // ── Window title accessors ────────────────────────────────────────────────

    @Override
    public String getWindowTitle() {
        try {
            String title = driver.getTitle();
            if (title != null && title.contains(expectedTitleFragment)
                    && !title.toLowerCase().contains("login")) return title;
            if (confirmedWindowTitle != null) {
                log.debug("App session title stale; returning confirmed title '{}'", confirmedWindowTitle);
                return confirmedWindowTitle;
            }
            return title;
        } catch (Exception e) {
            log.debug("getTitle failed ({}); returning confirmed title", e.getMessage());
            return confirmedWindowTitle;
        }
    }

    public boolean isMainWindowVisible() {
        if (confirmedWindowTitle != null) return true;
        try {
            String title = driver.getTitle();
            if (title != null && title.contains(expectedTitleFragment)) return true;
        } catch (Exception ignored) {}
        return false;
    }

    // ── Main window attachment ────────────────────────────────────────────────

    /**
     * Creates a new WinAppDriver session targeting the already-running AlbertaPOS main window.
     *
     * <p>Uses PowerShell Win32 API as primary HWND source (fast, ~200 ms); falls back to
     * UIAutomation desktop session if PowerShell returns nothing.
     *
     * <p>Caller is responsible for calling {@code quit()} on the returned driver
     * (typically in {@code @AfterClass}).
     *
     * @return a RemoteWebDriver scoped to the AlbertaPOS main window
     */
    public RemoteWebDriver attachToMainWindow() {
        log.info("Locating AlbertaPOS main window HWND...");
        try {
            String hwnd = resolveMainWindowHwnd();
            log.info("Main window HWND={}", hwnd);
            RemoteWebDriver mainDriver = WinAppDriverSessionFactory.createForWindow(
                    hwnd, URI.create(winAppDriverUrl).toURL());
            log.info("Attached to main window — title='{}'", mainDriver.getTitle());
            return mainDriver;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to attach WinAppDriver session to AlbertaPOS main window: " + e.getMessage(), e);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private String resolveMainWindowHwnd() {
        String hwnd = getMainWindowHandleViaPowerShell();
        if (hwnd != null) return hwnd;
        hwnd = getMainWindowHandleViaDesktopSession();
        if (hwnd != null) return hwnd;
        throw new RuntimeException(
                "Could not find AlbertaPOS main window HWND via PowerShell or UIAutomation. " +
                "Ensure AlbertaPOS is running and the main window is visible.");
    }

    /**
     * Finds the main window HWND with a Win32 title that is NOT the launcher.
     * Blank-title windows are included: during multi-process startup the child window
     * may not have its title set yet, but it is definitively not FrmLog.
     */
    private String[] getMainWindowInfoExcludingLauncher() {
        // Use -notlike '*Login*' rather than -ne to catch all login-screen title variants:
        // 'Alberta POS Login' (normal), 'Alberta POS - Login' (post-session-restore), etc.
        String script =
                "$p = Get-Process AlbertaPOS -ErrorAction SilentlyContinue | " +
                "Where-Object { $_.MainWindowHandle -ne 0 " +
                "-and $_.MainWindowTitle -notlike '*Login*' } | " +
                "Select-Object -First 1; " +
                "if ($p) { '0x' + $p.MainWindowHandle.ToString('X8') + '|' + $p.MainWindowTitle } else { '' }";
        String output = ProcessUtil.runPowerShell(script);
        if (output.startsWith("0x") && output.length() > 4) {
            String[] parts = output.split("\\|", 2);
            String hwnd  = parts[0].trim();
            String title = parts.length > 1 ? parts[1].trim() : "";
            log.info("Main window (title notlike '*Login*') — HWND={} title='{}'", hwnd, title);
            return new String[]{hwnd, title};
        }
        return null;
    }

    private String getMainWindowHandleViaPowerShell() {
        String script =
                "$p = Get-Process AlbertaPOS -ErrorAction SilentlyContinue | " +
                "Where-Object { $_.MainWindowHandle -ne 0 } | Select-Object -First 1; " +
                "if ($p) { '0x' + $p.MainWindowHandle.ToString('X8') } else { '' }";
        String output = ProcessUtil.runPowerShell(script);
        if (output.startsWith("0x") && output.length() > 2) {
            log.info("PowerShell HWND lookup succeeded — handle={}", output);
            return output;
        }
        log.debug("PowerShell HWND lookup returned no result (output='{}')", output);
        return null;
    }

    private String getMainWindowHandleViaDesktopSession() {
        RemoteWebDriver desktop = null;
        try {
            desktop = WinAppDriverSessionFactory.createForDesktop(
                    URI.create(winAppDriverUrl).toURL());
            desktop.manage().timeouts().implicitlyWait(Duration.ZERO);

            List<WebElement> windows = desktop.findElements(
                    By.xpath("//Window[@Name!='' and @Name!='" + LAUNCHER_WINDOW_TITLE + "']"));
            if (windows.isEmpty()) {
                log.debug("Desktop session: no non-launcher Window element found");
                return null;
            }
            String rawHandle = windows.get(0).getDomAttribute("NativeWindowHandle");
            if (rawHandle == null || rawHandle.isBlank()) {
                log.debug("NativeWindowHandle was null/blank on the Window element");
                return null;
            }
            rawHandle = rawHandle.trim();
            String hexHandle = (rawHandle.startsWith("0x") || rawHandle.startsWith("0X"))
                    ? rawHandle
                    : String.format("0x%08X", Long.parseLong(rawHandle));
            log.info("UIAutomation HWND lookup succeeded — handle={}", hexHandle);
            return hexHandle;
        } catch (Exception e) {
            log.debug("Desktop-session HWND lookup failed: {}", e.getMessage());
            return null;
        } finally {
            if (desktop != null) { try { desktop.quit(); } catch (Exception ignored) {} }
        }
    }

    private boolean isUacConsentRunning() {
        String output = ProcessUtil.runPowerShell(
                "if (Get-Process consent -ErrorAction SilentlyContinue) { 'true' } else { 'false' }");
        return "true".equalsIgnoreCase(output);
    }

    private boolean clickUacYesViaPowerShell() {
        String script =
                "Add-Type -AssemblyName UIAutomationClient,UIAutomationTypes; " +
                "$root = [System.Windows.Automation.AutomationElement]::RootElement; " +
                "$cond = New-Object System.Windows.Automation.PropertyCondition(" +
                "[System.Windows.Automation.AutomationElement]::NameProperty, 'Yes'); " +
                "$btn = $root.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $cond); " +
                "if ($btn) { " +
                "  $p = $btn.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern); " +
                "  $p.Invoke(); 'clicked' } else { '' }";
        return "clicked".equals(ProcessUtil.runPowerShell(script));
    }

}
