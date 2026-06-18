package com.pos.automation.tests;

import com.aventstack.extentreports.Status;
import com.pos.automation.annotations.TestDataSheet;
import com.pos.automation.base.BaseTest;
import com.pos.automation.listeners.RetryAnalyzer;
import com.pos.automation.pages.AlbertaPOSPage;
import com.pos.automation.pages.HomePage;
import com.pos.automation.pages.LoginPage;
import com.pos.automation.pages.TransactionPage;
import com.pos.automation.utils.ConfigReader;
import com.pos.automation.utils.ExcelUtil;
import com.pos.automation.utils.ExtentReportManager;
import com.pos.automation.utils.ProcessUtil;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import org.testng.asserts.SoftAssert;

import java.io.File;
import java.net.InetAddress;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;

/**
 * Data-driven cash/card transaction flow test for AlbertaPOS.
 *
 * <p>Every test parameter is sourced from the "Transaction" sheet in
 * {@code src/test/resources/testdata/POSTestData.xlsx}.
 * Each enabled row (Execute ≠ NO) drives one independent test invocation.
 * Test results (Status, Duration, SystemName, Timestamp) are written back
 * to the same Excel row via {@link ExcelUtil#updateRowResult}.
 *
 * <h3>Excel columns consumed</h3>
 * <ul>
 *   <li>{@value #COL_TEST_CASE_ID}         — test identifier logged and reported</li>
 *   <li>{@value #COL_TEST_DESCRIPTION}     — scenario label shown in Extent report</li>
 *   <li>{@value #COL_USERNAME}             — login username (falls back to config if blank)</li>
 *   <li>{@value #COL_PASSWORD}             — login password (falls back to config if blank)</li>
 *   <li>{@value #COL_BARCODE}              — product barcode to scan (required, must not be blank)</li>
 *   <li>{@value #COL_EXPECTED_ITEM_IN_GRID}— expected row count in transaction grid (integer)</li>
 *   <li>{@value #COL_PAYMENT_METHOD}       — {@code Cash} or {@code Card}</li>
 *   <li>{@value #COL_EXPECT_CASH_DISCOUNT} — {@code YES} to assert the rounding popup appears</li>
 *   <li>{@value #COL_RECEIPT_FOLDER}       — per-row receipt directory (falls back to config if blank)</li>
 *   <li>Execute                            — {@code NO} rows skipped by ExcelUtil automatically</li>
 * </ul>
 *
 * <h3>Full-suite fast-path</h3>
 * {@link #tryAttachToExistingMainWindow()} skips the login flow when AlbertaPOS
 * is already on the Home Page from a prior test in the same suite run.
 *
 * <h3>Run</h3>
 * <pre>mvn test -DsuiteXmlFile=src/test/resources/testng-transaction.xml</pre>
 */
@TestDataSheet(sheetName = "Transaction")
public class TransactionTest extends BaseTest {

    private static final Logger log = LoggerFactory.getLogger(TransactionTest.class);

    // ── Excel column name constants (match Transaction sheet header row exactly) ─
    private static final String COL_TEST_CASE_ID          = "TestCaseId";
    private static final String COL_TEST_DESCRIPTION      = "TestDescription";
    private static final String COL_BARCODE                = "Barcode";
    private static final String COL_EXPECTED_ITEM_IN_GRID  = "ExpectedItemInGrid";
    private static final String COL_PAYMENT_METHOD         = "PaymentMethod";
    private static final String COL_EXPECT_CASH_DISCOUNT   = "ExpectCashDiscountPopup";
    private static final String COL_RECEIPT_FOLDER         = "ReceiptSaveFolder";

    // Internal key injected by DataProvider to carry the 1-based Excel row index for write-back
    private static final String COL_ROW_INDEX              = "__rowIndex__";

    private static final DateTimeFormatter RESULT_TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // ── Suite-scoped state — initialised once in @BeforeClass ────────────────────
    private AlbertaPOSPage posPage;
    private LoginPage      loginPage;
    private String         homeTitleFragment;
    private String         defaultReceiptsFolder;   // fallback when COL_RECEIPT_FOLDER is blank
    private String         configUsername;           // fallback when COL_USERNAME is blank
    private String         configPassword;           // fallback when COL_PASSWORD is blank

    // ── Per-invocation state — reset at the top of each @Test call ───────────────
    private RemoteWebDriver mainWindowDriver;
    private HomePage        homePage;
    private TransactionPage transactionPage;

    // ── Setup / Teardown ─────────────────────────────────────────────────────────

    @BeforeClass
    public void initPages() {
        String winAppDriverUrl = ConfigReader.getString(ConfigReader.WIN_APP_DRIVER_URL);
        String titleFragment   = ConfigReader.getString(ConfigReader.APP_WINDOW_TITLE, "Alberta");
        homeTitleFragment      = ConfigReader.getString(ConfigReader.HOME_PAGE_TITLE, "Home");
        configUsername         = ConfigReader.getString(ConfigReader.POS_USERNAME);
        configPassword         = ConfigReader.getString(ConfigReader.POS_PASSWORD);
        defaultReceiptsFolder  = ConfigReader.getString(ConfigReader.RECEIPTS_FOLDER);

        posPage   = new AlbertaPOSPage(driver, winAppDriverUrl, titleFragment);
        loginPage = new LoginPage(driver);
        log.info("Suite initialised — homeTitleFragment='{}', defaultReceiptsFolder='{}'",
                homeTitleFragment, defaultReceiptsFolder);
    }

    @AfterClass(alwaysRun = true)
    public void closeMainWindowDriver() {
        // Do NOT call quit() on appTopLevelWindow sessions — WinAppDriver 1.x sends WM_CLOSE
        // to the target HWND, which closes AlbertaPOS and breaks subsequent suite tests.
        // Stale sessions are cleaned up by WinAppDriverManager.deleteStaleSessionsWithRetry()
        // at the next @BeforeSuite run.
        mainWindowDriver = null;
        homePage         = null;
        transactionPage  = null;
    }

    // ── DataProvider ─────────────────────────────────────────────────────────────

    /**
     * Loads all enabled rows from the "Transaction" Excel sheet.
     * Injects a private {@value #COL_ROW_INDEX} key into each HashMap so the test
     * method can write results back to the correct Excel row via
     * {@link ExcelUtil#updateRowResult}.
     */
    @SuppressWarnings("unchecked")
    @DataProvider(name = "testData")
    public static Object[][] provideTestData() {
        try {
            ExcelUtil excel = new ExcelUtil();
            TestDataSheet sheet = TransactionTest.class.getAnnotation(TestDataSheet.class);
            Object[][] data = excel.getDataAsObjectArray(sheet.sheetName());
            // Inject 1-based row index for Excel write-back (row 1 = first data row after header)
            for (int i = 0; i < data.length; i++) {
                ((HashMap<String, String>) data[i][0]).put(COL_ROW_INDEX, String.valueOf(i + 1));
            }
            return data;
        } catch (Exception e) {
            log.warn("Excel data unavailable for Transaction sheet — "
                    + "running with empty row (will fail at barcode validation): {}", e.getMessage());
            return new Object[][]{{new HashMap<String, String>()}};
        }
    }

    // ── Test ─────────────────────────────────────────────────────────────────────

    /**
     * Data-driven transaction flow: login → barcode scan → PAY/CARD → receipt save.
     * All test parameters are extracted from the Excel row delivered by the DataProvider.
     *
     * @param testData map of Excel column header → cell value for this invocation's row
     */
    @Test(description = "AlbertaPOS transaction: barcode scan → payment → receipt save",
          dataProvider = "testData",
          retryAnalyzer = RetryAnalyzer.class)
    public void testTransactionFlow(HashMap<String, String> testData) {

        // ── Per-invocation reset ──────────────────────────────────────────────
        mainWindowDriver = null;
        homePage         = null;
        transactionPage  = null;

        // ── Extract test parameters from Excel row ────────────────────────────
        String testCaseId     = testData.getOrDefault(COL_TEST_CASE_ID, "N/A");
        String description    = testData.getOrDefault(COL_TEST_DESCRIPTION, "");
        String username       = configUsername;
        String password       = configPassword;
        String barcode        = testData.getOrDefault(COL_BARCODE, "").trim();
        String expectedCount  = testData.getOrDefault(COL_EXPECTED_ITEM_IN_GRID, "1").trim();
        String paymentMethod  = testData.getOrDefault(COL_PAYMENT_METHOD, "Cash").trim();
        String expectPopup    = testData.getOrDefault(COL_EXPECT_CASH_DISCOUNT, "NO").trim();
        int    rowIndex       = parsePositiveInt(testData.getOrDefault(COL_ROW_INDEX, "1"), 1);

        // Resolve receipt save folder — Excel column is the authoritative source for PROD.
        // Config 'receiptsFolder' serves only as a safety-net fallback; a WARN is emitted
        // whenever it is used so missing Excel values are visible immediately in the logs.
        String excelFolder    = testData.getOrDefault(COL_RECEIPT_FOLDER, "").trim();
        String receiptsFolder;
        if (!excelFolder.isEmpty()) {
            receiptsFolder = excelFolder;
            log.info("   receiptsFolder='{}' (source: Excel column '{}')", receiptsFolder, COL_RECEIPT_FOLDER);
        } else {
            log.warn("   '{}' column is blank for row {} — falling back to config 'receiptsFolder'. "
                    + "Populate the Excel column to avoid this fallback.",
                    COL_RECEIPT_FOLDER, rowIndex);
            receiptsFolder = defaultReceiptsFolder;
        }
        Assert.assertFalse(receiptsFolder.isBlank(),
                "Receipt save folder is not configured for row " + rowIndex
                + ". Set the '" + COL_RECEIPT_FOLDER + "' column in the Transaction sheet "
                + "or set 'receiptsFolder' in config.properties.");

        // Barcode is required — fail early with a clear, actionable message
        Assert.assertFalse(barcode.isEmpty(),
                "Excel column '" + COL_BARCODE + "' is missing or blank for row " + rowIndex
                + " in the Transaction sheet. Add a valid product barcode.");

        log.info("── [{}] {} ──", testCaseId, description);
        log.info("   barcode='{}', payment='{}', expectedItems='{}', expectPopup='{}', row={}",
                barcode, paymentMethod, expectedCount, expectPopup, rowIndex);
        ExtentReportManager.getTest().log(Status.INFO,
                "[" + testCaseId + "] " + description
                + " | barcode: " + barcode + " | payment: " + paymentMethod);

        long   startMs            = System.currentTimeMillis();
        String testStatus         = "FAILED";
        String savedReceiptPath   = null;   // complete file path written back to Excel after run

        try {
            // ── Fast-path: app already on Home Page (full-suite context) ──────
            if (tryAttachToExistingMainWindow()) {
                log.info("Fast-path: main window already accessible — skipping login flow (Steps 1–4)");
                ExtentReportManager.getTest().log(Status.INFO,
                        "Fast-path: main window already up — skipping login steps");
            } else {
                // Step 1: Dismiss UAC / permission popup
                log.info("── Step 1: Handling Windows permission popup ──");
                ExtentReportManager.getTest().log(Status.INFO, "Step 1: Handling permission popup");
                posPage.handleWindowsPermissionPopup(POPUP_TIMEOUT_SECONDS);

                // Step 2: Verify login screen
                log.info("── Step 2: Verifying login screen ──");
                ExtentReportManager.getTest().log(Status.INFO, "Step 2: Verifying login screen");
                posPage.waitForLoginScreen(LOGIN_SCREEN_TIMEOUT_SECONDS);
                verifyLoginScreenDisplayed();

                // Step 3: Submit credentials (from Excel; falls back to config if blank)
                log.info("── Step 3: Submitting login (username='{}') ──", username);
                ExtentReportManager.getTest().log(Status.INFO,
                        "Step 3: Submitting credentials (username='" + username + "')");
                loginPage.login(username, password);

                // Step 4: Wait for main window
                log.info("── Step 4: Waiting for main window ──");
                ExtentReportManager.getTest().log(Status.INFO, "Step 4: Waiting for main POS window");
                posPage.waitForMainWindowToLoad(MAIN_WINDOW_TIMEOUT_SECONDS);
                verifyLoginSuccess();

                // Step 5: Attach to main window
                log.info("── Step 5: Attaching to main window ──");
                ExtentReportManager.getTest().log(Status.INFO, "Step 5: Attaching to main window");
                mainWindowDriver = posPage.attachToMainWindow();
                homePage         = new HomePage(mainWindowDriver);
                transactionPage  = new TransactionPage(mainWindowDriver);
            }

            verifyHomePageDisplayed();

            // Step 6: Enter barcode (from Excel)
            log.info("── Step 6: Entering barcode '{}' ──", barcode);
            ExtentReportManager.getTest().log(Status.INFO, "Step 6: Entering barcode '" + barcode + "'");
            transactionPage.enterBarcode(barcode);
            dismissAnyBlockingPopup();   // age-check / "Customer not found" popup

            // Step 7: Verify transaction grid — expected item count from Excel
            int expectedRows = parsePositiveInt(expectedCount, 1);
            log.info("── Step 7: Verifying transaction grid (expectedItems≥{}) ──", expectedRows);
            ExtentReportManager.getTest().log(Status.INFO,
                    "Step 7: Verifying grid — expected items: " + expectedRows);
            transactionPage.verifyItemAddedToGrid(15);
            ExtentReportManager.getTest().log(Status.INFO,
                    "Grid confirmed — barcode: " + barcode);

            // Step 8: Initiate payment via PaymentMethod column
            log.info("── Step 8: Initiating '{}' payment ──", paymentMethod);
            ExtentReportManager.getTest().log(Status.INFO, "Step 8: Payment — " + paymentMethod);
            transactionPage.verifyPayButtonPresent();
            initiatePayment(paymentMethod);

            // Step 9 (Cash only): select Cash tender inside CustomPayment popup
            if ("Cash".equalsIgnoreCase(paymentMethod)) {
                log.info("── Step 9: Selecting Cash tender ──");
                ExtentReportManager.getTest().log(Status.INFO, "Step 9: Selecting Cash tender");
                transactionPage.clickCash();
            }

            // Step 10: Cash-discount / rounding popup — driven by ExpectCashDiscountPopup column
            log.info("── Step 10: Cash discount popup expected='{}' ──", expectPopup);
            ExtentReportManager.getTest().log(Status.INFO,
                    "Step 10: Cash discount popup expected: " + expectPopup);
            // proceedWithCashPrice() is always safe to call — it is a no-op when the popup is absent
            transactionPage.proceedWithCashPrice();
            if ("YES".equalsIgnoreCase(expectPopup)) {
                log.info("Cash discount popup was expected — dismissed");
            }

            // Step 11: Save receipt to per-row folder (from Excel; falls back to config if blank)
            log.info("── Step 11: Saving receipt to '{}' ──", receiptsFolder);
            ExtentReportManager.getTest().log(Status.INFO, "Step 11: Saving receipt");
            String savedPath   = transactionPage.saveReceipt(receiptsFolder);
            savedReceiptPath   = verifyReceiptSaved(savedPath);
            ExtentReportManager.getTest().log(Status.PASS,
                    "[" + testCaseId + "] Completed — receipt: " + savedReceiptPath);

            testStatus = "PASSED";

        } finally {
            // Write result back to the same Excel row regardless of pass/fail
            long durationMs = System.currentTimeMillis() - startMs;
            try {
                String systemName = InetAddress.getLocalHost().getHostName();
                String timestamp  = LocalDateTime.now().format(RESULT_TIMESTAMP_FMT);
                ExcelUtil excel = new ExcelUtil();
                excel.updateRowResult("Transaction", rowIndex, testStatus, durationMs,
                        systemName, timestamp);
                // Write the complete receipt file path (folder + filename + extension) to Excel
                if (savedReceiptPath != null && !savedReceiptPath.isBlank()) {
                    excel.updateCellValue("Transaction", rowIndex, "SavedReceiptPath", savedReceiptPath);
                }
                log.info("Result written to Excel — row={}, status={}, duration={}ms, receipt='{}'",
                        rowIndex, testStatus, durationMs, savedReceiptPath);
            } catch (Exception ex) {
                log.warn("Excel result write-back failed (row={}): {}", rowIndex, ex.getMessage());
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────────

    /**
     * Routes payment initiation to the correct page-object method based on the
     * PaymentMethod Excel column value. Throws for unsupported values.
     */
    private void initiatePayment(String paymentMethod) {
        switch (paymentMethod.toLowerCase()) {
            case "cash" -> transactionPage.clickPay();
            case "card" -> transactionPage.clickCard();
            default -> throw new IllegalArgumentException(
                    "Unsupported PaymentMethod '" + paymentMethod + "' in Excel row. "
                    + "Supported values: Cash, Card");
        }
    }

    /**
     * Attempts to attach to the AlbertaPOS main window without going through login.
     * Returns {@code true} only when the window is already on the Home Page.
     *
     * <p>Uses a PowerShell pre-check to read the Win32 MainWindowTitle before creating
     * any WinAppDriver session — avoids the failure mode where attaching to FrmLog's
     * HWND then calling {@code quit()} would close the login screen.
     * Same pattern as {@code HomePageTest.tryAttachToExistingMainWindow()}.
     */
    private boolean tryAttachToExistingMainWindow() {
        try {
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
                transactionPage  = new TransactionPage(candidate);
                return true;
            }
            try { candidate.quit(); } catch (Exception ignored) {}
        } catch (Exception e) {
            log.debug("Fast-path attach failed (expected on first run): {}", e.getMessage());
        }
        return false;
    }

    /**
     * Dismisses age-check dialogs and "Customer not found" popups that may appear
     * after barcode entry for age-restricted items. Clicks btnNo up to 3 times.
     * Safe no-op when no popup is present.
     */
    private void dismissAnyBlockingPopup() {
        try {
            mainWindowDriver.manage().timeouts().implicitlyWait(Duration.ofMillis(500));
            for (int pass = 0; pass < 3; pass++) {
                List<WebElement> noBtn = mainWindowDriver.findElements(
                        By.xpath("//*[@AutomationId='btnNo']"));
                if (noBtn.isEmpty()) break;
                noBtn.get(0).click();
                Thread.sleep(1000);
                log.info("Blocking popup dismissed via btnNo (pass {})", pass + 1);
            }
        } catch (Exception e) {
            log.debug("dismissAnyBlockingPopup: {}", e.getMessage());
        } finally {
            mainWindowDriver.manage().timeouts().implicitlyWait(Duration.ofSeconds(15));
        }
    }

    private void verifyLoginScreenDisplayed() {
        String title = posPage.getWindowTitle();
        Assert.assertNotNull(title, "Login screen title must not be null");
        Assert.assertFalse(title.isBlank(), "Login screen title must not be blank");
        log.info("Login screen confirmed — title: '{}'", title);
        ExtentReportManager.getTest().log(Status.INFO, "Login screen — title: '" + title + "'");
    }

    private void verifyLoginSuccess() {
        String fragment = ConfigReader.getString(ConfigReader.APP_WINDOW_TITLE, "Alberta");
        Assert.assertTrue(posPage.isMainWindowVisible(), "Main POS window must be visible after login");
        String title = posPage.getWindowTitle();
        Assert.assertNotNull(title, "Main window title must not be null");
        Assert.assertFalse(title.isBlank(), "Main window title must not be blank");
        Assert.assertTrue(title.contains(fragment),
                "Main window title must contain '" + fragment + "' but was: '" + title + "'");
        log.info("Login validated — title: '{}'", title);
        ExtentReportManager.getTest().log(Status.INFO, "Login validated — title: '" + title + "'");
    }

    private void verifyHomePageDisplayed() {
        SoftAssert soft = new SoftAssert();
        Assert.assertTrue(homePage.isDisplayed(), "Home Page window must be displayed after login");
        String homeTitle = homePage.getWindowTitle();
        Assert.assertNotNull(homeTitle, "Home Page title must not be null");
        Assert.assertFalse(homeTitle.isBlank(), "Home Page title must not be blank");
        Assert.assertTrue(homeTitle.contains(homeTitleFragment),
                "Home Page title must contain '" + homeTitleFragment + "' but was: '" + homeTitle + "'");
        Assert.assertFalse(homePage.isErrorDialogVisible(),
                "An error dialog must not be visible on the Home Page after login");
        log.info("Home Page confirmed — title: '{}'", homeTitle);
        ExtentReportManager.getTest().log(Status.INFO, "Home Page confirmed — title: '" + homeTitle + "'");
        soft.assertTrue(homePage.getButtonCount() > 0, "Home Page must have at least one Button");
        soft.assertAll();
    }

    /**
     * Asserts the receipt file was written to the configured folder.
     * The save dialog may append an extension, so this checks for any file whose
     * name starts with the base filename returned by {@link TransactionPage#saveReceipt}.
     *
     * @return complete absolute path of the saved receipt file (folder + filename + extension)
     */
    private String verifyReceiptSaved(String basePath) {
        File   folder  = new File(basePath).getParentFile();
        String base    = new File(basePath).getName();
        File[] matches = folder.listFiles((dir, name) -> name.startsWith(base));
        Assert.assertNotNull(matches, "Receipts folder not accessible: " + folder.getAbsolutePath());
        Assert.assertTrue(matches.length > 0,
                "Receipt not found — expected file starting with '" + base
                + "' in folder '" + folder.getAbsolutePath() + "'");
        String completePath = matches[0].getAbsolutePath();
        log.info("Receipt verified — complete path: '{}'", completePath);
        ExtentReportManager.getTest().log(Status.INFO, "Receipt saved: " + completePath);
        return completePath;
    }

    /** Returns {@code value} if non-null and non-blank; otherwise returns {@code fallback}. */
    private static String orDefault(String value, String fallback) {
        return (value != null && !value.isBlank()) ? value : fallback;
    }

    /** Parses a positive integer; returns {@code defaultValue} if parsing fails or result ≤ 0. */
    private static int parsePositiveInt(String value, int defaultValue) {
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
