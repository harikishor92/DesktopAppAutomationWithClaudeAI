package com.pos.automation.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class PropertyReader {

    private static final Logger log = LoggerFactory.getLogger(PropertyReader.class);
    private static final String CONFIG_FILE = "config.properties";

    private static final Properties props = new Properties();

    static {
        try (InputStream in = PropertyReader.class.getClassLoader().getResourceAsStream(CONFIG_FILE)) {
            if (in == null) {
                throw new RuntimeException("config.properties not found on classpath");
            }
            props.load(in);
            log.info("Loaded {}", CONFIG_FILE);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load " + CONFIG_FILE, e);
        }
    }

    private PropertyReader() {}

    public static String get(String key) {
        String value = props.getProperty(key);
        if (value == null) {
            throw new RuntimeException("Property '" + key + "' not found in " + CONFIG_FILE);
        }
        return value.trim();
    }

    public static String get(String key, String defaultValue) {
        return props.getProperty(key, defaultValue).trim();
    }

    public static int getInt(String key, int defaultValue) {
        try {
            return Integer.parseInt(get(key));
        } catch (Exception e) {
            return defaultValue;
        }
    }
}