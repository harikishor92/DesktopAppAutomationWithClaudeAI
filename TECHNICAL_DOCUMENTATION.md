# AlbertaPOS Automation Framework — Technical Documentation

> **Version:** 1.0 | **Last Updated:** 2026-05-15 | **Java:** 21 | **WinAppDriver:** 1.x

---

## Table of Contents

1. [Overview](#1-overview)
2. [Technology Stack](#2-technology-stack)
3. [Architecture Overview](#3-architecture-overview)
4. [Codebase Architecture — Deep Dive](#4-codebase-architecture--deep-dive)
   - [4.1 Package Hierarchy](#41-package-hierarchy-and-responsibilities)
   - [4.2 Class Inheritance & Composition](#42-class-inheritance-and-composition-tree)
   - [4.3 Key Dependency Graph](#43-key-dependency-graph)
   - [4.4 WinAppDriver Session Lifecycle](#44-winappdriver-session-lifecycle)
   - [4.5 Login Strategy Decision Tree](#45-login-flow--strategy-decision-tree)
   - [4.6 Transaction Sequence](#46-transaction-flow--sequence)
   - [4.7 Data-Driven Execution Flow](#47-data-driven-execution-flow)
   - [4.8 Cross-Cutting Concerns](#48-cross-cutting-concerns)
5. [Project Structure](#5-project-structure)
6. [Critical Component: W3C/JSONWP Bridge](#6-critical-component-w3cjsonwp-bridge)
7. [Base Test Framework](#7-base-test-framework)
8. [WinAppDriverManager](#8-winappdriver-manager)
9. [Page Objects](#9-page-objects)
   - [9.1 BasePage](#91-basepage)
   - [9.2 AlbertaPOSPage](#92-albertapospage)
   - [9.3 LoginPage — 5-Strategy Login](#93-loginpage--5-strategy-login)
   - [9.4 HomePage](#94-homepage)
   - [9.5 TransactionPage](#95-transactionpage)
10. [Utility Layer](#10-utility-layer)
    - [10.1 ConfigReader](#101-configreader)
    - [10.2 CustomAction](#102-customaction)
    - [10.3 ExcelUtil](#103-excelutil)
    - [10.4 DatabaseUtil](#104-databaseutil)
    - [10.5 ScreenshotUtil](#105-screenshotutil)
    - [10.6 ExtentReportManager](#106-extentreportmanager)
    - [10.7 ProcessUtil](#107-processutil)
    - [10.8 AdminPrivilegeValidator](#108-adminprivilegevalidator)
    - [10.9 PropertyReader](#109-propertyreader-legacy)
11. [Test Infrastructure](#11-test-infrastructure)
    - [11.1 @TestDataSheet Annotation](#111-testdatasheet-annotation)
    - [11.2 ExtentReportListener](#112-extentreportlistener)
    - [11.3 RetryAnalyzer](#113-retryanalyzer)
12. [Test Classes](#12-test-classes)
13. [TestNG Suites](#13-testng-suites)
14. [Configuration Reference](#14-configuration-reference)
15. [Execution Guide](#15-execution-guide)
16. [Data-Driven Testing Guide](#16-data-driven-testing-guide)
17. [Adding New Tests](#17-adding-new-tests)
18. [Known Issues & Workarounds](#18-known-issues--workarounds)
19. [Logging](#19-logging)

---

## 1. Overview

The **AlbertaPOS Automation Framework** is a Windows desktop UI automation solution for the **AlbertaPOS** point-of-sale application — a WinForms `.NET` executable running on Windows 11.

### What It Automates

| Capability | Test Class | Description |
|---|---|---|
| App launch + login | `AlbertaPOSLaunchTest` | Start AlbertaPOS, authenticate, verify main window |
| Home page validation | `HomePageTest`, `HomePageValidationTest` | Verify all UI controls are present and functional |
| Cash transaction | `TransactionTest` | Full flow: barcode → grid → PAY → Cash → receipt save |
| Database validation | `DatabaseUtil` (utility) | Query MySQL to validate transaction records |

### Target Environment

- **OS:** Windows 11 Pro
- **Runtime:** Java 21 JVM running as **Administrator** (required)
- **App:** AlbertaPOS.exe (WinForms, .NET, elevated integrity)
- **Automation server:** WinAppDriver 1.x on `http://127.0.0.1:4723`
- **Test runner:** Maven + TestNG

---

## 2. Technology Stack

| Dependency | Version | Purpose |
|---|---|---|
| **Appium Java Client** | 8.6.0 | Last release with JSONWP support — mandatory for WinAppDriver 1.x |
| **Selenium WebDriver** | 4.21.0 | WebDriver API, HTTP command codec, `RemoteWebDriver` |
| **TestNG** | 7.10.2 | Test runner, suites, listeners, `@DataProvider`, retry |
| **WinAppDriver** | 1.x | Microsoft UIAutomation bridge — must run on the same machine |
| **ExtentReports** | 5.1.2 | Spark HTML execution reports with screenshots |
| **Apache POI OOXML** | 5.2.5 | Excel `.xlsx` read (test data) and write (results) |
| **MySQL Connector/J** | 8.3.0 | JDBC-based database result validation |
| **Gson** | 2.11.0 | JSON serialization inside `WinAppDriverCommandExecutor` |
| **SLF4J** | 2.0.13 | Logging facade |
| **Logback Classic** | 1.5.6 | Logging backend — console + rolling file |
| **Java** | 21 | Runtime and compile target |
| **Maven** | 3.x | Build lifecycle and test execution (`maven-surefire-plugin 3.2.5`) |

---

## 3. Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                              TEST EXECUTION LAYER                               │
│    AlbertaPOSLaunchTest   HomePageTest   HomePageValidationTest   TransactionTest│
│              │                   │                  │                    │       │
│              └───────────────────┴──────────────────┴────────────────────┘       │
│                                          │ extends                               │
├──────────────────────────────────────────┼──────────────────────────────────────┤
│                            BASE FRAMEWORK LAYER                                 │
│                                  BaseTest                                       │
│          @BeforeSuite / @AfterSuite  ·  @BeforeMethod / @AfterMethod            │
│                │                │                │               │              │
│     WinAppDriverManager   AlbertaPOSPage    LoginPage    ExtentReportListener   │
├──────────────────────────────────────────┼──────────────────────────────────────┤
│                          SESSION / DRIVER LAYER                                 │
│               WinAppDriverSessionFactory  (3 session types)                     │
│          ┌──────────────┬────────────────────┬─────────────────┐               │
│    App Session      Desktop Session      Window Session                         │
│   (launch app)   (UAC/system dialogs)  (attach by HWND)                        │
│                  WinAppDriverCommandExecutor                                     │
│           (raw JSONWP POST for NEW_SESSION · W3C codecs for rest)               │
├─────────────────────────────────────────────────────────────────────────────────┤
│                           PAGE OBJECT LAYER                                     │
│     BasePage ──► AlbertaPOSPage  ·  LoginPage  ·  HomePage  ·  TransactionPage │
│                     (all interactions delegated to CustomAction)                │
├─────────────────────────────────────────────────────────────────────────────────┤
│                         UTILITY / SUPPORT LAYER                                 │
│   ConfigReader   CustomAction   ExcelUtil   DatabaseUtil   ScreenshotUtil       │
│   ProcessUtil    ExtentReportManager        AdminPrivilegeValidator              │
├─────────────────────────────────────────────────────────────────────────────────┤
│                        EXTERNAL SYSTEMS / RUNTIME                               │
│   WinAppDriver 1.x ──► Windows UIAutomation API ──► AlbertaPOS.exe             │
│   MySQL Server           POSTestData.xlsx             Windows OS (UAC / HWND)   │
└─────────────────────────────────────────────────────────────────────────────────┘
```

**Key design principles:**
- Single shared `RemoteWebDriver` instance across an entire suite (one AlbertaPOS launch per `@BeforeSuite`)
- Page objects hold no state beyond the driver reference — all logic is in methods
- No JavaScript execution anywhere (WinAppDriver does not support it)
- All waits use `FluentWait`; no raw `Thread.sleep` calls in page/utility code
- Config is read-only at runtime — all values sourced from `config.properties` via `ConfigReader`

---

## 4. Codebase Architecture — Deep Dive

### 4.1 Package Hierarchy and Responsibilities

```
com.pos.automation
│
├── annotations/
│   └── TestDataSheet.java          @interface — binds a test class to its Excel sheet name
│
├── base/
│   ├── BaseTest.java               Suite orchestrator — owns the shared RemoteWebDriver;
│   │                               handles WinAppDriver start, app launch, window attach,
│   │                               and suite/method lifecycle hooks
│   ├── WinAppDriverCommandExecutor.java   JSONWP↔W3C protocol bridge (DO NOT REPLACE)
│   └── WinAppDriverSessionFactory.java    Capability builder for 3 session types
│
├── listeners/
│   ├── ExtentReportListener.java   ISuiteListener + ITestListener → HTML report lifecycle
│   └── RetryAnalyzer.java          IRetryAnalyzer → configurable test retry on failure
│
├── pages/
│   ├── BasePage.java               Abstract; holds RemoteWebDriver + null-safe element helpers
│   ├── AlbertaPOSPage.java         App launch, UAC handling, session popup, window attachment
│   ├── LoginPage.java              FrmLog numpad login — 5 input strategies + Win32 PostMessage
│   ├── HomePage.java               Home screen control discovery + error dialog detection
│   └── TransactionPage.java        Cash transaction end-to-end (barcode → receipt)
│
└── utils/
    ├── ConfigReader.java           Singleton; typed accessors for all config.properties keys
    ├── PropertyReader.java         Legacy flat-file reader (backward-compat; do not remove)
    ├── CustomAction.java           FluentWait-wrapped UI action library (click, type, wait…)
    ├── WinAppDriverManager.java    WinAppDriver process lifecycle + stale-session cleanup
    ├── AdminPrivilegeValidator.java  JVM elevation check via PowerShell
    ├── ProcessUtil.java            Base64-encoded PowerShell runner (bypasses quoting issues)
    ├── ExcelUtil.java              POI-based test data read + result write-back to .xlsx
    ├── DatabaseUtil.java           Lazy MySQL JDBC connection + query/validation helpers
    ├── ScreenshotUtil.java         java.awt.Robot full-desktop capture → target/screenshots/
    └── ExtentReportManager.java    Singleton ExtentReports + ThreadLocal<ExtentTest> node mgmt
```

---

### 4.2 Class Inheritance and Composition Tree

```
Object
│
├── BaseTest  (owns shared RemoteWebDriver driver)
│   ├── AlbertaPOSLaunchTest
│   ├── HomePageTest
│   ├── HomePageValidationTest
│   └── TransactionTest
│
├── BasePage  (abstract; holds RemoteWebDriver)
│   ├── AlbertaPOSPage    composes → WinAppDriverSessionFactory, ProcessUtil
│   ├── LoginPage         composes → CustomAction, ProcessUtil
│   ├── HomePage          composes → CustomAction
│   └── TransactionPage   composes → CustomAction
│
├── HttpCommandExecutor   [Selenium internal]
│   └── WinAppDriverCommandExecutor  (overrides execute() to intercept NEW_SESSION)
│
└── WinAppDriverSessionFactory  (static factory; no inheritance)

Listeners (wired via TestNG suite XML):
  ExtentReportListener  implements ISuiteListener, ITestListener
  RetryAnalyzer         implements IRetryAnalyzer
```

---

### 4.3 Key Dependency Graph

```
TransactionTest
  │
  ├── BaseTest (inherited)
  │     ├── AdminPrivilegeValidator.validate()
  │     ├── WinAppDriverManager.start()  →  ProcessUtil (PowerShell)
  │     ├── WinAppDriverSessionFactory.createForApp()
  │     │     └── WinAppDriverCommandExecutor (JSONWP POST)
  │     ├── AlbertaPOSPage.handleUACAndAttachMainWindow()
  │     │     ├── WinAppDriverSessionFactory.createForDesktop()
  │     │     └── WinAppDriverSessionFactory.createForWindow(hwnd)
  │     └── ExtentReportManager  (via ExtentReportListener)
  │
  ├── LoginPage.performLogin()
  │     ├── CustomAction  (FluentWait interactions)
  │     └── ProcessUtil   (Win32 PostMessage fallback)
  │
  ├── TransactionPage
  │     ├── CustomAction
  │     └── ScreenshotUtil  (on failure — called by listener)
  │
  ├── ExcelUtil   (DataProvider reads + result write-back)
  ├── ConfigReader  (all configuration values)
  └── DatabaseUtil  (optional post-transaction DB validation)
```

---

### 4.4 WinAppDriver Session Lifecycle

```
@BeforeSuite — setUp()
  │
  ├─ [1] AdminPrivilegeValidator.validate()
  │         PowerShell: [WindowsPrincipal]::IsInRole("Administrator")
  │         → log banner; optionally fail suite if not elevated
  │
  ├─ [2] WinAppDriverManager.start()
  │         GET http://127.0.0.1:4723/status
  │         → already healthy? skip start
  │         → ProcessBuilder(WinAppDriver.exe) — succeeds if JVM is elevated
  │         → 5-minute manual-wait fallback if auto-start fails
  │         → cleanupStaleSessions(): GET /sessions → DELETE each open session
  │
  ├─ [3] WinAppDriverSessionFactory.createForApp(appPath)
  │         DesiredCapabilities { app=appPath, deviceName="WindowsPC",
  │                               ms:waitForAppLaunch=90, ... }
  │         → WinAppDriverCommandExecutor → raw JSONWP POST /session
  │         → appSession : RemoteWebDriver
  │
  ├─ [4] AlbertaPOSPage.handleUAC(desktopSession)
  │         WinAppDriverSessionFactory.createForDesktop()
  │           → desktopSession : RemoteWebDriver (Root window = entire desktop)
  │         poll for UAC / security popup → click Yes / Allow
  │
  ├─ [5] AlbertaPOSPage.waitForMainWindow()
  │         Fast path: poll appSession.getWindowHandles() — title matches fragment
  │         Fallback:  ProcessUtil → PowerShell Get-Process | Where MainWindowHandle
  │                    → extract HWND (decimal)
  │
  └─ [6] WinAppDriverSessionFactory.createForWindow(hwnd)
            DesiredCapabilities { appTopLevelWindow=hwnd }
            → mainSession : RemoteWebDriver (attached to POS main window)
            → stored in BaseTest.driver — shared by all page objects + tests

@BeforeMethod
  ExtentReportListener.onTestStart() → create ExtentTest node

[Tests run using shared mainSession driver]

@AfterMethod
  On failure: ScreenshotUtil.capture() → attach screenshot to ExtentTest node
  ExtentReportListener.onTestSuccess/Failure/Skipped() → finalize node

@AfterSuite — tearDown()
  mainSession.quit()
  appSession.quit()
  desktopSession.quit()
  WinAppDriverManager.stop()     (only if stopWinAppDriverAfterSuite=true)
  DatabaseUtil.closeConnection() (only if DB tests ran)
  ExtentReportManager.flush()    (via ExtentReportListener.onFinish)
```

---

### 4.5 Login Flow — Strategy Decision Tree

```
LoginPage.performLogin(username, password)
  │
  ├─ checkAndDismissSessionPopup()
  │    findElement(Button[@Name='No'] or Button[@Name='Cancel'])
  │    → click if session-restore dialog is visible
  │
  ├─ [Strategy 1]  UIAutomation Edit controls
  │    findElement(By.xpath("//Edit[@AutomationId='txtUsername']"))
  │    found + enabled? → sendKeys(username) + sendKeys(password) → verify text → SUCCESS ✓
  │    not found / verification fails ↓
  │
  ├─ [Strategy 2]  W3C Actions API — absolute screen coordinates
  │    new Actions(driver).moveToLocation(x, y).click().sendKeys(username)...
  │    verify field text → SUCCESS ✓
  │    fails ↓
  │
  ├─ [Strategy 3]  UIAutomation Pane container + numpad Button clicks
  │    findElement(Pane).click()  →  click digit Buttons by Name
  │    verify → SUCCESS ✓
  │    fails ↓
  │
  ├─ [Strategy 4]  WinAppDriver numpad Button click by Name attribute
  │    findElement(By.name("1")).click() per digit
  │    verify → SUCCESS ✓
  │    fails ↓
  │
  ├─ [Strategy 5]  WinAppDriver Edit field sendKeys
  │    findElement(By.className("Edit")).sendKeys(username)
  │    verify → SUCCESS ✓
  │    fails ↓
  │
  └─ [PostMessage]  Win32 WM_CHAR via PowerShell
       GetForegroundWindow → FindWindowEx(hWnd, ClassName)
       → PostMessage(hCtrl, WM_CHAR, charCode, 0) per character
       verify → SUCCESS ✓  or  throw LoginException("All strategies exhausted")
```

> **Why so many strategies?** AlbertaPOS's FrmLog login screen uses a WinForms custom numpad. When the test JVM runs at medium integrity and AlbertaPOS runs at high integrity, Windows UIPI blocks most input injection methods. The strategy chain ensures login succeeds regardless of the exact integrity relationship between the processes.

---

### 4.6 Transaction Flow — Sequence

```
TransactionTest.testTransactionFlow(Object[] rowData)
  │
  Step 1   Login               LoginPage.performLogin(username, password)
  Step 2   Wait Main Window    AlbertaPOSPage.waitForMainWindow()
  Step 3   Enter Barcode       TransactionPage.enterBarcode(barcode)
             Primary:  AutomationId="txtBarcode"
             Fallback 1: XPath //Edit[contains(@Name,'arcode')]
             Fallback 2: XPath //Edit[1] in transaction panel
  Step 4   Verify Grid Row     TransactionPage.verifyItemInGrid()
             XPath //DataItem[@Name~=barcode] or //ListItem containing barcode text
  Step 5   Click PAY           CustomAction.click(By.name("PAY"))
  Step 6   Click Cash          CustomAction.click(By.name("Cash"))
  Step 7   Cash Discount Popup TransactionPage.handleCashDiscountPopup()
             findElement(Button[@Name='Yes'] or Button[@Name='No']) → click
  Step 8   Receipt Save Dialog TransactionPage.handleReceiptSaveDialog()
             Enumerate driver.getWindowHandles() → switch to dialog handle
             Path A (OS SaveFileDialog): //Edit[@Name='File name:'] + Save button
             Path B (WinForms dialog):   findElement(Edit) + findElement(Button)
             Type file name → click Save / OK
  Step 9   Verify File Saved   scan receiptsFolder for prefix + ~timestamp match
  Step 10  Write Excel Result  ExcelUtil.updateRowResult(sheet, row, "PASS", durationMs)
  Step 11  Log to Extent       extentTest.pass(details) or extentTest.fail(details+screenshot)
```

---

### 4.7 Data-Driven Execution Flow

```
POSTestData.xlsx — Sheet: "Transaction"
┌──────────┬─────────┬──────────────┬──────────┬──────────┐
│TestCaseID│Execute  │Barcode       │Username  │Password  │ ...
├──────────┼─────────┼──────────────┼──────────┼──────────┤
│TC_TXN_01 │YES      │998877665501  │111       │1111      │ ← included
│TC_TXN_02 │NO       │111222333444  │111       │1111      │ ← skipped
└──────────┴─────────┴──────────────┴──────────┴──────────┘

ExcelUtil.getDataAsObjectArray("Transaction")
  → skip rows where Execute column = "NO" (case-insensitive)
  → return Object[][] — one Object[] per included row

@DataProvider(name = "testData")
public Object[][] dataProvider() {
    TestDataSheet sheet = getClass().getAnnotation(TestDataSheet.class);
    return new ExcelUtil().getDataAsObjectArray(sheet.sheetName());
}

@Test(dataProvider = "testData", retryAnalyzer = RetryAnalyzer.class)
public void testTransactionFlow(Object[] rowData) { ... }

After test completes:
  ExcelUtil.updateRowResult("Transaction", rowIndex, "PASS", 4523L)
  → writes back: Status=PASS, Duration(ms)=4523, SystemName=DESKTOP-XYZ,
                 Timestamp=2026-05-15T10:23:01
```

---

### 4.8 Cross-Cutting Concerns

| Concern | Implementation | Where Wired |
|---|---|---|
| Configuration | `ConfigReader` singleton (classpath load once) | Every component |
| Logging | SLF4J + Logback → `logs/automation.log` | Every class via `LoggerFactory.getLogger(getClass())` |
| HTML Reporting | `ExtentReportManager` + `ExtentReportListener` | `<listeners>` tag in every suite XML |
| Screenshots | `ScreenshotUtil` (Robot — full desktop) | `ExtentReportListener.onTestFailure` |
| Retry | `RetryAnalyzer` (up to N times) | `@Test(retryAnalyzer = RetryAnalyzer.class)` |
| Test data | `ExcelUtil` + `@TestDataSheet` annotation | `@DataProvider` in each test class |
| Admin check | `AdminPrivilegeValidator` | `BaseTest.@BeforeSuite` |
| Stale sessions | `WinAppDriverManager.cleanupStaleSessions()` | `BaseTest.@BeforeSuite` before session creation |

---

## 5. Project Structure

```
d:\AlbertaPOSAutomationWithClaude\
│
├── pom.xml                                  Maven build — Java 21, all dependencies
├── CLAUDE.md                                Framework guide for Claude Code AI assistant
│
├── src/
│   ├── main/java/com/pos/automation/
│   │   ├── annotations/
│   │   │   └── TestDataSheet.java           @TestDataSheet("SheetName") class annotation
│   │   │
│   │   ├── base/
│   │   │   ├── BaseTest.java                Suite lifecycle, shared driver, app launch
│   │   │   ├── WinAppDriverCommandExecutor.java   JSONWP/W3C protocol bridge
│   │   │   └── WinAppDriverSessionFactory.java    3-session-type capability factory
│   │   │
│   │   ├── listeners/
│   │   │   ├── ExtentReportListener.java    TestNG → Extent Reports 5 bridge
│   │   │   └── RetryAnalyzer.java           Configurable test retry (default: 2)
│   │   │
│   │   ├── pages/
│   │   │   ├── BasePage.java                Abstract: driver holder + null-safe helpers
│   │   │   ├── AlbertaPOSPage.java          Launch, UAC, session popup, window attach
│   │   │   ├── LoginPage.java               FrmLog login — 5 strategies + PostMessage
│   │   │   ├── HomePage.java                Home screen element validation
│   │   │   └── TransactionPage.java         Cash transaction end-to-end
│   │   │
│   │   └── utils/
│   │       ├── ConfigReader.java            Typed accessors for config.properties
│   │       ├── PropertyReader.java          Legacy flat reader (backward-compat)
│   │       ├── CustomAction.java            FluentWait UI action library
│   │       ├── WinAppDriverManager.java     WAD lifecycle + stale session cleanup
│   │       ├── AdminPrivilegeValidator.java JVM elevation check via PowerShell
│   │       ├── ProcessUtil.java             Base64-encoded PowerShell runner
│   │       ├── ExcelUtil.java               POI read (data) + write (results)
│   │       ├── DatabaseUtil.java            MySQL JDBC — lazy connection + helpers
│   │       ├── ScreenshotUtil.java          Robot full-desktop capture
│   │       └── ExtentReportManager.java     Singleton + ThreadLocal ExtentTest
│   │
│   └── test/
│       ├── java/com/pos/automation/
│       │   ├── tests/
│       │   │   ├── AlbertaPOSLaunchTest.java     Smoke: launch + login + title verify
│       │   │   ├── HomePageTest.java              Home page element presence checks
│       │   │   ├── HomePageValidationTest.java    Comprehensive HP control-type scan
│       │   │   └── TransactionTest.java           11-step cash transaction flow
│       │   └── util/
│       │       └── CreateSampleExcel.java         One-time POSTestData.xlsx generator
│       │
│       └── resources/
│           ├── config.properties                  All framework configuration
│           ├── logback-test.xml                   SLF4J/Logback — console + rolling file
│           ├── testng.xml                         Smoke suite (AlbertaPOSLaunchTest)
│           ├── testng-homepage.xml                HomePageValidationTest suite
│           ├── testng-hometest.xml                HomePageTest suite
│           ├── testng-transaction.xml             TransactionTest suite
│           ├── testng-full-suite.xml              All 4 tests in one AlbertaPOS launch
│           └── testdata/
│               └── POSTestData.xlsx               Excel: Launch, HomePage, Transaction sheets
│
├── logs/
│   └── automation.log                     SLF4J rolling log (7-day retention)
│
└── target/
    ├── reports/ExtentReport_{timestamp}/  Extent HTML reports
    │   └── index.html
    ├── screenshots/                       Robot-captured PNG files
    └── surefire-reports/                  TestNG XML results
```

---

## 6. Critical Component: W3C/JSONWP Bridge

### The Problem

**Selenium 4** enforces W3C WebDriver protocol. It validates `DesiredCapabilities` before sending `NEW_SESSION` and strips any non-W3C keys (`app`, `deviceName`, `appTopLevelWindow`, etc.).

**WinAppDriver 1.x** only understands the **JSONWP** protocol — its `NEW_SESSION` endpoint expects `{"desiredCapabilities": {...}}`, not W3C `{"capabilities": {...}}`.

Without a bridge: session creation fails with `invalid argument` or `unknown command`.

### The Solution: `WinAppDriverCommandExecutor`

`WinAppDriverCommandExecutor` extends Selenium's `HttpCommandExecutor` and overrides the `execute()` method to intercept `NEW_SESSION`:

```
execute(Command command)
  │
  ├─ command.getName() == "newSession"?
  │     YES → serialize capabilities as raw JSONWP:
  │            POST {winAppDriverUrl}/session
  │            Body: {"desiredCapabilities": {app: ..., deviceName: ..., ...}}
  │            Parse response sessionId manually
  │            Wire W3CHttpCommandCodec + W3CHttpResponseCodec for all future commands
  │            Convert response element refs: {ELEMENT:"id"} → {element-6066-11e4-...:"id"}
  │            Return success response
  │     NO  → delegate to super.execute() (standard Selenium W3C path)
```

### `WinAppDriverSessionFactory`

Centralizes `DesiredCapabilities` setup for the three session types used in the framework:

| Method | Purpose | Key Capabilities |
|---|---|---|
| `createForApp(appPath)` | Launch AlbertaPOS | `app=appPath`, `deviceName="WindowsPC"`, `ms:waitForAppLaunch=90` |
| `createForDesktop()` | Watch for UAC/system dialogs | `app="Root"` (entire Windows desktop) |
| `createForWindow(hwnd)` | Attach to running main window | `appTopLevelWindow="{hwnd}"` |

> **Critical:** Never replace `WinAppDriverCommandExecutor` with a standard `AppiumDriver`, `AndroidDriver`, or `RemoteWebDriver`. The W3C capability stripping will cause `NEW_SESSION` to fail.

---

## 7. Base Test Framework

`BaseTest` is the suite-scoped superclass that owns the single shared `RemoteWebDriver` instance. All four test classes extend it.

### Lifecycle Hooks

| Hook | Scope | Responsibilities |
|---|---|---|
| `@BeforeSuite setUp()` | Once per suite | Admin check → WAD start → app launch → UAC handling → window attach |
| `@AfterSuite tearDown()` | Once per suite | Close all 3 sessions → optional WAD stop → optional process kill → DB close |
| `@BeforeMethod logTestStart()` | Per test method | Log test start to SLF4J |
| `@AfterMethod logTestResult()` | Per test method | Log pass/fail; on fail: capture screenshot |

### App Launch Strategies

```java
// Primary: requires elevated JVM (runs as Administrator)
Process process = new ProcessBuilder(appPath).start();

// Fallback: Shell.Application COM runas — launches elevated regardless of JVM integrity
ProcessUtil.runPowerShell(
    "(New-Object -ComObject Shell.Application).ShellExecute('" + appPath + "', '', '', 'runas', 1)"
);
```

### Main Window Detection

```
1. Fast path: poll appSession.getWindowHandles() every 2s (up to appLaunchWaitSeconds)
   → switch to handle whose title contains appWindowTitleFragment
   → extract HWND from window handle string

2. Fallback: PowerShell HWND poll
   Get-Process | Where-Object { $_.MainWindowTitle -like '*AlbertaPOS*' }
   | Select-Object -ExpandProperty MainWindowHandle
   → decimal HWND string

3. Attach: WinAppDriverSessionFactory.createForWindow(hwnd)
   → new session with appTopLevelWindow capability
   → becomes the shared this.driver
```

### Shared Constants (used across all test classes)

```java
protected static final int    DEFAULT_TIMEOUT_SECONDS    = 30;
protected static final int    SHORT_TIMEOUT_SECONDS      = 10;
protected static final int    LONG_TIMEOUT_SECONDS       = 60;
protected static final int    APP_LAUNCH_WAIT_SECONDS    = 90;
protected static final String WINDOW_TITLE_FRAGMENT      = "AlbertaPOS";
```

---

## 8. WinAppDriver Manager

`WinAppDriverManager` handles the full lifecycle of the WinAppDriver process and open sessions.

### `start()` — Decision Flow

```
GET http://127.0.0.1:4723/status
  → HTTP 200 received?  →  already healthy — skip start
  → exception / non-200?
        ProcessBuilder(winAppDriverExePath).start()
        → wait up to 10s for /status to return 200 (elevated JVM)
        → if still not healthy: manual-wait loop (checks every 5s, 5-min timeout)
              [prompt user: "Please start WinAppDriver manually at http://127.0.0.1:4723"]
```

### `cleanupStaleSessions()`

```
GET http://127.0.0.1:4723/sessions
  → parse JSON: sessions array
  → for each session: DELETE http://127.0.0.1:4723/session/{id}
  → log count of sessions cleaned up
```

### `stop()`

Called in `@AfterSuite` only when `config.properties` has `stopWinAppDriverAfterSuite=true` (default `false` — keeps WAD alive for the next run).

---

## 9. Page Objects

### 9.1 BasePage

Abstract base for all page objects. Holds the shared `RemoteWebDriver` and provides null-safe element helpers.

```java
public abstract class BasePage {
    protected final RemoteWebDriver driver;

    protected BasePage(RemoteWebDriver driver) { this.driver = driver; }

    // Returns first match or null — never throws NoSuchElementException
    protected WebElement findElement(By locator) { ... }

    // Returns all matches or empty list — never throws
    protected List<WebElement> findElements(By locator) { ... }

    // True if at least one match exists
    protected boolean isElementPresent(By locator) { ... }

    // Current window title
    protected String getWindowTitle() { ... }
}
```

### 9.2 AlbertaPOSPage

Handles everything between process start and the main POS window being usable.

| Method | Description |
|---|---|
| `handleUACPopup(desktopSession)` | Scans desktop session for UAC or security dialogs; clicks Yes/Allow by AutomationId or Name |
| `waitForLoginScreen()` | Polls for FrmLog window (ClassName="WindowsForms10.Window…") to appear |
| `handleSessionRestorePopup()` | Detects "There is already an active session" dialog → clicks No or Cancel |
| `waitForMainWindow()` | Two-path HWND detection (see §7); returns HWND decimal string |
| `attachToMainWindow(hwnd)` | Creates new `createForWindow(hwnd)` session → returns `RemoteWebDriver` |

**UAC locators:**
```xpath
//Button[@AutomationId='6']         — Windows 10/11 UAC "Yes"
//Button[@AutomationId='CommandButton_2']  — Legacy UAC
//Button[@Name='Yes']               — Generic fallback
//Button[@Name='Allow']             — Some security dialogs
```

### 9.3 LoginPage — 5-Strategy Login

The most complex page object due to **UIPI** (User Interface Privilege Isolation): AlbertaPOS runs at higher process integrity than the test JVM, blocking most standard input injection.

#### Strategy Summary

| # | Name | Primary Locator / Mechanism | Success Condition |
|---|---|---|---|
| 1 | UIAutomation Edit | `AutomationId="txtUsername"`, `"txtPassword"` | Field text equals entered value |
| 2 | W3C Actions (coords) | `Actions.moveToLocation(x, y).click().sendKeys(...)` | Field text equals entered value |
| 3 | Pane + digit buttons | Click `//Pane` container, then `//Button[@Name='N']` per digit | Field text equals entered value |
| 4 | WinAppDriver button click | `findElement(By.name("1")).click()` per digit | Field text equals entered value |
| 5 | Edit sendKeys | `findElement(By.className("Edit")).sendKeys(value)` | Field text equals entered value |
| PostMessage | Win32 WM_CHAR | PowerShell `PostMessage(hCtrl, WM_CHAR, charCode, 0)` | Field text equals entered value |

#### `performLogin(username, password)` — Orchestration

```java
checkAndDismissSessionPopup();   // always run first

for (Strategy s : strategies) {
    try {
        s.enterCredentials(username, password);
        if (verifyFieldValues(username, password)) {
            clickLoginButton();
            return;    // success
        }
    } catch (Exception e) {
        log.warn("Strategy {} failed: {}", s.name(), e.getMessage());
    }
}
tryPostMessageFallback(username, password);
```

### 9.4 HomePage

Validates the main POS window after login.

| Method | Description |
|---|---|
| `discoverElements()` | Counts visible Buttons, MenuItems, TextBlocks, Edit, ComboBox controls |
| `isErrorDialogPresent()` | Exact-match on known error titles + keyword heuristic fallback |
| `getWindowTitle()` | Returns current window title (inherited from `BasePage`) |
| `validateTitleContains(fragment)` | Asserts title matches `homePageTitleFragment` from config |

**Error dialog detection — exact-match list:**
```
"Error", "Application Error", "Unhandled Exception",
"Runtime Error", "Fatal Error", "Critical Error"
```
**Keyword fallback:** title contains `error`, `exception`, `fatal`, `critical`, `crash` (case-insensitive).

### 9.5 TransactionPage

Implements the full cash transaction flow.

#### Barcode Entry — Fallback Chain

```java
// Primary
By.xpath("//Edit[@AutomationId='txtBarcode']")

// Fallback 1
By.xpath("//Edit[contains(translate(@Name,'BARCODE','barcode'),'barcode')]")

// Fallback 2
By.xpath("(//Edit)[1]")   // first Edit field in the transaction panel
```

#### Receipt Save Dialog — Two-Path Strategy

```java
// Enumerate all window handles after clicking Save
for (String handle : driver.getWindowHandles()) {
    driver.switchTo().window(handle);

    // Path A: Windows OS SaveFileDialog
    WebElement fileNameField = findElement(By.xpath("//Edit[@Name='File name:']"));
    if (fileNameField != null) {
        fileNameField.clear();
        fileNameField.sendKeys(fileName);
        findElement(By.xpath("//Button[@Name='Save']")).click();
        return;
    }

    // Path B: WinForms custom save dialog
    WebElement edit = findElement(By.className("Edit"));
    WebElement saveBtn = findElement(By.xpath("//Button[1]"));
    if (edit != null && saveBtn != null) {
        edit.clear();
        edit.sendKeys(fileName);
        saveBtn.click();
        return;
    }
}
```

---

## 10. Utility Layer

### 10.1 ConfigReader

Singleton that loads `config.properties` once from the classpath. Provides typed accessors and named constants to prevent key-name typos.

```java
ConfigReader.APP_PATH                 // "appPath"
ConfigReader.WINAPPDRIVER_URL         // "winAppDriverUrl"
ConfigReader.POS_USERNAME             // "posUsername"
ConfigReader.IMPLICIT_WAIT            // "implicitWait"
// ... (all keys have a constant)

String val  = ConfigReader.getInstance().getString(ConfigReader.APP_PATH);
int    secs = ConfigReader.getInstance().getInt(ConfigReader.IMPLICIT_WAIT, 15);
boolean b   = ConfigReader.getInstance().getBoolean("stopWinAppDriverAfterSuite", false);
```

### 10.2 CustomAction

Instance class (injected with driver) wrapping all UI interactions in `FluentWait`. No `Thread.sleep` calls. WinAppDriver-specific implementations where standard Selenium approaches fail.

| Method | Description |
|---|---|
| `click(By)` | FluentWait until clickable → click |
| `enterText(By, String)` | Wait → clear → sendKeys |
| `clearAndEnterText(By, String)` | Triple-click to select all → sendKeys |
| `waitForElement(By, int)` | FluentWait presence wait |
| `waitForElementToDisappear(By, int)` | FluentWait staleness/absence wait |
| `isElementDisplayed(By)` | True if visible — never throws |
| `isElementEnabled(By)` | True if enabled — never throws |
| `selectDropdown(By, String)` | Click ComboBox → click `//ListItem[@Name='value']` |
| `acceptAlert()` | Find `//Button[@Name='OK']` or `//Button[@Name='Yes']` → click |
| `dismissAlert()` | Find `//Button[@Name='Cancel']` or `//Button[@Name='No']` → click |
| `highlightElement(WebElement)` | Log `Name` + `AutomationId` attributes (debug aid) |
| `scrollToElement(By)` | WinAppDriver scroll gesture |
| `switchToWindow(String)` | `driver.switchTo().window(handle)` |

> **No JavaScript:** WinAppDriver does not support `driver.executeScript(...)`. Any JS-based interaction pattern from web test automation will fail at runtime.

### 10.3 ExcelUtil

Apache POI XSSF-based utility for data-driven testing.

#### Reading Test Data

```java
ExcelUtil util = new ExcelUtil();  // loads excelFilePath from ConfigReader
Object[][] data = util.getDataAsObjectArray("Transaction");
// - Skips header row
// - Skips rows where "Execute" column value = "NO" (case-insensitive)
// - Returns remaining rows as Object[][]
```

#### Writing Results Back

```java
ExcelUtil.updateRowResult(
    "Transaction",   // sheet name
    2,               // 0-based row index (header = 0)
    "PASS",          // status
    4523L            // duration in milliseconds
);
// Writes to columns: Status | Duration(ms) | SystemName | Timestamp
```

#### Excel Sheet Structure

| Column | Type | Description |
|---|---|---|
| TestCaseID | String | Unique test identifier |
| Execute | String | YES / NO — controls inclusion |
| [Test inputs…] | Various | Barcode, Username, Password, etc. |
| Status | String | Written back: PASS / FAIL / SKIP |
| Duration(ms) | Long | Written back: execution duration |
| SystemName | String | Written back: `InetAddress.getLocalHost().getHostName()` |
| Timestamp | String | Written back: ISO-8601 datetime |

### 10.4 DatabaseUtil

MySQL JDBC utility for post-test database validation. Uses a lazy-initialized singleton connection.

```java
// Single value
String total = DatabaseUtil.fetchSingleValue(
    "SELECT total FROM transactions WHERE id = ?", List.of("TXN123")
);

// Multiple rows
List<Map<String,Object>> rows = DatabaseUtil.fetchAllRecords(
    "SELECT * FROM transactions WHERE status = ?", List.of("COMPLETED")
);

// Assertion helper — returns true if field matches expected value
boolean ok = DatabaseUtil.validateRecord(
    "SELECT * FROM transactions WHERE barcode = ?",
    List.of("998877665501"), "status", "COMPLETED"
);

// DML
int affected = DatabaseUtil.executeUpdate(
    "UPDATE transactions SET status = ? WHERE id = ?",
    List.of("REVIEWED", "TXN123")
);

// Must be called in @AfterSuite when DB tests are used
DatabaseUtil.closeConnection();
```

### 10.5 ScreenshotUtil

Full-desktop screenshot using `java.awt.Robot` — captures what is actually visible on screen regardless of window focus or z-order.

```java
// Called automatically by ExtentReportListener on test failure
String path = ScreenshotUtil.capture(testName);
// Saves to: target/screenshots/{testName}_{yyyyMMdd_HHmmss}.png
// Returns absolute file path for attachment to Extent report
```

> **Why Robot instead of WebDriver screenshot?** `driver.getScreenshotAs()` captures only the WinAppDriver session's target window. If a popup, UAC dialog, or overlay covers the window, `Robot` captures it faithfully because it reads raw screen pixels.

### 10.6 ExtentReportManager

Singleton managing the Extent Reports 5 instance and per-thread test nodes.

```java
// Suite start
ExtentReportManager.init();
// → creates target/reports/ExtentReport_{yyyyMMdd_HHmmss}/index.html

// Per test
ExtentTest test = ExtentReportManager.createTest(testName, description);
ExtentReportManager.getTest().pass("Step 3: barcode entered");
ExtentReportManager.getTest().fail("Step 4: grid row not found",
    MediaEntityBuilder.createScreenCaptureFromPath(screenshotPath).build());

// Suite end
ExtentReportManager.flush();
```

`ThreadLocal<ExtentTest>` ensures thread-safe test node access when tests run in parallel.

### 10.7 ProcessUtil

Executes PowerShell commands by Base64-encoding them with UTF-16 LE encoding and passing via `-EncodedCommand`. This avoids all command-line quoting issues when scripts contain quotes, brackets, or special characters.

```java
String hwnd = ProcessUtil.runPowerShell(
    "Get-Process AlbertaPOS | Select -ExpandProperty MainWindowHandle"
);
```

**Encoding:**
```java
byte[] encoded = script.getBytes(StandardCharsets.UTF_16LE);
String b64 = Base64.getEncoder().encodeToString(encoded);
// Executes: powershell.exe -EncodedCommand {b64}
```

### 10.8 AdminPrivilegeValidator

Validates that the test JVM is running as Administrator — a hard requirement for WinAppDriver auto-start and UIPI bypass.

```java
// In BaseTest.@BeforeSuite
AdminPrivilegeValidator.validate();
// Runs PowerShell:
//   ([Security.Principal.WindowsPrincipal]
//    [Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole(
//    [Security.Principal.WindowsBuiltInRole]::Administrator)
// → "True"  → OK, log green banner
// → "False" → log red warning banner; throw if failIfNotAdmin=true
```

### 10.9 PropertyReader (Legacy)

Independent flat file reader for `config.properties`. Maintained for backward compatibility with any older test code that does not use `ConfigReader`. Both classes load from the same classpath resource.

```java
String appPath = PropertyReader.getProperty("appPath");
```

---

## 11. Test Infrastructure

### 11.1 `@TestDataSheet` Annotation

A compile-retained, class-level annotation binding each test class to its Excel sheet name:

```java
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface TestDataSheet {
    String sheetName();
}

// Usage
@TestDataSheet("Transaction")
public class TransactionTest extends BaseTest { ... }
```

The `@DataProvider` in each test class reads this annotation:

```java
@DataProvider(name = "testData")
public Object[][] dataProvider() {
    TestDataSheet sheet = getClass().getAnnotation(TestDataSheet.class);
    return new ExcelUtil().getDataAsObjectArray(sheet.sheetName());
}
```

### 11.2 ExtentReportListener

Wired as a TestNG listener in all suite XML files:

```xml
<listeners>
    <listener class-name="com.pos.automation.listeners.ExtentReportListener"/>
</listeners>
```

**Event mapping:**

| TestNG Event | Extent Action |
|---|---|
| `onStart(ISuite)` | `ExtentReportManager.init()` |
| `onFinish(ISuite)` | `ExtentReportManager.flush()` |
| `onTestStart` | `ExtentReportManager.createTest(name, description)` |
| `onTestSuccess` | `getTest().pass(details)` |
| `onTestFailure` | `getTest().fail(throwable)` + attach screenshot |
| `onTestSkipped` | `getTest().skip(reason)` |

**Retry tracking:** if `ITestResult.getAttribute("retryCount") > 0`, node name is suffixed with `(Retry attempt N)`.

### 11.3 RetryAnalyzer

```java
@Test(retryAnalyzer = RetryAnalyzer.class)
public void testTransactionFlow(Object[] data) { ... }
```

```java
public class RetryAnalyzer implements IRetryAnalyzer {
    private int attempt = 0;
    private static final int MAX = ConfigReader.getInstance()
                                               .getInt("retryCount", 2);

    @Override
    public boolean retry(ITestResult result) {
        if (attempt < MAX) {
            attempt++;
            result.setAttribute("retryCount", attempt);
            return true;   // retry
        }
        return false;      // give up
    }
}
```

---

## 12. Test Classes

### AlbertaPOSLaunchTest

**Sheet:** `Launch` | **Suite:** `testng.xml`

| Step | Action | Assertion |
|---|---|---|
| 1 | Launch AlbertaPOS via `BaseTest.@BeforeSuite` | Process started without exception |
| 2 | Handle UAC popup if present | Dialog dismissed |
| 3 | Perform login (`posUsername` / `posPassword`) | Login screen closes |
| 4 | Wait for main window to attach | `mainSession` driver is non-null |
| 5 | Validate window title | Title contains `appWindowTitleFragment` |

### HomePageTest

**Sheet:** `HomePage` | **Suite:** `testng-hometest.xml`

After login (shared `@BeforeSuite`), verifies:
- At least one Button control is visible
- At least one MenuItem control is visible
- Window title matches `homePageTitleFragment`
- No error dialog is present

### HomePageValidationTest

**Sheet:** `HomePage` | **Suite:** `testng-homepage.xml`

Comprehensive control-type scan:
- Counts Buttons, MenuItems, TextBlocks, Edit, ComboBox, List controls
- Verifies minimum expected counts per control type (configurable thresholds)
- Checks for error dialogs with exact-match + keyword heuristic
- Logs each control count to Extent report as a separate step

### TransactionTest

**Sheet:** `Transaction` | **Suite:** `testng-transaction.xml`

Full 11-step cash transaction — see [§4.6 Transaction Flow](#46-transaction-flow--sequence) for the complete sequence diagram.

---

## 13. TestNG Suites

### Suite Files

| File | Test Classes | Launch Count | Notes |
|---|---|---|---|
| `testng.xml` | `AlbertaPOSLaunchTest` | 1 | Smoke suite |
| `testng-hometest.xml` | `HomePageTest` | 1 | Home page basic checks |
| `testng-homepage.xml` | `HomePageValidationTest` | 1 | Comprehensive HP validation |
| `testng-transaction.xml` | `TransactionTest` | 1 | Full transaction flow |
| `testng-full-suite.xml` | All four | 1 | Single launch for entire regression |

### `testng-full-suite.xml` — Login State Warning

When all four test classes run in one suite, **login state persists** between classes. `AlbertaPOSLaunchTest` logs in; subsequent classes (`HomePageTest`, `TransactionTest`, etc.) start with the session already authenticated. If a test logs out or the session expires, downstream tests will fail. Always run the full suite from a clean AlbertaPOS state.

### Suite XML Structure

```xml
<!DOCTYPE suite SYSTEM "https://testng.org/testng-1.0.dtd">
<suite name="AlbertaPOS Smoke Suite" verbose="1">
    <listeners>
        <listener class-name="com.pos.automation.listeners.ExtentReportListener"/>
    </listeners>
    <test name="Launch Tests">
        <classes>
            <class name="com.pos.automation.tests.AlbertaPOSLaunchTest"/>
        </classes>
    </test>
</suite>
```

---

## 14. Configuration Reference

All configuration lives in `src/test/resources/config.properties`.

### Application & Driver

| Key | Type | Example Value | Purpose |
|---|---|---|---|
| `appPath` | String | `C:\Program Files\AlbertaPOS\AlbertaPOS.exe` | Absolute path to POS executable |
| `winAppDriverUrl` | String | `http://127.0.0.1:4723` | WinAppDriver HTTP endpoint |
| `winAppDriverExePath` | String | `C:\Program Files\Windows Application Driver\WinAppDriver.exe` | Auto-start path |
| `implicitWait` | int | `15` | WebDriver implicit wait (seconds) |
| `appLaunchWaitSeconds` | int | `90` | `ms:waitForAppLaunch` capability value |

### Window Detection

| Key | Type | Example Value | Purpose |
|---|---|---|---|
| `appWindowTitleFragment` | String | `AlbertaPOS` | Fragment matched during main window polling |
| `homePageTitleFragment` | String | `Alberta` | Fragment validated after `appTopLevelWindow` attach |

### Credentials

| Key | Type | Example Value | Purpose |
|---|---|---|---|
| `posUsername` | String | `111` | POS login username |
| `posPassword` | String | `1111` | POS login password |

### Test Data

| Key | Type | Example Value | Purpose |
|---|---|---|---|
| `transactionBarcode` | String | `998877665501` | Barcode used in `TransactionTest` |
| `receiptsFolder` | String | `C:\POS\Receipts` | Directory scanned for saved receipt files |
| `excelFilePath` | String | `src/test/resources/testdata/POSTestData.xlsx` | Path to Excel test data file |

### Framework Behaviour

| Key | Type | Default | Purpose |
|---|---|---|---|
| `environment` | String | `DEV` | DEV / QA / PROD — used in reports |
| `executionMode` | String | `local` | local / ci |
| `stopWinAppDriverAfterSuite` | boolean | `false` | Kill WinAppDriver.exe after `@AfterSuite` |
| `retryCount` | int | `2` | Max retry attempts per failed test |

### Output Paths

| Key | Type | Default | Purpose |
|---|---|---|---|
| `reportPath` | String | `target/reports` | Base directory for Extent HTML reports |
| `screenshotPath` | String | `target/screenshots` | Directory for Robot PNG captures |

### Database (optional)

| Key | Type | Purpose |
|---|---|---|
| `dbDriver` | String | JDBC driver class (`com.mysql.cj.jdbc.Driver`) |
| `dbUrl` | String | JDBC URL (`jdbc:mysql://host:3306/dbname`) |
| `dbUsername` | String | MySQL username |
| `dbPassword` | String | MySQL password |

---

## 15. Execution Guide

### Prerequisites

1. **Java 21** installed and on `PATH`
2. **Maven 3.x** installed and on `PATH`
3. **WinAppDriver 1.x** installed at `winAppDriverExePath`
4. **AlbertaPOS.exe** installed at `appPath`
5. **Run IDE / terminal as Administrator** (required for WinAppDriver auto-start and UIPI bypass)

### One-Time Setup

```bash
# Generate POSTestData.xlsx with default test data (run once, then customize)
mvn test-compile exec:java -Dexec.mainClass="com.pos.automation.util.CreateSampleExcel" -Dexec.classpathScope="test"
```

### Running Tests

```bash
# Smoke suite (launch + login + title verify)
mvn clean test

# Home page validation
mvn clean test -DsuiteXmlFile=src/test/resources/testng-homepage.xml

# Home page tests
mvn clean test -DsuiteXmlFile=src/test/resources/testng-hometest.xml

# Transaction flow
mvn clean test -DsuiteXmlFile=src/test/resources/testng-transaction.xml

# Full regression (all classes, single AlbertaPOS launch)
mvn clean test -DsuiteXmlFile=src/test/resources/testng-full-suite.xml

# Single test class
mvn test -Dtest=TransactionTest

# Single test method
mvn test -Dtest=TransactionTest#testTransactionFlow

# Compile only
mvn clean compile

# Package without tests
mvn clean package -DskipTests
```

### Output Locations

| Artifact | Path |
|---|---|
| Extent HTML report | `target/reports/ExtentReport_{yyyyMMdd_HHmmss}/index.html` |
| SLF4J log file | `logs/automation.log` |
| Failure screenshots | `target/screenshots/{testName}_{timestamp}.png` |
| TestNG Surefire XML | `target/surefire-reports/` |

---

## 16. Data-Driven Testing Guide

### Excel File Structure (`POSTestData.xlsx`)

The file has one sheet per test class category:

| Sheet | Used By |
|---|---|
| `Launch` | `AlbertaPOSLaunchTest` |
| `HomePage` | `HomePageTest`, `HomePageValidationTest` |
| `Transaction` | `TransactionTest` |

### Mandatory Columns (all sheets)

| Column Header | Values | Purpose |
|---|---|---|
| `TestCaseID` | String (e.g. TC_001) | Unique identifier — appears in Extent report |
| `Execute` | `YES` / `NO` | Rows with `NO` are skipped by `ExcelUtil` |

### Result Columns (written back automatically)

| Column Header | Example | Written By |
|---|---|---|
| `Status` | `PASS` / `FAIL` | `ExcelUtil.updateRowResult()` |
| `Duration(ms)` | `4523` | `ExcelUtil.updateRowResult()` |
| `SystemName` | `DESKTOP-XYZ` | `ExcelUtil.updateRowResult()` |
| `Timestamp` | `2026-05-15T10:23:01` | `ExcelUtil.updateRowResult()` |

### Wiring a New Sheet

1. Open `POSTestData.xlsx` and add a new sheet (e.g. `Payment`)
2. Add `TestCaseID`, `Execute`, and your input columns
3. Add result columns (`Status`, `Duration(ms)`, `SystemName`, `Timestamp`)
4. Annotate the test class: `@TestDataSheet("Payment")`
5. Add a `@DataProvider` method:
   ```java
   @DataProvider(name = "testData")
   public Object[][] dataProvider() {
       TestDataSheet sheet = getClass().getAnnotation(TestDataSheet.class);
       return new ExcelUtil().getDataAsObjectArray(sheet.sheetName());
   }
   ```
6. In the test method: call `ExcelUtil.updateRowResult(...)` after execution

---

## 17. Adding New Tests

### Step 1 — Create a Page Object

```java
package com.pos.automation.pages;

public class PaymentPage extends BasePage {
    private final CustomAction action;

    public PaymentPage(RemoteWebDriver driver) {
        super(driver);
        this.action = new CustomAction(driver);
    }

    public void selectPaymentMethod(String method) {
        action.selectDropdown(By.xpath("//ComboBox[@AutomationId='cmbPayment']"), method);
    }
}
```

### Step 2 — Create the Test Class

```java
@TestDataSheet("Payment")
public class PaymentTest extends BaseTest {

    @DataProvider(name = "testData")
    public Object[][] dataProvider() {
        TestDataSheet sheet = getClass().getAnnotation(TestDataSheet.class);
        return new ExcelUtil().getDataAsObjectArray(sheet.sheetName());
    }

    @Test(dataProvider = "testData", retryAnalyzer = RetryAnalyzer.class)
    public void testPaymentFlow(Object[] rowData) {
        long start = System.currentTimeMillis();
        ExtentTest test = ExtentReportManager.getTest();

        try {
            String method = (String) rowData[2];   // column index 2

            PaymentPage paymentPage = new PaymentPage(driver);
            paymentPage.selectPaymentMethod(method);

            test.pass("Payment method selected: " + method);
            ExcelUtil.updateRowResult("Payment", 1, "PASS",
                                     System.currentTimeMillis() - start);
        } catch (Exception e) {
            test.fail(e);
            ExcelUtil.updateRowResult("Payment", 1, "FAIL",
                                     System.currentTimeMillis() - start);
            Assert.fail(e.getMessage());
        }
    }
}
```

### Step 3 — Create Suite XML

```xml
<!DOCTYPE suite SYSTEM "https://testng.org/testng-1.0.dtd">
<suite name="Payment Suite" verbose="1">
    <listeners>
        <listener class-name="com.pos.automation.listeners.ExtentReportListener"/>
    </listeners>
    <test name="Payment Tests">
        <classes>
            <class name="com.pos.automation.tests.PaymentTest"/>
        </classes>
    </test>
</suite>
```

Save as `src/test/resources/testng-payment.xml`.

### Step 4 — Add Excel Sheet

Add a `Payment` sheet to `POSTestData.xlsx` with columns: `TestCaseID`, `Execute`, `PaymentMethod`, `Status`, `Duration(ms)`, `SystemName`, `Timestamp`.

### Step 5 — Run

```bash
mvn clean test -DsuiteXmlFile=src/test/resources/testng-payment.xml
```

---

## 18. Known Issues & Workarounds

| Issue | Root Cause | Framework Workaround |
|---|---|---|
| **NEW_SESSION fails** | Selenium 4 strips `app`/`deviceName` non-W3C keys before sending | `WinAppDriverCommandExecutor` sends raw JSONWP POST, bypassing Selenium codec |
| **UIPI blocks input injection** | AlbertaPOS at high integrity blocks SendInput/SendMessage from medium-integrity JVM | `LoginPage` 5-strategy fallback chain; Win32 `PostMessage` via PowerShell as last resort |
| **Stale WAD sessions cause session creation failure** | Previous test run crashed without `@AfterSuite` cleanup | `WinAppDriverManager.cleanupStaleSessions()` called before every new suite |
| **UAC popup blocks window attach** | AlbertaPOS triggers a UAC elevation prompt on first launch | `AlbertaPOSPage.handleUACPopup()` using a separate `createForDesktop()` session |
| **Session restore popup blocks login** | AlbertaPOS detects an un-closed prior session and shows a resume dialog | `LoginPage.checkAndDismissSessionPopup()` runs before every login attempt |
| **Robot screenshot misses window content** | Not an issue — Robot captures raw screen pixels regardless of focus | `ScreenshotUtil` uses `Robot`, not `driver.getScreenshotAs()` |
| **PowerShell encoding issues** | Special characters in PS scripts break command-line quoting | `ProcessUtil` uses Base64 `-EncodedCommand` (UTF-16 LE) eliminating all quoting issues |
| **WAD won't auto-start** | `ProcessBuilder` requires same or higher integrity than WAD (elevated) | Falls back to 5-minute manual-wait loop with console prompt; auto-start works when JVM is elevated |
| **Receipt save dialog type varies** | Some AlbertaPOS versions show OS `SaveFileDialog`; others show WinForms custom dialog | `TransactionPage.handleReceiptSaveDialog()` tries Path A (OS) then Path B (WinForms) after iterating all window handles |

---

## 19. Logging

### Configuration — `logback-test.xml`

```xml
<configuration>
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>logs/automation.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
            <fileNamePattern>logs/automation.%d{yyyy-MM-dd}.log</fileNamePattern>
            <maxHistory>7</maxHistory>  <!-- 7-day retention -->
        </rollingPolicy>
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="FILE"/>
    </root>
</configuration>
```

### Usage in Classes

```java
private static final Logger log = LoggerFactory.getLogger(TransactionPage.class);

log.info("Entering barcode: {}", barcode);
log.warn("Strategy 1 failed, trying Strategy 2");
log.error("All login strategies exhausted", exception);
```

### Log Levels

| Level | When Used |
|---|---|
| `INFO` | Normal execution steps (each test step, element found, action taken) |
| `WARN` | Strategy fallback triggered, element not found on first attempt |
| `ERROR` | Test failure, unrecoverable exception, all strategies exhausted |
| `DEBUG` | Verbose diagnostic output (locator attempts, HWND values) — not enabled by default |

---

*End of Technical Documentation*
