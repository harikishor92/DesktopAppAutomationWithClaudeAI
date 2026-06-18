package com.pos.automation.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Utility for executing OS-level processes from Java.
 *
 * Uses PowerShell -EncodedCommand (UTF-16 LE Base64) instead of -Command so that
 * the script string is never subject to Windows command-line quoting rules.
 * This prevents the shell from mangling double-quotes inside C# DllImport attributes,
 * here-strings, or any other script content that contains double-quote characters.
 */
public final class ProcessUtil {

    private static final Logger log = LoggerFactory.getLogger(ProcessUtil.class);

    private ProcessUtil() {}

    /**
     * Executes a PowerShell script and returns trimmed stdout.
     * stderr is merged into stdout via {@code redirectErrorStream(true)}.
     * Returns an empty string on any error — never throws.
     *
     * The script is encoded as UTF-16 LE Base64 and passed via -EncodedCommand,
     * which completely bypasses Windows command-line argument quoting and prevents
     * double-quote mangling in C# inline type definitions or here-strings.
     *
     * @param script PowerShell script to execute
     * @return trimmed stdout, or empty string on failure
     */
    public static String runPowerShell(String script) {
        try {
            // Suppress PowerShell progress stream — prevents CLIXML progress records
            // (#< CLIXML ... <Obj S="progress"...>) from appearing in stdout and
            // corrupting single-word result strings like "done" or "clicked".
            String fullScript = "$ProgressPreference = 'SilentlyContinue'; " + script;
            byte[] utf16le = fullScript.getBytes(StandardCharsets.UTF_16LE);
            String encoded = Base64.getEncoder().encodeToString(utf16le);
            Process proc = new ProcessBuilder(
                    "powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
                    .redirectErrorStream(true)
                    .start();
            String output = new String(proc.getInputStream().readAllBytes()).trim();
            proc.waitFor();
            return output;
        } catch (Exception e) {
            log.debug("PowerShell execution failed: {}", e.getMessage());
            return "";
        }
    }
}
