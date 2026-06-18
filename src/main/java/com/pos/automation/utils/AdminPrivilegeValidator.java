package com.pos.automation.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * Validates that the current JVM process is running with Windows Administrator privileges.
 *
 * <p>Why this matters for POS automation:
 * <ul>
 *   <li>WinAppDriver.exe auto-start requires an elevated caller (CreateProcess on an admin exe)</li>
 *   <li>AlbertaPOS.exe has a {@code requireAdministrator} manifest — direct launch needs admin token</li>
 *   <li>UIPI blocks Win32 input injection (PostMessage, SendInput) from medium→high integrity</li>
 * </ul>
 *
 * <p>When {@code requireAdminPrivileges=true} in config.properties and the JVM is NOT elevated,
 * {@link #validateAndFailIfRequired()} throws immediately with a remediation message.
 */
public final class AdminPrivilegeValidator {

    private static final Logger log = LoggerFactory.getLogger(AdminPrivilegeValidator.class);

    private AdminPrivilegeValidator() {}

    /**
     * Returns {@code true} when the current Windows process token contains the
     * Administrators group with the Enabled flag set (i.e. the process is elevated).
     *
     * Delegates to PowerShell's {@code WindowsPrincipal.IsInRole(Administrator)} which
     * checks the actual token elevation level, not just group membership.
     */
    public static boolean isRunningAsAdmin() {
        String script =
                "$p = [System.Security.Principal.WindowsPrincipal]" +
                "[System.Security.Principal.WindowsIdentity]::GetCurrent(); " +
                "if ($p.IsInRole([System.Security.Principal.WindowsBuiltInRole]::Administrator)) " +
                "{ 'true' } else { 'false' }";
        try {
            Process ps = new ProcessBuilder(
                    "powershell", "-NoProfile", "-NonInteractive", "-Command", script)
                    .redirectErrorStream(true).start();
            String output = new String(ps.getInputStream().readAllBytes()).trim();
            ps.waitFor(5, TimeUnit.SECONDS);
            return "true".equalsIgnoreCase(output);
        } catch (Exception e) {
            log.warn("Admin privilege check failed ({}) — assuming non-admin", e.getMessage());
            return false;
        }
    }

    /**
     * Logs a structured admin-status banner. Non-admin results in a detailed warning
     * listing the exact remediation steps for this framework.
     */
    public static void validate() {
        log.info("╔═══════════════════════════════════════════════════════════╗");
        log.info("║            Admin Privilege Validation                     ║");
        log.info("╚═══════════════════════════════════════════════════════════╝");

        boolean isAdmin = isRunningAsAdmin();

        if (isAdmin) {
            log.info("  Privilege level : ADMINISTRATOR (elevated token)");
            log.info("  WinAppDriver    : can be auto-started by this JVM");
            log.info("  AlbertaPOS      : can be launched directly (no UAC dialog)");
            log.info("  UIPI            : not a concern (same or higher integrity)");
        } else {
            log.warn("  Privilege level : STANDARD USER  (medium integrity)");
            log.warn("  WinAppDriver    : auto-start BLOCKED — must start manually as Admin");
            log.warn("  AlbertaPOS      : direct launch BLOCKED — Shell.Application UAC fallback used");
            log.warn("  UIPI            : Win32 input injection to AlbertaPOS is blocked");
            log.warn("                    (LegacyIAccessiblePattern bypass is in place)");
            log.warn("  ─────────────────────────────────────────────────────");
            log.warn("  Recommended remediation (choose one):");
            log.warn("    A) Double-click run-tests-as-admin.bat  (elevates once, runs full suite)");
            log.warn("    B) Double-click start-app.bat, click Yes → then mvn clean test");
            log.warn("    C) Open PowerShell as Administrator → mvn clean test");
            log.warn("  ─────────────────────────────────────────────────────");
        }

        log.info("═══════════════════════════════════════════════════════════");
    }

    /**
     * Validates privileges and throws {@link IllegalStateException} when
     * {@code requireAdminPrivileges=true} in config and the process is NOT elevated.
     *
     * <p>Set {@code requireAdminPrivileges=false} (default) on development machines
     * where the non-admin UIPI workarounds (LegacyIAccessiblePattern, Shell.Application)
     * are sufficient. Set {@code true} in CI/CD where the pipeline always runs elevated.
     */
    public static void validateAndFailIfRequired() {
        validate();
        boolean required = ConfigReader.getBoolean(ConfigReader.REQUIRE_ADMIN_PRIVILEGES, false);
        if (required && !isRunningAsAdmin()) {
            throw new IllegalStateException(
                    "requireAdminPrivileges=true but the JVM is not running as Administrator. " +
                    "Run via run-tests-as-admin.bat or open PowerShell as Administrator.");
        }
    }
}
