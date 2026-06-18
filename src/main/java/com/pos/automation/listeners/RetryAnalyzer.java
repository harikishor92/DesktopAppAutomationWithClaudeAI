package com.pos.automation.listeners;

import com.pos.automation.utils.ConfigReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.IRetryAnalyzer;
import org.testng.ITestResult;

/**
 * TestNG retry analyzer that re-runs failed tests up to a configurable maximum.
 *
 * <p>TestNG creates a new {@code RetryAnalyzer} instance per test method invocation,
 * so the per-instance {@code retryCount} field resets naturally for each test.
 * No {@code ThreadLocal} is needed.
 *
 * <p>The maximum retry count is read from {@link ConfigReader#RETRY_COUNT} (default 2).
 * Set {@code retryCount=0} in {@code config.properties} to disable retries entirely.
 *
 * <p>Wired per test via the annotation:
 * <pre>
 *   {@literal @}Test(retryAnalyzer = RetryAnalyzer.class)
 *   public void myTest() { ... }
 * </pre>
 */
public class RetryAnalyzer implements IRetryAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(RetryAnalyzer.class);

    private int retryCount = 0;
    private final int maxRetryCount;

    public RetryAnalyzer() {
        this.maxRetryCount = ConfigReader.getInt(ConfigReader.RETRY_COUNT, 2);
    }

    /**
     * Called by TestNG after a test failure.
     * Returns {@code true} to trigger a retry; {@code false} to mark the test as failed.
     */
    @Override
    public boolean retry(ITestResult result) {
        if (retryCount < maxRetryCount) {
            retryCount++;
            log.warn("Retrying test '{}' — attempt {}/{} — failure: {}",
                    result.getMethod().getMethodName(),
                    retryCount,
                    maxRetryCount,
                    result.getThrowable() != null ? result.getThrowable().getMessage() : "unknown");
            return true;
        }
        log.info("Test '{}' exhausted all {} retry attempt(s) — marking as FAILED",
                result.getMethod().getMethodName(), maxRetryCount);
        return false;
    }
}
