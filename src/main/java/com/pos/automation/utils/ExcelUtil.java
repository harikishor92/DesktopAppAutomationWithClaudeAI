package com.pos.automation.utils;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Excel data utility using Apache POI for data-driven test support.
 *
 * <p>Usage in a TestNG {@code @DataProvider}:
 * <pre>
 *   {@literal @}DataProvider(name = "testData")
 *   public static Object[][] provideTestData() {
 *       ExcelUtil excel = new ExcelUtil();
 *       TestDataSheet sheet = MyTest.class.getAnnotation(TestDataSheet.class);
 *       return excel.getDataAsObjectArray(sheet.sheetName());
 *   }
 * </pre>
 *
 * <p>Sheet structure:
 * <ul>
 *   <li>Row 0 — header row (column names)</li>
 *   <li>Rows 1+ — data rows; each row returned as {@code HashMap<String, String>}</li>
 *   <li>Rows where {@code Execute} column is "NO" (case-insensitive) are skipped</li>
 *   <li>Null/blank cells are stored as {@code ""}</li>
 * </ul>
 *
 * <p>After test execution, call {@link #updateRowResult} to write Pass/Fail status
 * back to the sheet.
 */
public class ExcelUtil {

    private static final Logger log = LoggerFactory.getLogger(ExcelUtil.class);
    private static final DataFormatter DATA_FORMATTER = new DataFormatter();

    private static final String COL_STATUS    = "Status";
    private static final String COL_DURATION  = "Duration(ms)";
    private static final String COL_SYSTEM    = "SystemName";
    private static final String COL_TIMESTAMP = "Timestamp";
    private static final String COL_EXECUTE   = "Execute";

    private final String filePath;
    private XSSFWorkbook workbook;

    /** Reads the Excel file path from {@link ConfigReader#EXCEL_FILE_PATH}. */
    public ExcelUtil() {
        this(ConfigReader.getString(ConfigReader.EXCEL_FILE_PATH));
    }

    /** Explicit file path override — useful for unit tests. */
    public ExcelUtil(String filePath) {
        this.filePath = filePath;
        openWorkbook();
    }

    // ── Public API ───────────────────────────────────────────────────────────────

    /**
     * Reads all data rows from {@code sheetName}.
     * Row 0 is treated as the header; data starts at row 1.
     * Rows where the {@code Execute} column equals "NO" (case-insensitive) are omitted.
     *
     * @return list of row maps; each map is {@code {columnHeader -> cellValue}}
     */
    public List<HashMap<String, String>> readSheetData(String sheetName) {
        Sheet sheet = getSheet(sheetName);
        List<HashMap<String, String>> data = new ArrayList<>();

        Row headerRow = sheet.getRow(0);
        if (headerRow == null) {
            log.warn("Sheet '{}' has no header row — returning empty data", sheetName);
            return data;
        }

        List<String> headers = new ArrayList<>();
        for (int c = 0; c < headerRow.getLastCellNum(); c++) {
            Cell cell = headerRow.getCell(c);
            headers.add(cell != null ? getCellValueAsString(cell).trim() : "");
        }

        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;

            HashMap<String, String> rowMap = new HashMap<>();
            for (int c = 0; c < headers.size(); c++) {
                Cell cell = row.getCell(c);
                String value = (cell != null) ? getCellValueAsString(cell).trim() : "";
                rowMap.put(headers.get(c), value);
            }

            String execute = rowMap.getOrDefault(COL_EXECUTE, "YES");
            if ("NO".equalsIgnoreCase(execute)) {
                log.debug("Sheet '{}' row {} skipped (Execute=NO)", sheetName, r + 1);
                continue;
            }

            data.add(rowMap);
        }

        log.info("Sheet '{}' — {} data row(s) loaded (excluding Execute=NO rows)", sheetName, data.size());
        return data;
    }

    /**
     * Converts sheet data to the {@code Object[][]} format expected by TestNG {@code @DataProvider}.
     * Each {@code Object[]} contains a single {@code HashMap<String, String>} row.
     */
    public Object[][] getDataAsObjectArray(String sheetName) {
        List<HashMap<String, String>> rows = readSheetData(sheetName);
        Object[][] result = new Object[rows.size()][1];
        for (int i = 0; i < rows.size(); i++) {
            result[i][0] = rows.get(i);
        }
        return result;
    }

    /**
     * Writes test execution result columns back to the specified row.
     *
     * <p>The result columns ({@code Status}, {@code Duration(ms)}, {@code SystemName},
     * {@code Timestamp}) are created in the header row if they do not already exist.
     * The workbook is saved back to the original file after each call.
     *
     * @param sheetName  target sheet
     * @param rowIndex   1-based data row index (row 1 = first data row after header)
     * @param status     "PASSED", "FAILED", or "SKIPPED"
     * @param durationMs test duration in milliseconds
     * @param systemName host name from {@code InetAddress.getLocalHost().getHostName()}
     * @param timestamp  ISO-format execution timestamp
     */
    public void updateRowResult(String sheetName, int rowIndex,
                                String status, long durationMs,
                                String systemName, String timestamp) {
        try {
            Sheet sheet = getSheet(sheetName);
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) return;

            int statusCol    = findOrCreateColumn(headerRow, COL_STATUS);
            int durationCol  = findOrCreateColumn(headerRow, COL_DURATION);
            int systemCol    = findOrCreateColumn(headerRow, COL_SYSTEM);
            int timestampCol = findOrCreateColumn(headerRow, COL_TIMESTAMP);

            Row dataRow = sheet.getRow(rowIndex);
            if (dataRow == null) dataRow = sheet.createRow(rowIndex);

            dataRow.createCell(statusCol).setCellValue(status);
            dataRow.createCell(durationCol).setCellValue(durationMs);
            dataRow.createCell(systemCol).setCellValue(systemName);
            dataRow.createCell(timestampCol).setCellValue(timestamp);

            saveWorkbook();
            log.info("Excel result updated — sheet='{}' row={} status={}", sheetName, rowIndex + 1, status);
        } catch (Exception e) {
            log.warn("Failed to update Excel result for sheet='{}' row={}: {}", sheetName, rowIndex, e.getMessage());
        }
    }

    /**
     * Writes a single cell value to the specified column in the given row.
     * Creates the column header automatically if it does not already exist.
     * Uses the same row-index convention as {@link #updateRowResult}.
     *
     * @param sheetName  target sheet name
     * @param rowIndex   POI row index (0-based; row 0 = header, row 1 = first data row)
     * @param columnName column header name to find or create
     * @param value      string value to write into the cell
     */
    public void updateCellValue(String sheetName, int rowIndex, String columnName, String value) {
        try {
            Sheet sheet    = getSheet(sheetName);
            Row headerRow  = sheet.getRow(0);
            if (headerRow == null) return;

            int colIndex = findOrCreateColumn(headerRow, columnName);

            Row dataRow = sheet.getRow(rowIndex);
            if (dataRow == null) dataRow = sheet.createRow(rowIndex);

            Cell cell = dataRow.getCell(colIndex);
            if (cell == null) cell = dataRow.createCell(colIndex);
            cell.setCellValue(value);

            saveWorkbook();
            log.info("Cell updated — sheet='{}' row={} column='{}' value='{}'",
                    sheetName, rowIndex + 1, columnName, value);
        } catch (Exception e) {
            log.warn("Failed to update cell '{}' in '{}' row {}: {}",
                    columnName, sheetName, rowIndex, e.getMessage());
        }
    }

    /** Closes the workbook and releases file resources. */
    public void close() {
        if (workbook != null) {
            try {
                workbook.close();
                workbook = null;
                log.debug("ExcelUtil workbook closed");
            } catch (IOException e) {
                log.warn("Workbook close failed: {}", e.getMessage());
            }
        }
    }

    // ── Private Helpers ──────────────────────────────────────────────────────────

    private void openWorkbook() {
        try (FileInputStream fis = new FileInputStream(filePath)) {
            workbook = new XSSFWorkbook(fis);
            log.info("Excel workbook opened: {}", filePath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to open Excel file: " + filePath, e);
        }
    }

    private Sheet getSheet(String sheetName) {
        Sheet sheet = workbook.getSheet(sheetName);
        if (sheet == null) {
            throw new RuntimeException(
                    "Sheet '" + sheetName + "' not found in workbook '" + filePath +
                    "'. Available sheets: " + availableSheets());
        }
        return sheet;
    }

    private String availableSheets() {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            names.add(workbook.getSheetName(i));
        }
        return names.toString();
    }

    private String getCellValueAsString(Cell cell) {
        if (cell == null) return "";
        return DATA_FORMATTER.formatCellValue(cell).trim();
    }

    private int findOrCreateColumn(Row headerRow, String columnName) {
        for (int c = 0; c < headerRow.getLastCellNum(); c++) {
            Cell cell = headerRow.getCell(c);
            if (cell != null && columnName.equalsIgnoreCase(getCellValueAsString(cell))) {
                return c;
            }
        }
        int newIndex = headerRow.getLastCellNum();
        headerRow.createCell(newIndex).setCellValue(columnName);
        return newIndex;
    }

    private void saveWorkbook() {
        try (FileOutputStream fos = new FileOutputStream(filePath)) {
            workbook.write(fos);
        } catch (IOException e) {
            log.warn("Failed to save workbook to '{}': {}", filePath, e.getMessage());
        }
    }
}
