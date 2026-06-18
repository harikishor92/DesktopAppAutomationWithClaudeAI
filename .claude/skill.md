# AlbertaPOS Automation — Project Skills

This file defines project-specific skill behaviors for Claude Code when working in this
WinAppDriver + TestNG automation framework targeting AlbertaPOS (Windows desktop app).

---

## Skill: `run`

**Trigger**: User asks to run, start, execute tests, or launch the app.

### Prerequisites (check before running)

1. **Admin privileges** — The test JVM must be elevated. WinAppDriver auto-starts only when
   running as Administrator. Prompt the user to open their terminal as Administrator if needed.

2. **WinAppDriver** — Auto-started by `BaseTest.setUp()` when elevated. If not elevated, the
   user must start `C:\Program Files (x86)\Windows Application Driver\WinAppDriver.exe` manually
   before running tests.

3. **AlbertaPOS installed** — Executable at `C:\Program Files (x86)\Alberta Payments LLC\AlbertaPOS\AlbertaPOS.exe`
   (configurable via `appPath` in `src/test/resources/config.properties`).

4. **Credentials** — `posUsername=111`, `posPassword=1111` in `config.properties`.
   Update if the POS environment uses different credentials.

### Commands by suite

| Intent | Command |
|--------|---------|
| Smoke: launch + login + main window | `mvn clean test` |
| Single test class | `mvn test -Dtest=AlbertaPOSLaunchTest` |
| Single test method | `mvn test -Dtest=AlbertaPOSLaunchTest#testAlbertaPOSLaunchesSuccessfully` |
| Home Page validation | `mvn clean test -DsuiteXmlFile=src/test/resources/testng-homepage.xml` |
| Home Page tests | `mvn clean test -DsuiteXmlFile=src/test/resources/testng-hometest.xml` |
| Transaction flow | `mvn clean test -DsuiteXmlFile=src/test/resources/testng-transaction.xml` |
| Full suite (single launch) | `mvn clean test -DsuiteXmlFile=src/test/resources/testng-full-suite.xml` |
| Compile only | `mvn clean compile` |

### What to look for in output

**Pass indicators:**
```
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

**Login strategy indicators (in logs/automation.log):**
- `Login succeeded via Strategy A` — AutomationId path worked; Strategy B was NOT executed
- `Login succeeded via Strategy B` — AutomationId path failed; coordinate fallback was used
- `winAppClick(...)` lines appearing — Strategy B is running (should only appear when A failed)

**Title validation:**
- `Window title validation passed: 'Home'` — correct; main window attached
- `Window title validation passed: 'Alberta POS Login'` — wrong window; `getWindowTitle()` guard broken

**Retry indicators (normal when app is slow):**
- `Tests run: 3, Failures: 0, Errors: 0, Skipped: 2` — test passed on retry (retryCount=2)
- `(Retry attempt N)` in Extent report — same indication

### Reports and artifacts

- **Extent HTML report**: `target/reports/ExtentReport_{timestamp}/index.html`
- **Surefire XML**: `target/surefire-reports/`
- **Logs**: `logs/automation.log` (rolling daily, 7-day retention)
- **Screenshots** (failures only): `target/screenshots/`

---

## Skill: `verify`

**Trigger**: User asks to verify a fix, confirm a change works, or validate before committing.

### Steps

1. **Compile first** — Run `mvn clean compile` and confirm `BUILD SUCCESS` before running tests.
   A compile failure means the code change has a syntax/import error.

2. **Run the smoke suite** — `mvn clean test` runs `AlbertaPOSLaunchTest` which exercises:
   - Step 1: UAC/permission popup handling
   - Step 2: FrmLog login screen detection
   - Step 3: Login (Strategy A → Strategy B fallback)
   - Step 4: Main window detection (dual-path: app session + PowerShell Win32)
   - Step 5: Main window title validation (`'Home'`)

3. **Check log output** against pass/fail indicators above.

4. **For login-related changes** — tail `logs/automation.log` and verify:
   - `login-verify: powershell='main' → still-on-login-screen=false` (Strategy A success path)
   - NO `Strategy A claimed success but still on FrmLog` warning
   - NO unexpected `winAppClick(...)` lines (Strategy B should not run after successful A)

5. **For page object changes** — run the relevant suite:
   - `LoginPage` changes → `mvn clean test`
   - `HomePage` changes → `mvn clean test -DsuiteXmlFile=src/test/resources/testng-hometest.xml`
   - `TransactionPage` changes → `mvn clean test -DsuiteXmlFile=src/test/resources/testng-transaction.xml`

### Key invariants to verify after any login change

| Check | Expected |
|-------|---------|
| `isStillOnLoginScreen()` uses | PowerShell `Get-Process AlbertaPOS -notlike '*Login*'` |
| `getWindowTitle()` in `AlbertaPOSPage` | Excludes titles containing `"login"` |
| `appWindowTitleFragment` in config | `Home` (not `Alberta` — `Alberta` also matches FrmLog) |
| Strategy B executes | Only when Strategy A returns `false` |
| `winAppClick()` calls in log | Absent when Strategy A succeeds |

---

## Skill: `simplify` / code review guidance

**Trigger**: User asks to review, simplify, or clean up code in this project.

### Project-specific rules

- **No `driver.getTitle()` for login detection** — FrmLog HWND session always returns
  `'Alberta POS Login'` after login because FrmLog stays alive as a background window.
  Use `ProcessUtil.runPowerShell(...)` with `Get-Process AlbertaPOS | Where-Object { $_.MainWindowTitle -notlike '*Login*' }`.

- **No `ConfigReader` import in `LoginPage`** — `LoginPage` uses `ProcessUtil` for login
  verification; `ConfigReader` is not needed and was intentionally removed.

- **`@DataProvider(name="testData")` in `AlbertaPOSLaunchTest`** — Keep even if not wired
  to `@Test`. User explicitly requires it.

- **Named delay constants** — All `Thread.sleep` values in `LoginPage` must use the named
  constants (`FOCUS_DELAY_MS`, `DIGIT_DELAY_MS`, `SUBMIT_DELAY_MS`, `COORD_DELAY_MS`),
  not magic numbers.

- **No `AppiumDriver` replacement** — `WinAppDriverCommandExecutor` is a required JSONWP/W3C
  bridge. Do not replace with standard `AppiumDriver` or plain `RemoteWebDriver` — session
  creation will fail.

- **No `Thread.sleep` in page objects** (except named delay constants in `LoginPage`) —
  All waiting must use `FluentWait` with polling.

- **`findFirst(By...)` implicit-wait pattern** — Must set `implicitlyWait(Duration.ZERO)` in
  try block, restore to 15 s in `finally`. WinAppDriver floors this at 500 ms minimum.

---

## Architecture quick-reference

### Three WinAppDriver session types

| Session | Created by | Bound to | Used for |
|---------|-----------|----------|---------|
| App session | `BaseTest.setUp()` | AlbertaPOS.exe process | Login screen interaction |
| Desktop Root session | `AlbertaPOSPage.getMainWindowHandleViaDesktopSession()` | Windows Root | HWND lookup fallback |
| Main window session | `AlbertaPOSPage.attachToMainWindow()` | Main window HWND | Home/Transaction page tests |

### Login strategy execution order

1. **Strategy A** (primary) — UIAutomation element click via AutomationIds:
   `txtUserName` → digit `btn{N}` → `txtPassword` → digit `btn{N}` → `btnLogin`
2. **Strategy B** (fallback) — W3C PointerInput absolute screen coordinates (1920×1080 calibrated)

Success detection: PowerShell `Get-Process AlbertaPOS | Where-Object { $_.MainWindowTitle -notlike '*Login*' }` polled up to 8 × 1 s.

### Main window detection (dual-path)

1. **App session handles** — iterate `d.getWindowHandles()`, find title containing `appWindowTitleFragment` and not containing `"login"`
2. **PowerShell Win32** — `Get-Process AlbertaPOS | Where-Object { $_.MainWindowHandle -ne 0 -and $_.MainWindowTitle -notlike '*Login*' }`

### Config values that affect test behaviour

| Key | Current value | Effect if wrong |
|-----|-------------|----------------|
| `appWindowTitleFragment` | `Home` | If `Alberta`, FrmLog title passes title assertion |
| `posUsername` | `111` | Login fails silently; Strategy B also fails |
| `posPassword` | `1111` | Same as above |
| `retryCount` | `2` | Higher → masks flakiness; `0` → no retries |
| `stopWinAppDriverAfterSuite` | `false` | `true` kills WinAppDriver between runs |
