# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test Commands

```bash
# Run the smoke suite (AlbertaPOSLaunchTest — launch + login + main window)
mvn clean test

# Run the Home Page validation suite
mvn clean test -DsuiteXmlFile=src/test/resources/testng-homepage.xml

# Run the Home Page test suite
mvn clean test -DsuiteXmlFile=src/test/resources/testng-hometest.xml

# Run the Transaction flow suite
mvn clean test -DsuiteXmlFile=src/test/resources/testng-transaction.xml

# Run all test classes in one suite (app launched once — see full-suite note below)
mvn clean test -DsuiteXmlFile=src/test/resources/testng-full-suite.xml

# Run a single test class
mvn test -Dtest=AlbertaPOSLaunchTest

# Run a single test method
mvn test -Dtest=AlbertaPOSLaunchTest#testAlbertaPOSLaunchesSuccessfully

# Generate sample Excel test data (run once to create POSTestData.xlsx)
mvn test-compile exec:java -Dexec.mainClass="com.pos.automation.util.CreateSampleExcel" -Dexec.classpathScope="test"

# Compile only (no tests)
mvn clean compile

# Package without tests
mvn clean package -DskipTests
```

> **Note:** Each individual TestNG suite (`testng.xml`, `testng-homepage.xml`, etc.) launches AlbertaPOS once via `@BeforeSuite`. `testng-full-suite.xml` runs all classes in one launch — read the login state warning in that file before using it in CI.

**Prerequisites**: WinAppDriver will be auto-started if the test JVM runs as Administrator. Otherwise start it manually at `http://127.0.0.1:4723` before execution. The target app (`AlbertaPOS.exe`) must be installed at the path in `config.properties`.

**Reports**: Extent HTML reports land in `target/reports/ExtentReport_{timestamp}/index.html`. Surefire XML reports go to `target/surefire-reports/`. Logs go to `logs/automation.log` (rolling daily, 7-day retention). Failure screenshots go to `target/screenshots/`.

---

## Architecture

This is a **Windows desktop UI automation framework** using Appium Java Client 8.6.0 + Selenium 4.21.0 + WinAppDriver 1.x + TestNG 7.10.2 with Extent Reports 5, Apache POI, and MySQL JDBC.

### Framework Structure

```
src/main/java/com/pos/automation/
├── annotations/
│   └── TestDataSheet.java          — @TestDataSheet(sheetName="…") class-level annotation
├── base/
│   ├── BaseTest.java               — @BeforeSuite/@AfterSuite + shared @BeforeMethod/@AfterMethod
│   ├── WinAppDriverCommandExecutor.java   — W3C/JSONWP bridge (DO NOT MODIFY)
│   └── WinAppDriverSessionFactory.java    — Session creation factory (DO NOT MODIFY)
├── listeners/
│   ├── ExtentReportListener.java   — ITestListener + ISuiteListener → drives Extent Reports
│   └── RetryAnalyzer.java          — IRetryAnalyzer → retries failed tests (configurable)
├── pages/
│   ├── BasePage.java               — Abstract driver holder + element helpers
│   ├── AlbertaPOSPage.java         — App startup, UAC handling, main window attachment
│   ├── LoginPage.java              — FrmLog login with primary+fallback locators
│   ├── HomePage.java               — Home page element discovery and validation
│   └── TransactionPage.java        — Full cash transaction flow
└── utils/
    ├── ConfigReader.java           — Centralized config with typed constants and accessors
    ├── CustomAction.java           — Reusable UI action library (WinAppDriver-aware)
    ├── DatabaseUtil.java           — MySQL JDBC utility with lazy connection management
    ├── ExcelUtil.java              — Apache POI XSSF data reader + result writer
    ├── ExtentReportManager.java    — Singleton Extent Reports lifecycle manager
    ├── ProcessUtil.java            — PowerShell runner utility
    ├── PropertyReader.java         — Legacy config reader (kept for backward compatibility)
    └── ScreenshotUtil.java         — Robot-based full-desktop screenshot capture

src/test/java/com/pos/automation/
├── tests/
│   ├── AlbertaPOSLaunchTest.java   — @TestDataSheet("Launch") — 5-step launch+login test
│   ├── HomePageTest.java           — @TestDataSheet("HomePage") — Home Page validation
│   ├── HomePageValidationTest.java — @TestDataSheet("HomePage") — Comprehensive HP validation
│   └── TransactionTest.java        — @TestDataSheet("Transaction") — 11-step transaction flow
└── util/
    └── CreateSampleExcel.java      — One-time generator for POSTestData.xlsx

src/test/resources/
├── config.properties               — All framework configuration
├── logback-test.xml                — SLF4J/Logback configuration
├── testng.xml                      — Smoke suite (AlbertaPOSLaunchTest)
├── testng-homepage.xml             — Home Page validation suite
├── testng-hometest.xml             — Home Page test suite
├── testng-transaction.xml          — Transaction flow suite
├── testng-full-suite.xml           — All tests in one launch
└── testdata/
    └── POSTestData.xlsx            — Excel test data (Launch, HomePage, Transaction sheets)
```

### The W3C / JSONWP Compatibility Bridge

The critical architectural piece is `WinAppDriverCommandExecutor`. WinAppDriver 1.x speaks JSONWP, but Selenium 4 validates W3C capabilities and strips non-W3C keys (`app`, `deviceName`) before sending requests. The executor fixes this by:
- Intercepting `NEW_SESSION` and sending a raw JSONWP POST (`{"desiredCapabilities": {...}}`) directly over HTTP, bypassing Selenium's codec
- Then wiring W3C codecs (`W3CHttpCommandCodec` / `W3CHttpResponseCodec`) for all subsequent commands

**Do not replace** this with a standard `AppiumDriver` or `RemoteWebDriver` — session creation will fail.

### WinAppDriver Lifecycle

`BaseTest.setUp()` now attempts to auto-start WinAppDriver via `ProcessBuilder(exePath)` before falling back to the manual-wait loop. Auto-start succeeds when the test JVM is running as Administrator. Set `stopWinAppDriverAfterSuite=true` in `config.properties` to kill WinAppDriver after the suite completes (default: `false` to preserve it for the next run).

### Test Pattern

All tests extend `BaseTest`, which uses `@BeforeSuite`/`@AfterSuite` for the single shared `RemoteWebDriver` instance and `@BeforeMethod`/`@AfterMethod` for per-test logging and failure screenshots. Tests use page objects for UI interactions. All duplicate methods (`logTestStart`, `logTestResult`, `formatEpochMillis`) live in `BaseTest`.

**Full-suite fast-path:** `HomePageTest` and `TransactionTest` both implement `tryAttachToExistingMainWindow()` — checks PowerShell for a non-login AlbertaPOS window and attaches directly, skipping the login flow when the app is already on the Home Page (i.e., when a prior test in the same suite logged in first).

**Critical — `@AfterClass` must NOT call `quit()` on `appTopLevelWindow` sessions:** WinAppDriver 1.x sends `WM_CLOSE` to the target HWND when `DELETE /session/{id}` is called for an `appTopLevelWindow` session. This closes AlbertaPOS, breaking subsequent tests in the same suite. `HomePageTest` and `TransactionTest` both null out `mainWindowDriver` without calling `quit()`. Stale sessions are cleaned up by `WinAppDriverManager.deleteStaleSessionsWithRetry()` at the next `@BeforeSuite`.

**Blocking popup dismissal in TransactionTest:** `dismissAnyBlockingPopup()` clicks `btnNo` up to 3 times after barcode entry to dismiss age-check dialogs and "Customer not found" popups that may appear for age-restricted items.

**`testng.xml` suite naming:** Each `<test>` element must have a unique name within the suite. Current names: `POS Smoke Tests`, `Home Page Test`, `Transaction Test`.

### Page Object Model

- `BasePage` — abstract `RemoteWebDriver` holder with shared `findElement`, `findElements`, `isElementPresent`, `getWindowTitle`
- `AlbertaPOSPage` — handles UAC popups, waits for main window (app session title-change or PowerShell Win32 HWND), attaches to main window via `appTopLevelWindow`; `getWindowTitle()` excludes FrmLog title variants (`!contains("login")`) so `confirmedWindowTitle` is returned after login
- `LoginPage` — FrmLog credential entry; Strategy A uses confirmed stable AutomationIds (`txtUserName`, `txtPassword`, `btnLogin`); login success detected via PowerShell Win32 `Get-Process AlbertaPOS -notlike '*Login*'` with 8-attempt 1 s polling (FrmLog HWND session `getTitle()` never changes post-login); Strategy B uses W3C PointerInput absolute screen coordinates (1920×1080 calibrated) as fallback; inter-action delays defined as named constants (`FOCUS_DELAY_MS`, `DIGIT_DELAY_MS`, `SUBMIT_DELAY_MS`, `COORD_DELAY_MS`); session restore popup dismissed via `AutomationId='btnYes'` (popup container `AutomationId='popupSessionRestore'` — appears after LOG IN when a previous session was not properly closed; `SESSION_YES_BUTTON` locator must use AutomationId not Name)
- `HomePage` — all locators confirmed via WinAppDriver REST API live inspection; toolbar buttons individually accessible by AutomationId (`toolbar_Home`, `toolbar_Back` for Next); toolbar navigation uses direct UIAutomation element clicks (no `java.awt.Robot` or screen coordinates); quick-pay amounts use `btnTenderOne/Five/Ten/Twenty/Fifty/Hundred`; search bar uses `txtItemLookup`; toolbar container uses `AutomationId='toolbar'`
- `TransactionPage` — full cash transaction; all locators confirmed via WinAppDriver REST inspection: barcode field = `txtItemLookup` (same as HomePage search bar, no fallback chain); PAY = `btnCashpay`; CARD = `btnCardpay`; Cash tender = `(//*[@AutomationId='btnDottedButton'])[1]` (developer has not set distinct AutomationIds on tender buttons — all share `btnDottedButton`; first button = Cash); cash rounding popup reuses `popupSessionRestore` container with `btnYes`/`btnNo`; save dialog two-path strategy unchanged; `clickCard()` added for Card payment support

### Confirmed AlbertaPOS AutomationIds (via WinAppDriver REST inspection)

> PowerShell / Inspect.exe shows HWND-based numeric IDs; WinAppDriver reads the WinForms `Control.Name` property. Always use the IDs below — not Inspect.exe values — for XPath locators.

- **Login:** `txtUserName`, `txtPassword`, `btnLogin`, `btn0`–`btn9`, `btnBack`, `btnClear`
- **Session restore popup:** container `popupSessionRestore`; YES = `btnYes`; NO = `btnNo` — appears after LOG IN when a prior session was not cleanly closed; `SESSION_YES_BUTTON` must use AutomationId, not Name
- **Toolbar:** container `toolbar`; navigation via `toolbar_Home` (Row 1) and `toolbar_Back` (Next row); other buttons follow `toolbar_{Label}` pattern — toolbar buttons are individually clickable, no Robot or screen coordinates needed
- **Group navigation:** `GROUP1`–`GROUP5`
- **Quick-pay amounts:** `btnTenderOne`, `btnTenderFive`, `btnTenderTen`, `btnTenderTwenty`, `btnTenderFifty`, `btnTenderHundred`
- **Payment area:** `btnCashpay`, `btnCardpay`, `btnNext`, `btnExact`, `btnRefund`
- **Search bar / barcode field:** `txtItemLookup` (shared between HomePage search and TransactionPage barcode entry)
- **Transaction — CustomPayment popup:** container `CustomPayment`; tender type buttons all share `AutomationId='btnDottedButton'` Name='Tender Button' (no unique IDs per tender type); Cash = first `btnDottedButton` i.e. `(//*[@AutomationId='btnDottedButton'])[1]`
- **Cash rounding popup:** reuses `popupSessionRestore` container + `btnYes` (confirm) / `btnNo` (cancel) — same component as session restore popup

### CustomAction Utility

`CustomAction` (instance class) wraps all common UI interactions with `FluentWait`, logging, and WinAppDriver-specific implementations:
- No JS execution (not supported by WinAppDriver)
- `selectDropdown` opens ComboBox then clicks `//ListItem[@Name='…']`
- `acceptAlert`/`dismissAlert` find `//Button[@Name='OK'/'Yes'/'Cancel'/'No']` — no `driver.switchTo().alert()`
- `highlightElement` logs Name/AutomationId attributes (no DOM manipulation)

### Data-Driven Testing

Add `@TestDataSheet("SheetName")` at test class level. Add a `@DataProvider(name="testData")` method that calls `new ExcelUtil().getDataAsObjectArray(sheet.sheetName())`. Test data lives in `src/test/resources/testdata/POSTestData.xlsx` (path configurable via `excelFilePath` in `config.properties`).

After test execution, call `ExcelUtil.updateRowResult(sheetName, rowIndex, status, durationMs, systemName, timestamp)` to write results back to the Excel row. `TransactionTest` injects a `__rowIndex__` key into each DataProvider HashMap so the test method can identify the correct row for write-back.

**Transaction sheet columns (`POSTestData.xlsx` → Sheet: `Transaction`):**

| Column | Source | Notes |
|--------|--------|-------|
| `TestCaseId` | Excel | Logged and shown in Extent report |
| `TestDescription` | Excel | Scenario label |
| `Username` | config only | Always read from `config.properties` — not from Excel |
| `Password` | config only | Always read from `config.properties` — not from Excel |
| `Barcode` | Excel | Required — test fails early if blank |
| `ExpectedItemInGrid` | Excel | Integer; grid confirmed ≥1 row |
| `PaymentMethod` | Excel | `Cash` or `Card` |
| `ExpectCashDiscountPopup` | Excel | `YES`/`NO` — logged; `proceedWithCashPrice()` always called (safe no-op when absent) |
| `ReceiptSaveFolder` | Excel | Authoritative source for PROD; falls back to `config.receiptsFolder` with a WARN log if blank; assertion fails if both are blank |
| `Execute` | Excel | `NO` rows skipped by ExcelUtil automatically |
| `Status`, `Duration(ms)`, `SystemName`, `Timestamp` | written back | Updated by `ExcelUtil.updateRowResult()` in `finally` block |
| `SavedReceiptPath` | written back | Complete receipt file path (folder + filename + extension) written after each run by `ExcelUtil.updateCellValue()` — column auto-created if absent |

**`TransactionTest` DataProvider pattern:** injects `__rowIndex__` into each row's HashMap so write-back targets the correct Excel row. The `@Test` must declare `dataProvider = "testData"` and accept `HashMap<String, String> testData` as a parameter. Each enabled Excel row drives one independent test invocation.

**`ReceiptSaveFolder` enforcement:** Excel column is the authoritative source. If blank, a `WARN` is logged and `config.receiptsFolder` is used as fallback. If both are blank the test fails immediately with a clear assertion message. For PROD, every enabled row must have `ReceiptSaveFolder` populated so the fallback never triggers.

**`ExcelUtil.updateCellValue(sheetName, rowIndex, columnName, value)`:** generic cell-write helper added to `ExcelUtil`; finds or auto-creates the column by header name and writes the value. Used by `TransactionTest` to persist `SavedReceiptPath` alongside the standard result columns.

### Extent Reports

`ExtentReportListener` (wired via `<listeners>` in every TestNG XML) drives `ExtentReportManager`:
- Report output: `target/reports/ExtentReport_{yyyyMMdd_HHmmss}/index.html`
- Failure screenshots attached automatically via `ScreenshotUtil.capture()`
- Retry attempts logged with "(Retry attempt N)" suffix

### Retry

`RetryAnalyzer` retries failed tests up to `retryCount` times (default 2, configurable in `config.properties`). Wire per test: `@Test(retryAnalyzer = RetryAnalyzer.class)`.

### Database Validation

`DatabaseUtil` provides static methods (`fetchSingleValue`, `fetchAllRecords`, `validateRecord`, `executeUpdate`) backed by a lazy-initialized MySQL `Connection`. Credentials live in `config.properties` (`dbDriver`, `dbUrl`, `dbUsername`, `dbPassword`). Call `DatabaseUtil.closeConnection()` in `@AfterSuite` when DB validation is used.

### Configuration

All config in `src/test/resources/config.properties`. Use `ConfigReader` (new API) or `PropertyReader` (backward-compatible legacy). `ConfigReader` exposes named constants for every key:

| Key | Purpose |
|-----|---------|
| `appPath` | POS executable path |
| `winAppDriverUrl` | WinAppDriver server URL |
| `winAppDriverExePath` | WinAppDriver.exe path for auto-start |
| `implicitWait` | Implicit wait seconds |
| `appWindowTitleFragment` | Title fragment for main window detection; current value: `Home` (matches AlbertaPOS main window title — do not set to `Alberta`, which also matches FrmLog login title) |
| `homePageTitleFragment` | Title fragment after `appTopLevelWindow` attach |
| `appLaunchWaitSeconds` | `ms:waitForAppLaunch` value |
| `posUsername` / `posPassword` | POS login credentials (TransactionTest always reads credentials from config, not from Excel) |
| `receiptsFolder` | Receipt save directory — default fallback when `ReceiptSaveFolder` Excel column is blank |
| `environment` | Current value: `PROD` — logged and shown in Extent report metadata; no conditional logic is driven by this value |
| `executionMode` | local / ci |
| `stopWinAppDriverAfterSuite` | Kill WinAppDriver after suite (default false) |
| `reportPath` | Extent report base directory |
| `screenshotPath` | Screenshot directory |
| `retryCount` | Max retry attempts (default 2) |
| `excelFilePath` | Path to POSTestData.xlsx |
| `dbDriver` / `dbUrl` / `dbUsername` / `dbPassword` | MySQL connection |

### Dependencies

| Dependency | Version | Purpose |
|-----------|---------|---------|
| Appium Java Client | 8.6.0 | Last version with JSONWP (required for WinAppDriver 1.x) |
| Selenium | 4.21.0 | WebDriver API |
| TestNG | 7.10.2 | Test runner |
| Gson | 2.11.0 | JSON in `WinAppDriverCommandExecutor` |
| ExtentReports | 5.1.2 | HTML test reports |
| Apache POI OOXML | 5.2.5 | Excel data-driven testing |
| MySQL Connector/J | 8.3.0 | Database validation |
| SLF4J + Logback | 2.0.13 / 1.5.6 | Logging |
