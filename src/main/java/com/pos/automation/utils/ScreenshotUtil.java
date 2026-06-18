package com.pos.automation.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Captures full-desktop screenshots using java.awt.Robot.
 *
 * WinAppDriver does not support the Selenium TakesScreenshot interface for desktop apps,
 * so Robot is used to grab the entire screen. Screenshots are saved to target/screenshots/
 * and are primarily useful for failure diagnosis in CI or local debugging.
 */
public final class ScreenshotUtil {

    private static final Logger log = LoggerFactory.getLogger(ScreenshotUtil.class);
    private static final String SCREENSHOT_DIR = "target/screenshots";
    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");

    private ScreenshotUtil() {}

    /**
     * Takes a full-desktop screenshot and saves it as a PNG file.
     *
     * @param label descriptive label embedded in the file name (e.g. test method name)
     * @return absolute path of the saved file, or {@code null} if capture failed
     */
    public static String capture(String label) {
        try {
            File dir = new File(SCREENSHOT_DIR);
            if (!dir.exists()) dir.mkdirs();

            String timestamp = LocalDateTime.now().format(TIMESTAMP_FMT);
            // Sanitise label so it is safe as a filename component
            String safeName = label.replaceAll("[^a-zA-Z0-9_\\-]", "_");
            File outputFile = new File(dir, safeName + "_" + timestamp + ".png");

            Robot robot = new Robot();
            Rectangle screenBounds = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
            BufferedImage image = robot.createScreenCapture(screenBounds);
            ImageIO.write(image, "png", outputFile);

            log.info("Screenshot captured: {}", outputFile.getAbsolutePath());
            return outputFile.getAbsolutePath();
        } catch (Exception e) {
            log.warn("Screenshot capture failed: {}", e.getMessage());
            return null;
        }
    }
}