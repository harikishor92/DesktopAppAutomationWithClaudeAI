package com.pos.automation.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Centralized configuration reader for the POS automation framework.
 *
 * Provides typed accessors and named constants for every key in config.properties.
 * Independent of {@link PropertyReader} — both classes load the same file but
 * {@code ConfigReader} is the preferred API for all new code.
 *
 * Usage:
 * <pre>
 *   String url = ConfigReader.getString(ConfigReader.WIN_APP_DRIVER_URL);
 *   int wait   = ConfigReader.getInt(ConfigReader.IMPLICIT_WAIT, 10);
 * </pre>
 */
public final class ConfigReader {

    private static final Logger log = LoggerFactory.getLogger(ConfigReader.class);
    private static final String CONFIG_FILE = "config.properties";
    private static final Properties props = new Properties();

    static {
        try (InputStream in = ConfigReader.class.getClassLoader().getResourceAsStream(CONFIG_FILE)) {
            if (in == null) {
                throw new RuntimeException("config.properties not found on classpath");
            }
            props.load(in);
            log.debug("ConfigReader loaded {}", CONFIG_FILE);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load " + CONFIG_FILE, e);
        }
    }

    private ConfigReader() {}

    // ── Config key constants ────────────────────────────────────────────────────

    public static final String APP_PATH                = "appPath";
    public static final String WIN_APP_DRIVER_URL      = "winAppDriverUrl";
    public static final String WIN_APP_DRIVER_EXE      = "winAppDriverExePath";
    public static final String IMPLICIT_WAIT           = "implicitWait";
    public static final String APP_WINDOW_TITLE        = "appWindowTitleFragment";
    public static final String HOME_PAGE_TITLE         = "homePageTitleFragment";
    public static final String LOGIN_WINDOW_TITLE      = "loginWindowTitle";
    public static final String APP_LAUNCH_WAIT         = "appLaunchWaitSeconds";
    public static final String POS_USERNAME            = "posUsername";
    public static final String POS_PASSWORD            = "posPassword";
    public static final String RECEIPTS_FOLDER         = "receiptsFolder";
    public static final String ENVIRONMENT             = "environment";
    public static final String EXECUTION_MODE          = "executionMode";
    public static final String STOP_WINAPPDRIVER       = "stopWinAppDriverAfterSuite";
    public static final String REPORT_PATH             = "reportPath";
    public static final String SCREENSHOT_PATH         = "screenshotPath";
    public static final String RETRY_COUNT             = "retryCount";
    public static final String EXCEL_FILE_PATH         = "excelFilePath";
    public static final String DB_DRIVER               = "dbDriver";
    public static final String DB_URL                  = "dbUrl";
    public static final String DB_USERNAME             = "dbUsername";
    public static final String DB_PASSWORD             = "dbPassword";

    // ── Admin / WinAppDriver lifecycle ──────────────────────────────────────────
    public static final String REQUIRE_ADMIN_PRIVILEGES    = "requireAdminPrivileges";
    public static final String WINAPPDRIVER_START_TIMEOUT  = "winAppDriverStartTimeoutSeconds";
    public static final String WINAPPDRIVER_HEALTH_RETRIES = "winAppDriverHealthCheckRetries";
    public static final String AUTOMATION_VM_MODE          = "automationVmMode";

    // ── Typed accessors ─────────────────────────────────────────────────────────

    /**
     * Returns the value for {@code key}, trimmed.
     * Throws {@link RuntimeException} if the key is absent or blank.
     */
    public static String getString(String key) {
        String value = props.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            throw new RuntimeException("Required config key '" + key + "' not found in " + CONFIG_FILE);
        }
        return value.trim();
    }

    /**
     * Returns the value for {@code key}, or {@code defaultValue} if absent/blank.
     */
    public static String getString(String key, String defaultValue) {
        String value = props.getProperty(key);
        return (value == null || value.trim().isEmpty()) ? defaultValue : value.trim();
    }

    /**
     * Returns the integer value for {@code key}, or {@code defaultValue} on missing/parse error.
     */
    public static int getInt(String key, int defaultValue) {
        try {
            return Integer.parseInt(getString(key));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    /**
     * Returns the boolean value for {@code key}.
     * Treats "true" (case-insensitive) as {@code true}; everything else as {@code defaultValue}.
     */
    public static boolean getBoolean(String key, boolean defaultValue) {
        try {
            return Boolean.parseBoolean(getString(key));
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
