package com.pos.automation.utils;

import com.aventstack.extentreports.ExtentReports;
import com.aventstack.extentreports.ExtentTest;
import com.aventstack.extentreports.reporter.ExtentSparkReporter;
import com.aventstack.extentreports.reporter.configuration.Theme;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Singleton manager for Extent Reports 5 lifecycle.
 *
 * <p>A single {@link ExtentReports} instance is created per JVM run (guarded by
 * {@code synchronized}). Reports are written to:
 * <pre>
 *   {reportPath}/ExtentReport_{yyyyMMdd_HHmmss}/index.html
 * </pre>
 * where {@code reportPath} is read from {@link ConfigReader#REPORT_PATH}.
 *
 * <p>A {@link ThreadLocal} holds one {@link ExtentTest} per thread, enabling safe
 * concurrent use if TestNG parallel execution is ever enabled.
 *
 * <p>Usage in a TestNG listener:
 * <pre>
 *   public void onTestStart(ITestResult result) {
 *       ExtentReportManager.createTest(result.getMethod().getMethodName());
 *   }
 *   public void onTestSuccess(ITestResult result) {
 *       ExtentReportManager.getTest().pass("Test passed");
 *   }
 * </pre>
 */
public final class ExtentReportManager {

    private static final Logger log = LoggerFactory.getLogger(ExtentReportManager.class);
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private static ExtentReports extentReports;
    private static final ThreadLocal<ExtentTest> testThreadLocal = new ThreadLocal<>();

    private ExtentReportManager() {}

    // ── Singleton access ─────────────────────────────────────────────────────────

    /**
     * Returns the singleton {@link ExtentReports} instance, creating it on first call.
     * Sets up the {@link ExtentSparkReporter} with a timestamp-based output directory.
     */
    public static synchronized ExtentReports getInstance() {
        if (extentReports == null) {
            String outputPath = buildReportPath();
            new File(outputPath).mkdirs();

            ExtentSparkReporter spark = new ExtentSparkReporter(outputPath + "/index.html");
            spark.config().setDocumentTitle("AlbertaPOS Automation Report");
            spark.config().setReportName("POS Automation Execution Report");
            spark.config().setTheme(Theme.STANDARD);
            spark.config().setEncoding("utf-8");

            extentReports = new ExtentReports();
            extentReports.attachReporter(spark);

            extentReports.setSystemInfo("OS",           System.getProperty("os.name"));
            extentReports.setSystemInfo("Java Version", System.getProperty("java.version"));
            extentReports.setSystemInfo("Environment",  ConfigReader.getString(ConfigReader.ENVIRONMENT, "DEV"));
            extentReports.setSystemInfo("ExecutionMode",ConfigReader.getString(ConfigReader.EXECUTION_MODE, "local"));
            extentReports.setSystemInfo("Framework",    "WinAppDriver + Appium + TestNG");

            log.info("ExtentReports initialised — output: {}/index.html", outputPath);
        }
        return extentReports;
    }

    // ── Test management ──────────────────────────────────────────────────────────

    /**
     * Creates a new {@link ExtentTest} node and stores it in the current thread's local.
     *
     * @param testName display name for the test in the report
     * @return the created {@link ExtentTest}
     */
    public static ExtentTest createTest(String testName) {
        ExtentTest test = getInstance().createTest(testName);
        testThreadLocal.set(test);
        log.debug("ExtentTest created: {}", testName);
        return test;
    }

    /**
     * Stores a pre-created {@link ExtentTest} in the current thread's local.
     * Use when the test node was created externally.
     */
    public static void setTest(ExtentTest test) {
        testThreadLocal.set(test);
    }

    /**
     * Returns the {@link ExtentTest} stored for the current thread, or {@code null}
     * if {@link #createTest} / {@link #setTest} has not been called on this thread.
     */
    public static ExtentTest getTest() {
        return testThreadLocal.get();
    }

    // ── Flush ────────────────────────────────────────────────────────────────────

    /** Writes the report to disk. Safe to call multiple times. */
    public static synchronized void flushReports() {
        if (extentReports != null) {
            extentReports.flush();
            log.info("ExtentReports flushed to disk");
        }
    }

    // ── Private ──────────────────────────────────────────────────────────────────

    private static String buildReportPath() {
        String base      = ConfigReader.getString(ConfigReader.REPORT_PATH, "target/reports");
        String timestamp = LocalDateTime.now().format(TS_FMT);
        return base + "/ExtentReport_" + timestamp;
    }
}
