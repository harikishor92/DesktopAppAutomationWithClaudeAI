package com.pos.automation.util;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * One-time utility to generate the sample POSTestData.xlsx file.
 *
 * Run via: mvn exec:java -Dexec.mainClass="com.pos.automation.util.CreateSampleExcel"
 *
 * Creates src/test/resources/testdata/POSTestData.xlsx with three pre-populated sheets.
 */
public class CreateSampleExcel {

    public static void main(String[] args) throws IOException {
        String outputPath = "src/test/resources/testdata/POSTestData.xlsx";
        new File("src/test/resources/testdata").mkdirs();

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {

            CellStyle headerStyle = createHeaderStyle(workbook);

            // ── Sheet 1: Launch ──────────────────────────────────────────────────
            Sheet launch = workbook.createSheet("Launch");
            String[] launchHeaders = {
                "TestCaseId", "TestDescription", "Username", "Password",
                "ExpectedTitleFragment", "PopupTimeoutSeconds", "MainWindowTimeoutSeconds", "Execute",
                "Status", "Duration(ms)", "SystemName", "Timestamp"
            };
            createHeaderRow(launch, launchHeaders, headerStyle);
            createDataRow(launch, 1, new String[]{
                "TC_LAUNCH_001", "Verify AlbertaPOS launches and main window is visible",
                "111", "1111", "Alberta", "15", "60", "YES", "", "", "", ""
            });
            createDataRow(launch, 2, new String[]{
                "TC_LAUNCH_002", "Verify launch with extended timeout",
                "111", "1111", "Alberta", "20", "90", "NO", "", "", "", ""
            });
            autoSizeColumns(launch, launchHeaders.length);

            // ── Sheet 2: HomePage ────────────────────────────────────────────────
            Sheet homePage = workbook.createSheet("HomePage");
            String[] homeHeaders = {
                "TestCaseId", "TestDescription", "Username", "Password",
                "HomePageTitleFragment", "ExpectedButtonCountMin", "ExpectedNoErrorDialog", "Execute",
                "Status", "Duration(ms)", "SystemName", "Timestamp"
            };
            createHeaderRow(homePage, homeHeaders, headerStyle);
            createDataRow(homePage, 1, new String[]{
                "TC_HOME_001", "Verify Home Page displays after successful login",
                "111", "1111", "Home", "1", "TRUE", "YES", "", "", "", ""
            });
            createDataRow(homePage, 2, new String[]{
                "TC_HOME_002", "Verify Home Page element counts",
                "111", "1111", "Home", "5", "TRUE", "YES", "", "", "", ""
            });
            autoSizeColumns(homePage, homeHeaders.length);

            // ── Sheet 3: Transaction ─────────────────────────────────────────────
            Sheet transaction = workbook.createSheet("Transaction");
            String[] txnHeaders = {
                "TestCaseId", "TestDescription", "Username", "Password",
                "Barcode", "ExpectedItemInGrid", "PaymentMethod",
                "ExpectCashDiscountPopup", "ReceiptSaveFolder", "Execute",
                "Status", "Duration(ms)", "SystemName", "Timestamp"
            };
            createHeaderRow(transaction, txnHeaders, headerStyle);
            createDataRow(transaction, 1, new String[]{
                "TC_TXN_001", "Full cash transaction flow with receipt save",
                "111", "1111", "998877665501", "TRUE", "Cash", "TRUE",
                "", "YES", "", "", "", ""
            });
            createDataRow(transaction, 2, new String[]{
                "TC_TXN_002", "Cash transaction with regular price",
                "111", "1111", "998877665501", "TRUE", "Cash", "FALSE",
                "", "NO", "", "", "", ""
            });
            autoSizeColumns(transaction, txnHeaders.length);

            // ── Write to disk ────────────────────────────────────────────────────
            try (FileOutputStream fos = new FileOutputStream(outputPath)) {
                workbook.write(fos);
            }
            System.out.println("POSTestData.xlsx created at: " + new File(outputPath).getAbsolutePath());
        }
    }

    private static CellStyle createHeaderStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN);
        return style;
    }

    private static void createHeaderRow(Sheet sheet, String[] headers, CellStyle style) {
        Row row = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = row.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(style);
        }
    }

    private static void createDataRow(Sheet sheet, int rowIndex, String[] values) {
        Row row = sheet.createRow(rowIndex);
        for (int i = 0; i < values.length; i++) {
            row.createCell(i).setCellValue(values[i]);
        }
    }

    private static void autoSizeColumns(Sheet sheet, int count) {
        for (int i = 0; i < count; i++) {
            sheet.autoSizeColumn(i);
        }
    }
}
