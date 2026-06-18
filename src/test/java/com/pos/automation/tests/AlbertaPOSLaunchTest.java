package com.pos.automation.tests;

import com.aventstack.extentreports.Status;
import com.pos.automation.annotations.TestDataSheet;
import com.pos.automation.base.BaseTest;
import com.pos.automation.listeners.RetryAnalyzer;
import com.pos.automation.pages.AlbertaPOSPage;
import com.pos.automation.pages.LoginPage;
import com.pos.automation.utils.ConfigReader;
import com.pos.automation.utils.ExcelUtil;
import com.pos.automation.utils.ExtentReportManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.HashMap;

/**
 * Validates that AlbertaPOS launches successfully, the FrmLog login screen is reachable,
 * credentials are accepted, and the main window title is correct.
 *
 * <h3>Flow</h3>
 * <ol>
 *   <li>BaseTest &#64;BeforeSuite — WinAppDriver auto-started (if elevated) then AlbertaPOS launched</li>
 *   <li>&#64;BeforeClass — page objects wired up, credentials read from config</li>
 *   <li>&#64;Test:
 *     <ul>
 *       <li>Step 1 — dismiss any Windows UAC / security popup</li>
 *       <li>Step 2 — wait for FrmLog login screen; assert title == "FrmLog"</li>
 *       <li>Step 3 — submit credentials</li>
 *       <li>Step 4 — wait for main window</li>
 *       <li>Step 5 — assert main window title is valid and contains expected fragment</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <h3>Run</h3>
 * <pre>mvn clean test</pre>
 */
@TestDataSheet(sheetName = "Launch")
public class AlbertaPOSLaunchTest extends BaseTest {

    private static final Logger log = LoggerFactory.getLogger(AlbertaPOSLaunchTest.class);

    private AlbertaPOSPage posPage;
    private LoginPage      loginPage;
    private String         posUsername;
    private String         posPassword;
    private String         expectedTitle;
    private String         loginWindowTitle;

    // ── Setup ────────────────────────────────────────────────────────────────────

    @BeforeClass
    public void initPage() {
        String winAppDriverUrl = ConfigReader.getString(ConfigReader.WIN_APP_DRIVER_URL);
        expectedTitle          = ConfigReader.getString(ConfigReader.APP_WINDOW_TITLE, "Alberta");
        loginWindowTitle       = ConfigReader.getString(ConfigReader.LOGIN_WINDOW_TITLE, "Alberta POS Login");
        posUsername            = ConfigReader.getString(ConfigReader.POS_USERNAME);
        posPassword            = ConfigReader.getString(ConfigReader.POS_PASSWORD);
        posPage   = new AlbertaPOSPage(driver, winAppDriverUrl, expectedTitle);
        loginPage = new LoginPage(driver);
        log.info("Pages initialised (expectedTitleFragment='{}')", expectedTitle);
    }

    // ── DataProvider ─────────────────────────────────────────────────────────────

    @DataProvider(name = "testData")
    public static Object[][] provideTestData() {
        try {
            ExcelUtil excel = new ExcelUtil();
            TestDataSheet sheet = AlbertaPOSLaunchTest.class.getAnnotation(TestDataSheet.class);
            return excel.getDataAsObjectArray(sheet.sheetName());
        } catch (Exception e) {
            log.warn("Excel data not available for Launch sheet — running with config defaults: {}", e.getMessage());
            return new Object[][]{{new HashMap<String, String>()}};
        }
    }

    // ── Test ─────────────────────────────────────────────────────────────────────

    @Test(description = "AlbertaPOS application launches, FrmLog screen confirmed, main window title validated",
          retryAnalyzer = RetryAnalyzer.class)
    public void testAlbertaPOSLaunchesSuccessfully() {

        // Step 1: Dismiss any Windows security / permission popup
        log.info("Step 1 — handling potential Windows permission popup");
        ExtentReportManager.getTest().log(Status.INFO, "Step 1: Handling Windows permission popup (if any)");
        posPage.handleWindowsPermissionPopup(POPUP_TIMEOUT_SECONDS);

        // Step 2: Wait for the FrmLog login screen; assert the window title is "FrmLog"
        log.info("Step 2 — waiting for login screen (FrmLog)");
        ExtentReportManager.getTest().log(Status.INFO, "Step 2: Waiting for login screen (FrmLog)");
        posPage.waitForLoginScreen(LOGIN_SCREEN_TIMEOUT_SECONDS);
        String loginScreenTitle = driver.getTitle();
        Assert.assertEquals(loginScreenTitle, loginWindowTitle,
                "Login screen title must be '" + loginWindowTitle + "' but found: '" + loginScreenTitle + "'");
        log.info("Login screen confirmed — title: '{}'", loginScreenTitle);
        ExtentReportManager.getTest().log(Status.INFO, "Login screen confirmed — title: '" + loginScreenTitle + "'");

        // Step 3: Submit login credentials
        log.info("Step 3 — logging in (username='{}')", posUsername);
        ExtentReportManager.getTest().log(Status.INFO, "Step 3: Submitting credentials (username='" + posUsername + "')");
        loginPage.login(posUsername, posPassword);

        // Step 4: Wait for the main window after login
        log.info("Step 4 — waiting for AlbertaPOS main window");
        ExtentReportManager.getTest().log(Status.INFO, "Step 4: Waiting for main POS window");
        posPage.waitForMainWindowToLoad(MAIN_WINDOW_TIMEOUT_SECONDS);

        // Step 5: Validate main window is accessible and correctly titled
        log.info("Step 5 — validating main window title");
        ExtentReportManager.getTest().log(Status.INFO, "Step 5: Validating main window title");
        String title = validateMainWindow();
        log.info("AlbertaPOS launch test PASSED — title: '{}'", title);
        ExtentReportManager.getTest().log(Status.PASS, "Main window confirmed — title: '" + title + "'");
    }

    // ── Validation ───────────────────────────────────────────────────────────────

    private String validateMainWindow() {
        Assert.assertTrue(posPage.isMainWindowVisible(),
                "AlbertaPOS main window must be visible after launch");

        String title = posPage.getWindowTitle();
        Assert.assertNotNull(title, "Main window title must not be null");
        Assert.assertFalse(title.isBlank(),
                "Main window title must not be blank — got: '" + title + "'");
        Assert.assertTrue(title.contains(expectedTitle),
                "Main window title must contain '" + expectedTitle + "' but was: '" + title + "'");

        log.info("Window title validation passed: '{}'", title);
        return title;
    }
}
