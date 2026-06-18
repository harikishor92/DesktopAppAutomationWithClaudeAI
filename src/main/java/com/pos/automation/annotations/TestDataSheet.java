package com.pos.automation.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a test class with the Excel sheet name that provides its test data.
 *
 * Usage:
 * <pre>
 *   {@literal @}TestDataSheet(sheetName = "Login")
 *   public class LoginTest extends BaseTest { ... }
 * </pre>
 *
 * The sheet name is read at runtime by {@code ExcelUtil.getDataAsObjectArray()} via
 * the TestNG {@code @DataProvider} method in each test class.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface TestDataSheet {
    String sheetName();
}
