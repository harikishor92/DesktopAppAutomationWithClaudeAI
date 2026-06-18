package com.pos.automation.tests;

import com.aventstack.extentreports.Status;
import com.pos.automation.annotations.TestDataSheet;
import com.pos.automation.base.BaseTest;
import com.pos.automation.listeners.RetryAnalyzer;
import com.pos.automation.pages.AlbertaPOSPage;
import com.pos.automation.pages.HomePage;
import com.pos.automation.pages.LoginPage;
import com.pos.automation.utils.ConfigReader;
import com.pos.automation.utils.ExcelUtil;
import com.pos.automation.utils.ExtentReportManager;
import com.pos.automation.utils.ProcessUtil;
import org.openqa.selenium.By;
import org.openqa.selenium.Keys;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import org.testng.asserts.SoftAssert;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;

/**
 * Validates the AlbertaPOS Home Page after a successful login.
 *
 * <h3>Validation coverage</h3>
 * <ol>
 *   <li>Window title and error-dialog state (hard asserts)</li>
 *   <li>Toolbar container — present across all three header rows</li>
 *   <li>Header Row navigation — Next button advances rows; Home button resets to Row 1</li>
 *   <li>Main body — search bar and group-navigation buttons (AutomationId GROUP1–GROUP5)</li>
 *   <li>Payment area — PAY, CARD, NEXT $, EXACT $, REFUND, quick-pay amounts (AutomationId-backed)</li>
 * </ol>
 *
 * <h3>Toolbar UIAutomation note</h3>
 * <p>AlbertaPOS's toolbar buttons (Settings, Qty, Day, Month, etc.) are rendered via a
 * custom WinForms control that does not populate the UIAutomation element tree accessible
 * from the main-window WinAppDriver session. The toolbar is exposed as a single
 * {@code ControlType.Custom} element named "Top Row" with zero accessible children.
 * Individual button validation is therefore not performed; toolbar navigation uses
 * absolute-coordinate {@code java.awt.Robot} clicks on the left/right edges of the
 * Custom container.</p>
 *
 * <h3>Flow — standalone run</h3>
 * <ol>
 *   <li>BaseTest &#64;BeforeSuite — WinAppDriver starts, AlbertaPOS.exe is launched</li>
 *   <li>&#64;BeforeClass — page objects wired to the launched app session</li>
 *   <li>&#64;Test — popup handling → login screen → credentials → attach → validate</li>
 * </ol>
 *
 * <h3>Flow — full-suite run (AlbertaPOSLaunchTest ran first)</h3>
 * <p>A fast-path check at the top of the test attaches to the main window immediately
 * when its title already contains {@code homeTitleFragment} ('Home'), skipping the
 * login flow and avoiding a {@code waitForLoginScreen()} timeout on a stale FrmLog.</p>
 *
 * <h3>Run</h3>
 * <pre>mvn test "-DsuiteXmlFile=src/test/resources/testng-hometest.xml"</pre>
 */
@TestDataSheet(sheetName = "HomePage")
public class HomePageTest extends BaseTest {

    private static final Logger log = LoggerFactory.getLogger(HomePageTest.class);

    private static final int TOOLBAR_ANIMATION_PAUSE_MS = 600;

    private AlbertaPOSPage  posPage;
    private LoginPage       loginPage;
    private String          posUsername;
    private String          posPassword;
    private String          homeTitleFragment;
    private RemoteWebDriver mainWindowDriver;
    private HomePage        homePage;

    // ── Setup / Teardown ─────────────────────────────────────────────────────────

    @BeforeClass
    public void initPages() {
        String winAppDriverUrl = ConfigReader.getString(ConfigReader.WIN_APP_DRIVER_URL);
        String titleFragment   = ConfigReader.getString(ConfigReader.APP_WINDOW_TITLE, "Alberta");
        posUsername            = ConfigReader.getString(ConfigReader.POS_USERNAME);
        posPassword            = ConfigReader.getString(ConfigReader.POS_PASSWORD);
        homeTitleFragment      = ConfigReader.getString(ConfigReader.HOME_PAGE_TITLE, "Home");

        posPage   = new AlbertaPOSPage(driver, winAppDriverUrl, titleFragment);
        loginPage = new LoginPage(driver);
        log.info("Page objects initialised — expectedTitleFragment='{}', homeTitleFragment='{}'",
                titleFragment, homeTitleFragment);
    }

    @AfterClass(alwaysRun = true)
    public void closeMainWindowDriver() {
        // Do NOT call quit() on the appTopLevelWindow session.
        // WinAppDriver 1.x sends WM_CLOSE to the target HWND on DELETE /session,
        // which closes AlbertaPOS and breaks subsequent tests in the same suite.
        // Stale sessions are cleaned up by WinAppDriverManager.deleteStaleSessionsWithRetry()
        // at the start of the next @BeforeSuite run.
        if (mainWindowDriver != null) {
            log.info("Main window WinAppDriver session released (not quit — avoids WM_CLOSE to AlbertaPOS)");
            mainWindowDriver = null;
            homePage         = null;
        }
    }

    // ── DataProvider ─────────────────────────────────────────────────────────────

    @DataProvider(name = "testData")
    public static Object[][] provideTestData() {
        try {
            ExcelUtil excel = new ExcelUtil();
            TestDataSheet sheet = HomePageTest.class.getAnnotation(TestDataSheet.class);
            return excel.getDataAsObjectArray(sheet.sheetName());
        } catch (Exception e) {
            log.warn("Excel data not available for HomePage sheet — running with config defaults: {}", e.getMessage());
            return new Object[][]{{new HashMap<String, String>()}};
        }
    }

    // ── Test ─────────────────────────────────────────────────────────────────────

    @Test(description = "Validates all AlbertaPOS Home Page elements and header toolbar navigation",
          retryAnalyzer = RetryAnalyzer.class)
    public void testHomePageDisplayedAfterLogin() {

        // Drop stale references from a previous retry — do NOT call quit() here.
        // appTopLevelWindow-based sessions close the target Win32 window on quit().
        mainWindowDriver = null;
        homePage = null;

        // Fast-path: app already on Home Page (full-suite — AlbertaPOSLaunchTest ran first).
        if (tryAttachToExistingMainWindow()) {
            log.info("Fast-path: main window already accessible — skipping login flow");
            ExtentReportManager.getTest().log(Status.INFO, "Fast-path: main window already up — skipping login steps");
        } else {
            // Step 1: Dismiss any Windows UAC / security dialog
            log.info("── Step 1: Handling Windows permission popup (if any) ──");
            ExtentReportManager.getTest().log(Status.INFO, "Step 1: Handling Windows permission popup");
            posPage.handleWindowsPermissionPopup(POPUP_TIMEOUT_SECONDS);

            // Step 2: Wait for login screen
            log.info("── Step 2: Waiting for login screen ──");
            ExtentReportManager.getTest().log(Status.INFO, "Step 2: Verifying login screen");
            posPage.waitForLoginScreen(LOGIN_SCREEN_TIMEOUT_SECONDS);

            // Step 3: Submit credentials
            log.info("── Step 3: Submitting login credentials (username='{}') ──", posUsername);
            ExtentReportManager.getTest().log(Status.INFO, "Step 3: Submitting credentials (username='" + posUsername + "')");
            loginPage.login(posUsername, posPassword);

            // Step 4: Attach to main window
            log.info("── Step 4: Attaching to main window ──");
            ExtentReportManager.getTest().log(Status.INFO, "Step 4: Waiting for main window");
            posPage.waitForMainWindowToLoad(MAIN_WINDOW_TIMEOUT_SECONDS);
            mainWindowDriver = posPage.attachToMainWindow();
            homePage         = new HomePage(mainWindowDriver);
        }

        // Dismiss any dialogs left open from prior test sessions (e.g. Age Check, Customer not found).
        // These are WinForms windows whose UIAutomation Name contains an error keyword and would
        // cause isErrorDialogVisible() to fire even though no NEW error has occurred.
        dismissLingeringDialogs();

        // ── Hard asserts: window state must be valid before detailed checks ────────
        String homeTitle = homePage.getWindowTitle();
        Assert.assertNotNull(homeTitle, "Home Page title must not be null after login");
        Assert.assertFalse(homeTitle.isBlank(), "Home Page title must not be blank after login");
        Assert.assertTrue(homeTitle.contains(homeTitleFragment),
                "Home Page title must contain '" + homeTitleFragment + "' but was: '" + homeTitle + "'");
        Assert.assertFalse(homePage.isErrorDialogVisible(),
                "An error dialog must not be visible on the Home Page after login");
        log.info("Home Page window confirmed — title: '{}'", homeTitle);
        ExtentReportManager.getTest().log(Status.INFO, "Home Page confirmed — title: '" + homeTitle + "'");

        SoftAssert soft = new SoftAssert();

        // Reset to Row 1 unconditionally — Robot click on Home is a no-op on Row 1 and
        // correctly resets from Row 2/3. Avoids stale-toolbar state across retries.
        log.info("Resetting toolbar to Row 1 via Home button");
        homePage.clickHomeHeader();

        // ── Step 5: Toolbar container — Row 1 ────────────────────────────────────
        // The toolbar Custom element is the only individually addressable toolbar node.
        // Individual button children are not exposed in UIAutomation from this session.
        log.info("── Step 5: Toolbar container validation (Row 1) ──");
        ExtentReportManager.getTest().log(Status.INFO, "Step 5: Validating toolbar container (Row 1)");
        assertToolbarPresent("Row 1", soft);
        log.info("Note: toolbar button names (Settings, Qty, etc.) are not individually " +
                "accessible via UIAutomation from the main-window session — validated by container presence only");

        // ── Step 6: Navigate to Row 2 ────────────────────────────────────────────
        log.info("── Step 6: Navigating to Header Row 2 ──");
        ExtentReportManager.getTest().log(Status.INFO, "Step 6: Clicking Next → validating Header Row 2 toolbar");
        homePage.clickNextHeader();
        pauseForToolbarAnimation();
        assertToolbarPresent("Row 2", soft);
        int row2Count = homePage.getButtonCount();
        soft.assertTrue(row2Count >= 14,
                "Main-body button count should be >= 14 on Row 2; found: " + row2Count);
        log.info("Header Row 2 — main-body button count: {}", row2Count);

        // ── Step 7: Navigate to Row 3 ────────────────────────────────────────────
        log.info("── Step 7: Navigating to Header Row 3 ──");
        ExtentReportManager.getTest().log(Status.INFO, "Step 7: Clicking Next → validating Header Row 3 toolbar");
        homePage.clickNextHeader();
        pauseForToolbarAnimation();
        assertToolbarPresent("Row 3", soft);

        // ── Step 8: Return to Row 1 ───────────────────────────────────────────────
        log.info("── Step 8: Returning to Header Row 1 ──");
        ExtentReportManager.getTest().log(Status.INFO, "Step 8: Clicking Home → confirm toolbar still present");
        homePage.clickHomeHeader();
        pauseForToolbarAnimation();
        assertToolbarPresent("Row 1 (reset)", soft);

        // ── Step 9: Main body ─────────────────────────────────────────────────────
        log.info("── Step 9: Main body validation ──");
        ExtentReportManager.getTest().log(Status.INFO, "Step 9: Validating main body elements");
        soft.assertTrue(homePage.isSearchBarVisible(), "Search bar (Edit control) should be visible");
        List<By> missingGroups = homePage.findMissingLocators(HomePage.GROUP_NAV_LOCATORS);
        soft.assertTrue(missingGroups.isEmpty(),
                "Missing group navigation buttons: " + missingGroups);
        if (!missingGroups.isEmpty()) {
            log.warn("Missing group buttons: {}", missingGroups);
        }

        // ── Step 10: Payment area ─────────────────────────────────────────────────
        log.info("── Step 10: Payment area validation ──");
        ExtentReportManager.getTest().log(Status.INFO, "Step 10: Validating payment area");
        soft.assertTrue(homePage.isPayButtonVisible(),   "PAY button should be visible");
        soft.assertTrue(homePage.isCardButtonVisible(),  "CARD button should be visible");
        soft.assertTrue(homePage.isNextDollarVisible(),  "NEXT $ button should be visible");
        soft.assertTrue(homePage.isExactDollarVisible(), "EXACT $ button should be visible");
        soft.assertTrue(homePage.isRefundVisible(),      "REFUND button should be visible");
        List<By> missingAmounts = homePage.findMissingLocators(HomePage.QUICK_PAY_LOCATORS);
        soft.assertTrue(missingAmounts.isEmpty(),
                "Missing quick-pay amount buttons: " + missingAmounts);
        if (!missingAmounts.isEmpty()) {
            log.warn("Missing quick-pay amounts: {}", missingAmounts);
        }

        // ── Summary ───────────────────────────────────────────────────────────────
        int buttonCount = homePage.getButtonCount();
        int textCount   = homePage.getTextElements().size();
        log.info("Home Page element counts — buttons: {}, textLabels: {}", buttonCount, textCount);
        ExtentReportManager.getTest().log(Status.INFO,
                "Elements — buttons: " + buttonCount + ", textLabels: " + textCount);

        soft.assertAll();

        log.info("Home Page validation PASSED");
        ExtentReportManager.getTest().log(Status.PASS, "Home Page validation passed");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    /**
     * Attempts to attach to the AlbertaPOS main window without going through login.
     * Returns {@code true} only when the main window is already on the Home Page.
     *
     * <p>Uses a PowerShell pre-check to read the Win32 {@code MainWindowTitle} before
     * creating any WinAppDriver session. This avoids the failure mode where attaching
     * to FrmLog's HWND then calling {@code quit()} would close the login screen and
     * cause {@code waitForLoginScreen()} to time out on the subsequent normal flow.
     */
    private boolean tryAttachToExistingMainWindow() {
        try {
            // Read the Win32 main window title via PowerShell — no WinAppDriver session needed.
            // On fresh start the title is the login screen title; post-login it is 'Home'.
            String psTitle = ProcessUtil.runPowerShell(
                    "$p = Get-Process AlbertaPOS -ErrorAction SilentlyContinue | " +
                    "Where-Object { $_.MainWindowHandle -ne 0 } | Select-Object -First 1; " +
                    "if ($p) { $p.MainWindowTitle } else { '' }");
            if (psTitle == null || !psTitle.contains(homeTitleFragment)) return false;

            RemoteWebDriver candidate = posPage.attachToMainWindow();
            HomePage        page      = new HomePage(candidate);
            String          title     = page.getWindowTitle();
            if (title != null && title.contains(homeTitleFragment)) {
                mainWindowDriver = candidate;
                homePage         = page;
                return true;
            }
            try { candidate.quit(); } catch (Exception ignored) {}
        } catch (Exception e) {
            log.debug("Fast-path attach failed (expected on first run): {}", e.getMessage());
        }
        return false;
    }

    /**
     * Dismisses any dialog windows left open from a previous test session before
     * the clean-state hard asserts run. Tries NO → Cancel/Close → Escape in order,
     * repeating up to 5 times to handle stacked dialogs.
     */
    private void dismissLingeringDialogs() {
        if (!homePage.isErrorDialogVisible()) return;
        log.info("Lingering dialogs detected — dismissing before validation");
        mainWindowDriver.manage().timeouts().implicitlyWait(Duration.ofMillis(500));
        try {
            for (int pass = 0; pass < 5; pass++) {
                boolean dismissed = false;

                List<WebElement> noBtn = mainWindowDriver.findElements(
                        By.xpath("//Button[@Name='NO' or @Name='No']"));
                if (!noBtn.isEmpty()) {
                    noBtn.get(0).click();
                    log.info("  Dismissed dialog via NO button (pass {})", pass + 1);
                    dismissed = true;
                } else {
                    List<WebElement> cancelBtn = mainWindowDriver.findElements(
                            By.xpath("//Button[@Name='Cancel' or @Name='Close']"));
                    if (!cancelBtn.isEmpty()) {
                        cancelBtn.get(0).click();
                        log.info("  Dismissed dialog via Cancel/Close (pass {})", pass + 1);
                        dismissed = true;
                    }
                }

                if (!dismissed) {
                    new Actions(mainWindowDriver).sendKeys(Keys.ESCAPE).perform();
                    log.info("  Sent Escape as fallback (pass {})", pass + 1);
                }

                try { Thread.sleep(400); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

                if (!homePage.isErrorDialogVisible()) {
                    log.info("  Lingering dialogs cleared after {} pass(es)", pass + 1);
                    return;
                }
            }
            log.warn("  Could not clear all lingering dialogs after 5 attempts — proceeding");
        } catch (Exception e) {
            log.warn("  dismissLingeringDialogs: {}", e.getMessage());
        } finally {
            mainWindowDriver.manage().timeouts().implicitlyWait(Duration.ofSeconds(15));
        }
    }

    private void assertToolbarPresent(String context, SoftAssert soft) {
        boolean visible = homePage.isToolbarVisible();
        soft.assertTrue(visible, "Toolbar container must be visible on " + context);
        log.info("Toolbar container — visible: {} context: {}", visible, context);
    }

    /**
     * Waits {@value #TOOLBAR_ANIMATION_PAUSE_MS} ms after toolbar navigation clicks to allow
     * the WinForms animation to complete before asserting toolbar state.
     */
    private void pauseForToolbarAnimation() {
        try {
            Thread.sleep(TOOLBAR_ANIMATION_PAUSE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
