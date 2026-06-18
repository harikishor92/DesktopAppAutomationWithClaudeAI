package com.pos.automation.listeners;

import com.aventstack.extentreports.Status;
import com.pos.automation.utils.ExtentReportManager;
import com.pos.automation.utils.ScreenshotUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.ISuite;
import org.testng.ISuiteListener;
import org.testng.ITestListener;
import org.testng.ITestResult;

/**
 * TestNG listener that drives Extent Reports 5 during test execution.
 *
 * <p>Registered in TestNG XML suite files:
 * <pre>
 *   &lt;listeners&gt;
 *     &lt;listener class-name="com.pos.automation.listeners.ExtentReportListener"/&gt;
 *   &lt;/listeners&gt;
 * </pre>
 *
 * <p>Lifecycle:
 * <ul>
 *   <li>{@code onStart(ISuite)} — initialises the singleton {@link ExtentReportManager}</li>
 *   <li>{@code onTestStart} — creates an {@link com.aventstack.extentreports.ExtentTest} node</li>
 *   <li>{@code onTestSuccess/Failure/Skipped} — logs pass/fail/skip with screenshot on failure</li>
 *   <li>{@code onFinish(ISuite)} — flushes the report to disk</li>
 * </ul>
 *
 * <p>Retry handling: when {@code result.getMethod().getCurrentInvocationCount() > 1},
 * "(Retry attempt N)" is appended to the test node name so retried runs are visible in the report.
 */
public class ExtentReportListener implements ITestListener, ISuiteListener {

    private static final Logger log = LoggerFactory.getLogger(ExtentReportListener.class);

    // ── ISuiteListener ───────────────────────────────────────────────────────────

    @Override
    public void onStart(ISuite suite) {
        log.info("Suite '{}' started — initialising Extent Reports", suite.getName());
        ExtentReportManager.getInstance();
    }

    @Override
    public void onFinish(ISuite suite) {
        log.info("Suite '{}' finished — flushing Extent Reports", suite.getName());
        ExtentReportManager.flushReports();
    }

    // ── ITestListener ────────────────────────────────────────────────────────────

    @Override
    public void onTestStart(ITestResult result) {
        String testName = buildTestName(result);
        log.info("Test started: {}", testName);
        ExtentReportManager.createTest(testName)
                .info("Test started: " + testName);
    }

    @Override
    public void onTestSuccess(ITestResult result) {
        long durationMs = result.getEndMillis() - result.getStartMillis();
        log.info("Test PASSED: {} ({}ms)", result.getMethod().getMethodName(), durationMs);
        safeLog(Status.PASS,
                "Test PASSED — duration: " + durationMs + "ms");
    }

    @Override
    public void onTestFailure(ITestResult result) {
        String testName  = result.getMethod().getMethodName();
        long durationMs  = result.getEndMillis() - result.getStartMillis();
        Throwable cause  = result.getThrowable();

        log.error("Test FAILED: {} ({}ms)", testName, durationMs);

        if (ExtentReportManager.getTest() != null) {
            ExtentReportManager.getTest()
                    .log(Status.FAIL, "Test FAILED — duration: " + durationMs + "ms");

            if (cause != null) {
                ExtentReportManager.getTest()
                        .log(Status.FAIL, "<b>Exception:</b> " + cause.getMessage());
                ExtentReportManager.getTest().fail(cause);
            }

            String screenshotPath = ScreenshotUtil.capture(testName + "_FAILED");
            if (screenshotPath != null) {
                try {
                    ExtentReportManager.getTest()
                            .addScreenCaptureFromPath(screenshotPath, "Failure Screenshot");
                    log.info("Screenshot attached to report: {}", screenshotPath);
                } catch (Exception e) {
                    log.warn("Could not attach screenshot to Extent Report: {}", e.getMessage());
                }
            }
        }
    }

    @Override
    public void onTestSkipped(ITestResult result) {
        log.info("Test SKIPPED: {}", result.getMethod().getMethodName());
        Throwable cause = result.getThrowable();
        safeLog(Status.SKIP,
                "Test SKIPPED" + (cause != null ? " — " + cause.getMessage() : ""));
    }

    @Override
    public void onTestFailedButWithinSuccessPercentage(ITestResult result) {
        // No-op — not used in this framework
    }

    // ── Private ──────────────────────────────────────────────────────────────────

    /**
     * Builds the test display name. Appends retry context when the test has been retried.
     */
    private String buildTestName(ITestResult result) {
        String name    = result.getMethod().getMethodName();
        int invocation = result.getMethod().getCurrentInvocationCount();
        return (invocation > 0)
                ? name + " (Retry attempt " + invocation + ")"
                : name;
    }

    /** Logs a status message to the current thread's ExtentTest; skips if null. */
    private void safeLog(Status status, String message) {
        if (ExtentReportManager.getTest() != null) {
            ExtentReportManager.getTest().log(status, message);
        }
    }
}
