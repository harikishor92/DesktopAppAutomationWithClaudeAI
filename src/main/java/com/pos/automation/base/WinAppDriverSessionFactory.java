package com.pos.automation.base;

import org.openqa.selenium.remote.DesiredCapabilities;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;

/**
 * Creates WinAppDriver sessions for the three session types used by this framework.
 *
 * Before this class existed, DesiredCapabilities setup was duplicated in both
 * BaseTest (app-launch session) and AlbertaPOSPage (desktop session + window-attach session).
 * Centralising here means capability keys and values are defined in one place.
 */
public final class WinAppDriverSessionFactory {

    private static final Logger log = LoggerFactory.getLogger(WinAppDriverSessionFactory.class);

    private static final String DEVICE_NAME = "WindowsPC";
    private static final String PLATFORM    = "Windows";

    private WinAppDriverSessionFactory() {}

    /**
     * Creates a session that launches the application at {@code appPath} and waits for
     * its first window to appear.
     *
     * @param appPath          absolute path to the executable
     * @param waitForAppLaunch seconds WinAppDriver waits before timing out
     * @param url              WinAppDriver endpoint
     */
    public static RemoteWebDriver createForApp(String appPath, int waitForAppLaunch, URL url) {
        DesiredCapabilities caps = buildBaseCaps();
        caps.setCapability("app", appPath);
        caps.setCapability("ms:waitForAppLaunch", String.valueOf(waitForAppLaunch));
        log.debug("Creating app session — app='{}' waitForAppLaunch={}s", appPath, waitForAppLaunch);
        return new RemoteWebDriver(new WinAppDriverCommandExecutor(url, caps), caps);
    }

    /**
     * Creates a session attached to the Windows desktop Root element.
     * Allows locating system-level dialogs (UAC, security warnings) that live outside
     * any application process.
     *
     * @param url WinAppDriver endpoint
     */
    public static RemoteWebDriver createForDesktop(URL url) {
        DesiredCapabilities caps = buildBaseCaps();
        caps.setCapability("app", "Root");
        log.debug("Creating desktop (Root) session");
        return new RemoteWebDriver(new WinAppDriverCommandExecutor(url, caps), caps);
    }

    /**
     * Creates a session that attaches directly to an already-running window identified
     * by its Win32 HWND. Does not launch a new process.
     *
     * @param hexHwnd hex-formatted HWND (e.g. "0x001A0898")
     * @param url     WinAppDriver endpoint
     */
    public static RemoteWebDriver createForWindow(String hexHwnd, URL url) {
        DesiredCapabilities caps = buildBaseCaps();
        caps.setCapability("appTopLevelWindow", hexHwnd);
        log.debug("Creating window-attach session — HWND={}", hexHwnd);
        return new RemoteWebDriver(new WinAppDriverCommandExecutor(url, caps), caps);
    }

    private static DesiredCapabilities buildBaseCaps() {
        DesiredCapabilities caps = new DesiredCapabilities();
        caps.setCapability("deviceName", DEVICE_NAME);
        caps.setCapability("platformName", PLATFORM);
        return caps;
    }
}
