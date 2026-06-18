package com.pos.automation.base;

import com.pos.automation.listeners.ExtentReportListener;
import com.pos.automation.utils.AdminPrivilegeValidator;
import com.pos.automation.utils.ConfigReader;
import com.pos.automation.utils.PropertyReader;
import com.pos.automation.utils.WinAppDriverManager;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.ITestResult;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.AfterSuite;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.BeforeSuite;
import org.testng.annotations.Listeners;

import java.lang.reflect.Method;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

/**
 * Suite-scoped base class for all POS automation tests.
 *
 * <p>Lifecycle:
 * <ul>
 *   <li>{@link #setUp()} ({@code @BeforeSuite}) — validates admin privileges, starts WinAppDriver
 *       via {@link WinAppDriverManager}, then launches or attaches to AlbertaPOS.</li>
 *   <li>{@link #logTestStart(Method)} ({@code @BeforeMethod}) — logs test start banner.</li>
 *   <li>{@link #logTestResult(ITestResult)} ({@code @AfterMethod}) — logs result banner.</li>
 *   <li>{@link #tearDown()} ({@code @AfterSuite}) — closes driver, optionally kills POS / WAD.</li>
 * </ul>
 *
 * <h3>Admin / elevation notes</h3>
 * <p>AlbertaPOS.exe has a {@code requireAdministrator} manifest. When the test JVM runs as
 * Administrator, WinAppDriver auto-starts and the app launches without UAC dialogs.
 * When the JVM runs as a standard user, the framework falls back to Shell.Application
 * ShellExecute "runas" (which pops a UAC dialog the user must accept once), and input
 * injection uses {@code LegacyIAccessiblePattern.DoDefaultAction()} to bypass UIPI.
 *
 * <h3>Shared constants (protected)</h3>
 * {@link #POPUP_TIMEOUT_SECONDS} = 15 |
 * {@link #MAIN_WINDOW_TIMEOUT_SECONDS} = 60 |
 * {@link #LOGIN_SCREEN_TIMEOUT_SECONDS} = 20
 */
@Listeners(ExtentReportListener.class)
public class BaseTest {

    private static final Logger log = LoggerFactory.getLogger(BaseTest.class);

    // WinAppDriverCommandExecutor bypasses Selenium 4 W3C cap validation — required for WinAppDriver 1.x.
    protected static RemoteWebDriver driver;

    // ── Shared timeout constants (subclasses inherit these) ──────────────────────
    protected static final int POPUP_TIMEOUT_SECONDS        = 5;   // UAC appears immediately or not at all
    protected static final int MAIN_WINDOW_TIMEOUT_SECONDS  = 60;
    protected static final int LOGIN_SCREEN_TIMEOUT_SECONDS = 20;

    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    // Guards against registering duplicate shutdown hooks on IDE re-run.
    private static volatile boolean shutdownHookRegistered = false;

    // True only when this framework actually launched AlbertaPOS (not pre-running).
    // Set AFTER successful window attach so tearDown never kills a user-started instance.
    private static volatile boolean launchedByFramework = false;

    // ── Suite Lifecycle ──────────────────────────────────────────────────────────

    @BeforeSuite
    public void setUp() {
        long suiteStart = System.currentTimeMillis();
        registerShutdownHook();

        // ── 1. Admin privilege check ─────────────────────────────────────────────
        AdminPrivilegeValidator.validateAndFailIfRequired();

        // ── 2. Read configuration ────────────────────────────────────────────────
        String appPath             = PropertyReader.get("appPath");
        String winAppDriverUrl     = PropertyReader.get("winAppDriverUrl");
        String winAppDriverExePath = PropertyReader.get("winAppDriverExePath",
                "C:\\Program Files (x86)\\Windows Application Driver\\WinAppDriver.exe");
        int implicitWait   = PropertyReader.getInt("implicitWait", 10);
        int appLaunchWait  = PropertyReader.getInt("appLaunchWaitSeconds", 60);

        logSuiteStartBanner(appPath, winAppDriverUrl, winAppDriverExePath);

        // ── 3. Fast path: check if AlbertaPOS is already running ─────────────────
        // Must happen BEFORE WinAppDriver session cleanup: deleting an active
        // createForApp session terminates the process WinAppDriver attached to.
        String earlyHwnd = waitForAlbertaPOSWindow(2);
        if (earlyHwnd != null) {
            log.info("AlbertaPOS already running — skipping session cleanup, HWND={}", earlyHwnd);
        }

        // ── 4. WinAppDriver lifecycle management ─────────────────────────────────
        WinAppDriverManager wadMgr = new WinAppDriverManager(winAppDriverUrl, winAppDriverExePath);
        wadMgr.ensureRunning(earlyHwnd == null); // skip session cleanup when app is pre-running

        // ── 5. Create WinAppDriver session ───────────────────────────────────────
        try {
            final java.net.URL url = URI.create(winAppDriverUrl).toURL();

            // Fast path: app already running — attach directly
            if (earlyHwnd != null) {
                driver = createSessionWithRetry(earlyHwnd, url, 3);
                driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(implicitWait));
                launchedByFramework = false;
                log.info("Fast-path attach complete — title='{}' handles={}",
                        driver.getTitle(), driver.getWindowHandles());
                logSuiteReadyBanner(suiteStart);
                return;
            }

            // Slow path: launch and wait
            log.info("AlbertaPOS not running — killing any ghost process, then launching");
            killProcess("AlbertaPOS.exe");
            launchViaWinAppDriver(appPath, url);
            // NOTE: launchedByFramework set ONLY after successful window attach below.
            // If UAC is never clicked, tearDown must not kill a user-started instance.

            log.info("Waiting up to {}s for AlbertaPOS window...", appLaunchWait);
            String hwnd = waitForAlbertaPOSWindow(appLaunchWait);
            if (hwnd == null) {
                throw new RuntimeException(buildLaunchTimeoutMessage(appPath, appLaunchWait));
            }
            log.info("AlbertaPOS window detected — HWND={}", hwnd);

            driver = createSessionWithRetry(hwnd, url, 3);
            driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(implicitWait));
            launchedByFramework = true; // set AFTER successful attach
            log.info("Session attached — title='{}' handles={}", driver.getTitle(), driver.getWindowHandles());
            logSuiteReadyBanner(suiteStart);

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(
                    "WinAppDriver session creation failed. " +
                    "Ensure AlbertaPOS is running and WinAppDriver is started as Administrator. " +
                    "URL: " + winAppDriverUrl, e);
        }
    }

    // ── Per-Test Lifecycle ───────────────────────────────────────────────────────

    @BeforeMethod(alwaysRun = true)
    public void logTestStart(Method method) {
        log.info("┌─────────────────────────────────────────────────────────────┐");
        log.info("│  TEST STARTED : {}", method.getName());
        log.info("│  Start Time   : {}", formatEpochMillis(System.currentTimeMillis()));
        log.info("└─────────────────────────────────────────────────────────────┘");
    }

    @AfterMethod(alwaysRun = true)
    public void logTestResult(ITestResult result) {
        long   durationMs = result.getEndMillis() - result.getStartMillis();
        long   minutes    = durationMs / 60_000;
        long   seconds    = (durationMs % 60_000) / 1_000;
        long   millis     = durationMs % 1_000;

        log.info("┌─────────────────────────────────────────────────────────────┐");
        log.info("│  TEST RESULT  : {}", result.getName());
        log.info("│  Status       : {}", result.isSuccess() ? "PASSED" : "FAILED");
        log.info("│  Start Time   : {}", formatEpochMillis(result.getStartMillis()));
        log.info("│  End Time     : {}", formatEpochMillis(result.getEndMillis()));
        log.info("│  Duration     : {}m {}s {}ms  ({}ms total)", minutes, seconds, millis, durationMs);
        log.info("└─────────────────────────────────────────────────────────────┘");
    }

    // ── Suite Teardown ───────────────────────────────────────────────────────────

    @AfterSuite
    public void tearDown() {
        log.info("=== Suite Teardown ===");
        if (driver != null) {
            log.info("Closing WinAppDriver session");
            try { driver.quit(); } catch (Exception e) { log.warn("driver.quit(): {}", e.getMessage()); }
            finally { driver = null; }
        }
        if (launchedByFramework) {
            log.info("Framework launched AlbertaPOS — terminating process");
            killProcess("AlbertaPOS.exe");
        } else {
            log.info("AlbertaPOS was pre-running — leaving it alive");
        }
        boolean stopWad = ConfigReader.getBoolean(ConfigReader.STOP_WINAPPDRIVER, false);
        if (stopWad) {
            log.info("stopWinAppDriverAfterSuite=true — stopping WinAppDriver");
            killProcess("WinAppDriver.exe");
        } else {
            log.info("WinAppDriver kept alive (stopWinAppDriverAfterSuite=false)");
        }
        log.info("=== Suite Teardown Complete ===");
    }

    // ── Shared Utilities ─────────────────────────────────────────────────────────

    protected String formatEpochMillis(long epochMillis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault())
                            .format(TIMESTAMP_FMT);
    }

    // ── App Window Detection ─────────────────────────────────────────────────────

    /**
     * Polls PowerShell for an AlbertaPOS process that has a visible main window handle.
     *
     * AlbertaPOS is a multi-process launcher: the bootstrap process exits after spawning
     * the real app, so Get-Process finds the child by image name regardless of lineage.
     *
     * @param timeoutSeconds max wait before returning null
     * @return hex HWND string (e.g. "0x001A0898"), or null if not found in time
     */
    private String waitForAlbertaPOSWindow(int timeoutSeconds) {
        String script =
                "$p = Get-Process AlbertaPOS -ErrorAction SilentlyContinue | " +
                "Where-Object { $_.MainWindowHandle -ne 0 } | Select-Object -First 1; " +
                "if ($p) { '0x' + $p.MainWindowHandle.ToString('X8') } else { '' }";

        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                Process ps = new ProcessBuilder(
                        "powershell", "-NoProfile", "-NonInteractive", "-Command", script)
                        .redirectErrorStream(true).start();
                String output = new String(ps.getInputStream().readAllBytes()).trim();
                ps.waitFor(5, TimeUnit.SECONDS);
                if (output.startsWith("0x") && output.length() > 4) {
                    return output;
                }
            } catch (Exception e) {
                log.debug("HWND poll error: {}", e.getMessage());
            }
            sleepQuietly(500);
        }
        return null;
    }

    /**
     * Launches AlbertaPOS using two sequential strategies:
     *
     * <ul>
     *   <li><b>Strategy A</b> (WinAppDriver session create): fires first. Works when WinAppDriver
     *       is elevated — CreateProcess with admin token launches the app with no UAC dialog.
     *       After 5 s we do a quick HWND check; if the window appeared, Strategy B is skipped.</li>
     *   <li><b>Strategy B</b> (Shell.Application ShellExecute "runas"): runs ONLY when Strategy A
     *       did not produce a window within 5 s. Surfaces a UAC dialog; the user clicks Yes once.
     *       Returns immediately after queuing the elevation request.</li>
     * </ul>
     *
     * <p>Firing both strategies simultaneously is dangerous: if Strategy A succeeds (window
     * appears) while Strategy B's Shell.Application is also running, a second AlbertaPOS instance
     * is launched → second UAC dialog appears on the secure desktop → the regular desktop is
     * frozen → WinAppDriver cannot query the existing window → every getTitle() call throws.
     */
    private void launchViaWinAppDriver(String appPath, java.net.URL winAppUrl) {
        log.info("Launching AlbertaPOS — Strategy A: WinAppDriver elevated session create");

        // Strategy A: elevated WinAppDriver creates the session directly.
        Thread winAppThread = new Thread(() -> {
            try {
                WinAppDriverSessionFactory.createForApp(appPath, 20, winAppUrl);
            } catch (Exception ignored) {
                // Expected: launcher exits without leaving a persistent window handle.
            }
        });
        winAppThread.setDaemon(true);
        winAppThread.start();

        // Wait 3 s, then check whether Strategy A brought up a window.
        // If it did, skip Strategy B entirely — firing Shell.Application after the app is already
        // running would launch a second instance (UAC dialog on secure desktop), which freezes the
        // regular desktop and causes WinAppDriver to lose the existing window handle.
        sleepQuietly(3_000);
        String earlyHwnd = pollAlbertaPOSWindowOnce();
        if (earlyHwnd != null) {
            log.info("Strategy A succeeded (HWND={}) — skipping Shell.Application", earlyHwnd);
            return;
        }

        // Strategy B: Shell.Application ShellExecute "runas" — only reached when WinAppDriver is
        // not elevated and Strategy A failed to produce a window.
        log.info("No window from Strategy A — firing Strategy B: Shell.Application runas (UAC may appear, click Yes)");
        String escapedPath = appPath.replace("'", "''");
        String shellScript =
                "$sh = New-Object -ComObject Shell.Application; " +
                "$sh.ShellExecute('" + escapedPath + "', '', '', 'runas', 1)";
        Thread shellThread = new Thread(() -> {
            try {
                // -Sta: Shell.Application is a COM UI object; requires STA thread.
                Process ps = new ProcessBuilder(
                        "powershell", "-NoProfile", "-Sta", "-NonInteractive", "-Command", shellScript)
                        .redirectErrorStream(true).start();
                String psOut = new String(ps.getInputStream().readAllBytes()).trim();
                ps.waitFor(10, TimeUnit.SECONDS);
                if (!psOut.isEmpty()) log.info("Shell.Application output: {}", psOut);
                log.info("Shell.Application ShellExecute queued (click Yes on the UAC dialog)");
            } catch (Exception e) {
                log.debug("Shell.Application launch attempt: {}", e.getMessage());
            }
        });
        shellThread.setDaemon(true);
        shellThread.start();
        log.info("Strategy B fired — waiting for AlbertaPOS window");
    }

    /**
     * Single-shot PowerShell HWND check with no retry loop.
     * Used to test whether Strategy A produced a window before firing Strategy B.
     */
    private String pollAlbertaPOSWindowOnce() {
        String script =
                "$p = Get-Process AlbertaPOS -ErrorAction SilentlyContinue | " +
                "Where-Object { $_.MainWindowHandle -ne 0 } | Select-Object -First 1; " +
                "if ($p) { '0x' + $p.MainWindowHandle.ToString('X8') } else { '' }";
        try {
            Process ps = new ProcessBuilder(
                    "powershell", "-NoProfile", "-NonInteractive", "-Command", script)
                    .redirectErrorStream(true).start();
            String output = new String(ps.getInputStream().readAllBytes()).trim();
            ps.waitFor(5, TimeUnit.SECONDS);
            return (output.startsWith("0x") && output.length() > 4) ? output : null;
        } catch (Exception e) {
            log.debug("pollAlbertaPOSWindowOnce error: {}", e.getMessage());
            return null;
        }
    }

    // ── Session creation with retry ───────────────────────────────────────────────

    /**
     * Creates a WinAppDriver window session with up to {@code maxAttempts} retries.
     * Retries handle transient session creation failures (e.g. timing during app startup).
     */
    private RemoteWebDriver createSessionWithRetry(String hwnd, java.net.URL url, int maxAttempts) {
        Exception lastException = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                log.info("Creating WinAppDriver session — HWND={} attempt {}/{}", hwnd, attempt, maxAttempts);
                RemoteWebDriver d = WinAppDriverSessionFactory.createForWindow(hwnd, url);
                log.info("  Session created successfully on attempt {}", attempt);
                return d;
            } catch (Exception e) {
                lastException = e;
                log.warn("  Session creation attempt {}/{} failed: {}", attempt, maxAttempts, e.getMessage());
                if (attempt < maxAttempts) {
                    long backoffMs = attempt * 2_000L;
                    log.info("  Retrying in {}ms...", backoffMs);
                    sleepQuietly(backoffMs);
                }
            }
        }
        throw new RuntimeException(
                "WinAppDriver session creation failed after " + maxAttempts + " attempts. " +
                "HWND=" + hwnd + " URL=" + url, lastException);
    }

    // ── Process Management ───────────────────────────────────────────────────────

    /**
     * Terminates a process by executable name.
     *
     * Attempt 1 — {@code taskkill /f}: fast for same or lower integrity.
     * Attempt 2 — WMI {@code Win32_Process.Terminate()}: handles elevated targets
     * because WMI routes through the WINMGMT service (SYSTEM).
     */
    private void killProcess(String exeName) {
        try {
            Process p = new ProcessBuilder("taskkill", "/f", "/im", exeName).start();
            if (p.waitFor() == 0) {
                log.info("Killed {} (taskkill)", exeName);
                sleepQuietly(500);
                return;
            }
        } catch (Exception ignored) {}

        try {
            String script =
                    "$p = Get-WmiObject Win32_Process | Where-Object { $_.Name -eq '" + exeName + "' };" +
                    " if ($p) { $p | ForEach-Object { [void]$_.Terminate() }; 'killed' }";
            Process p = new ProcessBuilder(
                    "powershell", "-NoProfile", "-NonInteractive", "-Command", script)
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor(5, TimeUnit.SECONDS);
            if ("killed".equals(out)) { log.info("Killed {} (WMI)", exeName); sleepQuietly(500); }
        } catch (Exception ignored) {}
    }

    // ── Logging helpers ───────────────────────────────────────────────────────────

    private void logSuiteStartBanner(String appPath, String wadUrl, String wadExe) {
        log.info("╔═══════════════════════════════════════════════════════════╗");
        log.info("║              POS Automation Suite Starting                ║");
        log.info("╚═══════════════════════════════════════════════════════════╝");
        log.info("  App path   : {}", appPath);
        log.info("  WAD URL    : {}", wadUrl);
        log.info("  WAD exe    : {}", wadExe);
        log.info("  Environment: {} | Mode: {}",
                ConfigReader.getString(ConfigReader.ENVIRONMENT, "DEV"),
                ConfigReader.getString(ConfigReader.EXECUTION_MODE, "local"));
        log.info("  Admin VM   : {}", ConfigReader.getBoolean(ConfigReader.AUTOMATION_VM_MODE, false));
    }

    private void logSuiteReadyBanner(long suiteStartMs) {
        long elapsed = System.currentTimeMillis() - suiteStartMs;
        log.info("╔═══════════════════════════════════════════════════════════╗");
        log.info("║         Suite Setup Complete — Tests Ready                ║");
        log.info("║  Setup time: {}ms", elapsed);
        log.info("╚═══════════════════════════════════════════════════════════╝");
    }

    private String buildLaunchTimeoutMessage(String appPath, int waitSeconds) {
        return "AlbertaPOS window did not appear within " + waitSeconds + "s.\n" +
               "Options:\n" +
               "  A) Double-click run-tests-as-admin.bat (elevated, no UAC prompts)\n" +
               "  B) Double-click start-app.bat → click Yes on UAC → re-run mvn clean test\n" +
               "  C) Open PowerShell as Administrator → mvn clean test\n" +
               "App path: " + appPath;
    }

    // ── Utilities ────────────────────────────────────────────────────────────────

    private void sleepQuietly(long millis) {
        try { Thread.sleep(millis); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private void registerShutdownHook() {
        if (shutdownHookRegistered) return;
        shutdownHookRegistered = true;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (driver != null) { try { driver.quit(); } catch (Exception ignored) {} }
            if (launchedByFramework) killProcess("AlbertaPOS.exe");
        }, "pos-cleanup-hook"));
    }
}
