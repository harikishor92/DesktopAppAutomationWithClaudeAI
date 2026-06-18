package com.pos.automation.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Centralised WinAppDriver lifecycle manager for the POS automation framework.
 *
 * <p>Responsibilities:
 * <ol>
 *   <li><b>Health check</b> — HTTP GET /status with configurable retry count</li>
 *   <li><b>Session readiness</b> — GET /sessions validates the HTTP layer is fully up</li>
 *   <li><b>Auto-start</b> — ProcessBuilder launch succeeds when test JVM is Administrator</li>
 *   <li><b>Manual-wait fallback</b> — polls with reminders when auto-start is not possible</li>
 *   <li><b>Stale session cleanup</b> — HTTP DELETE for all active sessions before a new run</li>
 * </ol>
 *
 * <p>Usage:
 * <pre>
 *   WinAppDriverManager mgr = new WinAppDriverManager(url, exePath);
 *   mgr.ensureRunning(true);   // cleanupSessions=true when no app is pre-running
 *   mgr.ensureRunning(false);  // cleanupSessions=false when AlbertaPOS is pre-running
 * </pre>
 */
public class WinAppDriverManager {

    private static final Logger log = LoggerFactory.getLogger(WinAppDriverManager.class);

    private static final int HEALTH_TIMEOUT_MS   = 3_000;
    private static final int START_POLL_MS       = 1_000;
    private static final int REMIND_INTERVAL_MS  = 30_000;
    private static final int MANUAL_MAX_MS       = 300_000; // 5 minutes

    private final String baseUrl;
    private final String exePath;
    private final int    startTimeoutSeconds;
    private final int    healthRetries;

    public WinAppDriverManager(String baseUrl, String exePath) {
        this(baseUrl, exePath,
                ConfigReader.getInt(ConfigReader.WINAPPDRIVER_START_TIMEOUT, 30),
                ConfigReader.getInt(ConfigReader.WINAPPDRIVER_HEALTH_RETRIES, 3));
    }

    public WinAppDriverManager(String baseUrl, String exePath,
                               int startTimeoutSeconds, int healthRetries) {
        this.baseUrl             = baseUrl;
        this.exePath             = exePath;
        this.startTimeoutSeconds = startTimeoutSeconds;
        this.healthRetries       = healthRetries;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Ensures WinAppDriver is running and healthy.
     *
     * <ol>
     *   <li>If already healthy: optionally cleans stale sessions and returns.</li>
     *   <li>Attempts auto-start (requires elevated JVM).</li>
     *   <li>Falls back to a manual-wait loop (up to 5 minutes).</li>
     * </ol>
     *
     * @param cleanupSessions {@code false} when AlbertaPOS is already running —
     *                        deleting an active {@code createForApp} session would
     *                        kill the attached process.
     */
    public void ensureRunning(boolean cleanupSessions) {
        log.info("╔═══════════════════════════════════════════════════════════╗");
        log.info("║          WinAppDriver Lifecycle Check                     ║");
        log.info("╚═══════════════════════════════════════════════════════════╝");
        log.info("  URL        : {}", baseUrl);
        log.info("  Executable : {}", exePath);
        log.info("  Start TO   : {}s | Health retries: {}", startTimeoutSeconds, healthRetries);

        if (isHealthy()) {
            log.info("  Status     : ALREADY RUNNING — healthy");
            if (cleanupSessions) {
                deleteStaleSessionsWithRetry();
            } else {
                log.info("  Sessions   : skipping cleanup (AlbertaPOS is pre-running)");
            }
            log.info("═══════════════════════════════════════════════════════════");
            return;
        }

        log.info("  Status     : NOT RUNNING — attempting auto-start");
        if (tryAutoStart()) {
            log.info("  Auto-start : SUCCESS");
            if (cleanupSessions) deleteStaleSessionsWithRetry();
            log.info("═══════════════════════════════════════════════════════════");
            return;
        }

        log.warn("  Auto-start : FAILED (JVM may not be elevated)");
        waitForManualStart();
        log.info("═══════════════════════════════════════════════════════════");
    }

    /**
     * Health check: HTTP GET /status → 200.
     * Retried up to {@link #healthRetries} times with 500 ms gaps.
     *
     * @return {@code true} if WinAppDriver responds correctly
     */
    public boolean isHealthy() {
        log.info("WinAppDriver health check (max {} attempt(s))...", healthRetries);
        for (int attempt = 1; attempt <= healthRetries; attempt++) {
            try {
                HttpURLConnection conn = openConnection(baseUrl + "/status", HEALTH_TIMEOUT_MS);
                int code = conn.getResponseCode();
                if (code == 200) {
                    log.info("  Health check PASSED — attempt {}/{} HTTP {}", attempt, healthRetries, code);
                    return true;
                }
                log.warn("  Health check attempt {}/{} — HTTP {} (unexpected)", attempt, healthRetries, code);
            } catch (Exception e) {
                log.debug("  Health check attempt {}/{} — {}: {}", attempt, healthRetries,
                        e.getClass().getSimpleName(), e.getMessage());
            }
            if (attempt < healthRetries) sleepQuietly(500);
        }
        log.info("  Health check FAILED — WinAppDriver unreachable at {}", baseUrl);
        return false;
    }

    /**
     * Session-readiness check: GET /sessions → 200.
     * Confirms the HTTP layer is fully initialised (port open ≠ HTTP ready).
     */
    public boolean isSessionCreationReady() {
        log.info("WinAppDriver session-creation readiness check...");
        try {
            HttpURLConnection conn = openConnection(baseUrl + "/sessions", HEALTH_TIMEOUT_MS);
            int code = conn.getResponseCode();
            boolean ready = (code == 200);
            log.info("  Session readiness: {} (HTTP {})", ready ? "READY" : "NOT READY", code);
            return ready;
        } catch (Exception e) {
            log.warn("  Session readiness check failed: {}", e.getMessage());
            return false;
        }
    }

    // ── Private: auto-start ───────────────────────────────────────────────────

    /**
     * Launches WinAppDriver via ProcessBuilder.
     * Succeeds when the test JVM is running as Administrator.
     * Prints a structured startup progress log.
     */
    private boolean tryAutoStart() {
        try {
            log.info("Auto-starting WinAppDriver: {}", exePath);
            new ProcessBuilder(exePath).inheritIO().start();

            long startMs  = System.currentTimeMillis();
            long deadline = startMs + startTimeoutSeconds * 1000L;
            int  poll     = 0;

            while (System.currentTimeMillis() < deadline) {
                sleepQuietly(START_POLL_MS);
                poll++;

                if (isPortResponding()) {
                    sleepQuietly(800); // brief pause for HTTP layer to initialise
                    if (isHealthy()) {
                        long elapsed = (System.currentTimeMillis() - startMs) / 1000;
                        log.info("WinAppDriver ready in ~{}s (poll #{})", elapsed, poll);
                        return true;
                    }
                }

                if (poll % 5 == 0) {
                    long elapsed = (System.currentTimeMillis() - startMs) / 1000;
                    log.info("  Waiting for WinAppDriver... ({}/{}s)", elapsed, startTimeoutSeconds);
                }
            }
            log.warn("WinAppDriver auto-start timed out after {}s", startTimeoutSeconds);

        } catch (java.io.IOException e) {
            // Access denied when JVM is not elevated — expected in non-admin runs.
            log.warn("WinAppDriver auto-start blocked ({}): {} — JVM not elevated",
                    e.getClass().getSimpleName(), e.getMessage());
        }
        return false;
    }

    // ── Private: manual-wait ──────────────────────────────────────────────────

    private void waitForManualStart() {
        String folder = exePath.contains("\\")
                ? exePath.substring(0, exePath.lastIndexOf('\\'))
                : exePath;

        log.warn("╔══════════════════════════════════════════════════════════════╗");
        log.warn("║        WinAppDriver IS NOT RUNNING — Action Required         ║");
        log.warn("║                                                              ║");
        log.warn("║  Start WinAppDriver as Administrator (one-time per session): ║");
        log.warn("║    1. Open folder : {}", folder);
        log.warn("║    2. Right-click   WinAppDriver.exe                         ║");
        log.warn("║    3. Choose        Run as administrator                      ║");
        log.warn("║    4. Click Yes     on the UAC prompt                        ║");
        log.warn("║                                                              ║");
        log.warn("║  Waiting up to 5 minutes...                                  ║");
        log.warn("╚══════════════════════════════════════════════════════════════╝");

        long start      = System.currentTimeMillis();
        long deadline   = start + MANUAL_MAX_MS;
        long nextRemind = start + REMIND_INTERVAL_MS;

        while (System.currentTimeMillis() < deadline) {
            if (isPortResponding() && isHealthy()) {
                long elapsed = (System.currentTimeMillis() - start) / 1000;
                log.info("WinAppDriver detected after {}s — ready at {}", elapsed, baseUrl);
                return;
            }
            if (System.currentTimeMillis() >= nextRemind) {
                long elapsed   = (System.currentTimeMillis() - start) / 1000;
                long remaining = (deadline - System.currentTimeMillis()) / 1000;
                log.warn("Still waiting for WinAppDriver... ({}s elapsed, {}s remaining)", elapsed, remaining);
                nextRemind += REMIND_INTERVAL_MS;
            }
            sleepQuietly(2_000);
        }

        throw new RuntimeException(
                "WinAppDriver was not started within 5 minutes.\n" +
                "  Folder : " + folder + "\n" +
                "  Action : right-click WinAppDriver.exe → Run as administrator\n" +
                "  URL    : " + baseUrl);
    }

    // ── Private: session cleanup ──────────────────────────────────────────────

    /**
     * Deletes all active WinAppDriver sessions (with one retry on transient failure).
     * Skips gracefully if the sessions endpoint is unavailable.
     */
    public void deleteStaleSessionsWithRetry() {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                deleteStaleWinAppDriverSessions();
                return;
            } catch (Exception e) {
                log.debug("Session cleanup attempt {}/2 failed: {}", attempt, e.getMessage());
                if (attempt < 2) sleepQuietly(500);
            }
        }
    }

    private void deleteStaleWinAppDriverSessions() throws Exception {
        HttpURLConnection conn = openConnection(baseUrl + "/sessions", 3_000);
        if (conn.getResponseCode() != 200) {
            log.debug("Session list returned non-200 — skipping cleanup");
            return;
        }
        String body;
        try (Scanner s = new Scanner(conn.getInputStream())) {
            body = s.useDelimiter("\\A").hasNext() ? s.next() : "";
        }

        Matcher m     = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        int     count = 0;
        while (m.find()) {
            String sessionId = m.group(1);
            log.info("Deleting stale WinAppDriver session: {}", sessionId);
            HttpURLConnection del = (HttpURLConnection)
                    URI.create(baseUrl + "/session/" + sessionId).toURL().openConnection();
            del.setRequestMethod("DELETE");
            del.setConnectTimeout(3_000);
            del.setReadTimeout(5_000);
            del.getResponseCode();
            count++;
        }
        log.info("Stale session cleanup: {} session(s) removed", count);
    }

    // ── Private: utilities ────────────────────────────────────────────────────

    /** Fast TCP-level check — lighter than isHealthy(), used in tight poll loops. */
    private boolean isPortResponding() {
        try {
            HttpURLConnection conn = openConnection(baseUrl + "/status", 500);
            return conn.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private static HttpURLConnection openConnection(String url, int timeoutMs) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setRequestMethod("GET");
        return conn;
    }

    private void sleepQuietly(long millis) {
        try { Thread.sleep(millis); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
