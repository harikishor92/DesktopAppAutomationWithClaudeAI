package com.pos.automation.pages;

import org.openqa.selenium.By;
import org.openqa.selenium.Keys;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.FluentWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;

import java.io.File;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Page object for the AlbertaPOS transaction screen.
 *
 * Covers the full cash transaction flow:
 *   barcode search → cart validation → PAY → Cash → cash-discount popup → receipt save.
 *
 * Must be constructed with the main-window driver obtained from
 * {@link AlbertaPOSPage#attachToMainWindow()}, not the launcher (FrmLog) session
 * held by BaseTest.driver.
 *
 * Locator strategy:
 *   – Buttons use exact {@code @Name} matches confirmed from application screenshots.
 *   – The barcode field uses a primary {@code @AutomationId} with an ordered fallback
 *     chain (same pattern as LoginPage). Confirm the primary AutomationId via
 *     Accessibility Insights for Windows if the primary lookup fails at runtime.
 *   – The receipt save dialog targets the standard Windows SaveFileDialog filename
 *     field (AutomationId "1001"). A fallback scans all window handles for any Edit
 *     control if the dialog is a custom WinForms form.
 */
public class TransactionPage extends BasePage {

    private static final Logger log = LoggerFactory.getLogger(TransactionPage.class);

    private static final DateTimeFormatter RECEIPT_TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    // ── Barcode / Search field — AutomationId confirmed via WinAppDriver inspection ──
    // Same field as HomePage search bar (AutomationId='txtItemLookup', Name='Item Lookup')
    private static final By BARCODE_FIELD_PRIMARY = By.xpath("//*[@AutomationId='txtItemLookup']");

    // ── Transaction grid ──────────────────────────────────────────────────────
    // DataItem = UIAutomation ControlType.DataItem — the row type for WinForms DataGridView
    static final By TRANSACTION_GRID_ROWS = By.xpath("//DataItem");

    // ── Payment buttons — AutomationId confirmed via WinAppDriver inspection ──
    // btnCashpay = green PAY button (Cash Payment); btnCardpay = green CARD button
    static final By PAY_BUTTON  = By.xpath("//*[@AutomationId='btnCashpay']");
    static final By CARD_BUTTON = By.xpath("//*[@AutomationId='btnCardpay']");

    // ── Cash tender button in CustomPayment popup ────────────────────────────
    // After clicking PAY, a CustomPayment popup opens with 8 FloatingButtonControl tender buttons.
    // The developer has not set distinct AutomationIds per tender type — all share
    // AutomationId='btnDottedButton' and Name='Tender Button'. Cash is always the first button.
    static final By CASH_BUTTON = By.xpath("(//*[@AutomationId='btnDottedButton'])[1]");

    // ── Cash rounding / discount popup ───────────────────────────────────────
    // AlbertaPOS reuses the popupSessionRestore component for multiple popup types.
    // The cash rounding confirmation popup ("We've rounded your cash amount...") uses:
    //   btnYes = PROCEED (confirm cash payment)
    //   btnNo  = CANCEL (proceed with regular/card price)
    // Same AutomationIds as the session restore popup — confirmed via WinAppDriver inspection.
    static final By PROCEED_WITH_CASH_PRICE_BUTTON    = By.xpath("//*[@AutomationId='btnYes']");
    static final By PROCEED_WITH_REGULAR_PRICE_BUTTON = By.xpath("//*[@AutomationId='btnNo']");

    // ── Receipt save dialog ───────────────────────────────────────────────────
    // AutomationId "1001" is the filename Edit field in the standard Windows SaveFileDialog.
    // Fallback locator targets a WinForms Save button regardless of dialog type.
    private static final By SAVE_DIALOG_FILENAME_FIELD = By.xpath("//Edit[@AutomationId='1001']");
    private static final By SAVE_DIALOG_SAVE_BUTTON    = By.xpath("//Button[@Name='Save']");

    // ─────────────────────────────────────────────────────────────────────────

    public TransactionPage(RemoteWebDriver driver) {
        super(driver);
    }

    // ── Action methods ────────────────────────────────────────────────────────

    /**
     * Clears the barcode/search field, types the given barcode, and confirms with ENTER
     * to trigger the AlbertaPOS item lookup.
     *
     * @param barcode exact product barcode (e.g. "998877665501")
     */
    public void enterBarcode(String barcode) {
        log.info("Entering barcode: '{}'", barcode);
        WebElement field = resolveBarcodeField();
        field.clear();
        field.sendKeys(barcode);
        field.sendKeys(Keys.ENTER);
        log.info("Barcode '{}' submitted — awaiting item lookup", barcode);
    }

    /**
     * Clicks the PAY button to open the payment method selection screen.
     * Throws if the button is not found — indicates the transaction screen is not active
     * or no item has been added to the cart.
     */
    public void clickPay() {
        log.info("Clicking PAY button");
        WebElement btn = waitForElement(PAY_BUTTON, 10);
        if (btn == null) {
            throw new RuntimeException(
                    "PAY button not found — verify the transaction screen is active " +
                    "and at least one item has been added to the cart");
        }
        btn.click();
        log.info("PAY button clicked");
    }

    /**
     * Clicks the Cash payment button on the payment method selection screen.
     * Uses a FluentWait because the payment screen has a brief transition after PAY is clicked.
     */
    public void clickCash() {
        log.info("Clicking Cash payment button");
        WebElement btn = waitForElement(CASH_BUTTON, 10);
        if (btn == null) {
            throw new RuntimeException(
                    "Cash button not found — verify the payment method screen is displayed. " +
                    "Inspect the screen with Accessibility Insights and confirm @Name='Cash'.");
        }
        btn.click();
        log.info("Cash payment button clicked");
    }

    /**
     * Clicks the CARD payment button (AutomationId='btnCardpay') on the Home Page.
     * Used when PaymentMethod = "Card" in the Excel data row.
     */
    public void clickCard() {
        log.info("Clicking CARD payment button");
        WebElement btn = waitForElement(CARD_BUTTON, 10);
        if (btn == null) {
            throw new RuntimeException(
                    "CARD button (AutomationId='btnCardpay') not found on the transaction screen.");
        }
        btn.click();
        log.info("CARD payment button clicked");
    }

    /**
     * Handles the cash-discount popup that AlbertaPOS shows when Cash is selected and
     * a cash discount applies.  Clicks "PROCEED WITH CASH PRICE".
     *
     * Safe no-op when the popup does not appear (cash discount not applicable for this item
     * or this environment's configuration).
     */
    public void proceedWithCashPrice() {
        log.info("Waiting for cash discount popup (max 10s)");
        WebElement btn = waitForElement(PROCEED_WITH_CASH_PRICE_BUTTON, 10);
        if (btn == null) {
            log.info("Cash discount popup did not appear — proceeding without action");
            return;
        }
        btn.click();
        log.info("'PROCEED WITH CASH PRICE' clicked");
    }

    /**
     * Handles the receipt save dialog that appears after the cash transaction is processed.
     *
     * Generates a unique timestamp-based filename, types the full target path into the
     * dialog's filename field, and clicks Save.  Works for both the standard Windows
     * SaveFileDialog (AutomationId "1001") and custom WinForms save forms (fallback to
     * first Edit control discovered across all window handles).
     *
     * @param folderPath absolute path to the receipts directory
     * @return the base file path passed to the dialog (extension may be appended by the dialog)
     */
    public String saveReceipt(String folderPath) {
        ensureDirectoryExists(folderPath);
        String fileName = "Receipt_" + LocalDateTime.now().format(RECEIPT_TIMESTAMP_FMT);
        String fullPath = folderPath + File.separator + fileName;
        log.info("Saving receipt — target path: '{}'", fullPath);
        handleSaveDialog(fullPath);
        log.info("Receipt save dialog handled — base path: '{}'", fullPath);
        return fullPath;
    }

    // ── Verification methods ──────────────────────────────────────────────────

    /**
     * Waits for the transaction grid to contain at least one row after barcode entry.
     * Uses FluentWait to allow time for the AlbertaPOS item lookup to complete.
     *
     * @param timeoutSeconds maximum seconds to wait for a grid row to appear
     */
    public void verifyItemAddedToGrid(int timeoutSeconds) {
        log.info("Verifying transaction grid has at least one item row (timeout: {}s)", timeoutSeconds);
        List<WebElement> rows;
        try {
            rows = new FluentWait<>(driver)
                    .withTimeout(Duration.ofSeconds(timeoutSeconds))
                    .pollingEvery(Duration.ofMillis(500))
                    .ignoring(Exception.class)
                    .withMessage("Transaction grid contained no rows after " + timeoutSeconds +
                                 "s — barcode lookup may have failed or the item does not exist")
                    .until(d -> {
                        List<WebElement> r = findElements(TRANSACTION_GRID_ROWS);
                        return r.isEmpty() ? null : r;
                    });
        } catch (Exception e) {
            throw new RuntimeException(
                    "Transaction grid row verification failed: " + e.getMessage(), e);
        }
        log.info("Transaction grid confirmed — row count: {}", rows.size());
    }

    /**
     * Asserts that the PAY button is present on the transaction screen.
     * Called after {@link #verifyItemAddedToGrid} to confirm the cart is ready for checkout.
     */
    public void verifyPayButtonPresent() {
        log.info("Verifying PAY button is present on the transaction screen");
        WebElement btn = waitForElement(PAY_BUTTON, 10);
        Assert.assertNotNull(btn,
                "PAY button must be present on the transaction screen — " +
                "verify that an item has been added to the cart");
        log.info("PAY button confirmed present");
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Resolves the barcode/search input field via confirmed AutomationId='txtItemLookup'.
     * Throws if not found — indicates the home page is not active or the field is not visible.
     */
    private WebElement resolveBarcodeField() {
        WebElement field = findElement(BARCODE_FIELD_PRIMARY);
        if (field != null) {
            log.debug("Barcode field resolved (AutomationId='txtItemLookup')");
            return field;
        }
        throw new RuntimeException(
                "Barcode/search field (AutomationId='txtItemLookup') not found. " +
                "Verify the Home Page is active and the search bar is visible.");
    }

    /**
     * Handles the receipt save dialog.
     *
     * Two-path strategy:
     *   Path 1 — filename field is directly reachable in the current WinAppDriver session
     *            context (WinForms modal dialog launched from the same process window).
     *   Path 2 — filename field is in a separate window handle (standard Windows shell
     *            SaveFileDialog).  All current handles are scanned and the driver context
     *            is switched to the handle that contains the filename field.
     *
     * After Save is clicked the driver context is restored to the original handle so
     * subsequent test steps continue to operate on the main POS window.
     */
    private void handleSaveDialog(String fullFilePath) {
        String originalHandle = null;
        try {
            originalHandle = driver.getWindowHandle();
        } catch (Exception e) {
            log.debug("Could not capture current window handle: {}", e.getMessage());
        }

        // Path 1: standard modal child dialog — filename field reachable in current context
        WebElement filenameField = waitForElement(SAVE_DIALOG_FILENAME_FIELD, 10);

        if (filenameField == null) {
            // Path 2: Windows shell SaveFileDialog — appears as a new top-level window handle.
            // Thread.sleep is used here because the polling target is OS window-handle state,
            // not a Selenium-visible element, making FluentWait's ignoring() inapplicable.
            log.debug("Filename field not in current context — scanning window handles for save dialog");
            String dialogHandle = findHandleContainingFilenameField(10);

            if (dialogHandle != null) {
                driver.switchTo().window(dialogHandle);
                filenameField = waitForElement(SAVE_DIALOG_FILENAME_FIELD, 5);
                if (filenameField == null) {
                    // Fallback: first Edit control found in the dialog window
                    List<WebElement> edits = findElements(By.xpath("//Edit"));
                    if (!edits.isEmpty()) {
                        filenameField = edits.get(0);
                        log.debug("Using first Edit control as filename field fallback");
                    }
                }
            }
        }

        if (filenameField == null) {
            throw new RuntimeException(
                    "Receipt save dialog filename field not found after 10s. " +
                    "Inspect the dialog with Accessibility Insights for Windows and update " +
                    "SAVE_DIALOG_FILENAME_FIELD in TransactionPage with the correct AutomationId.");
        }

        log.info("Filename field found — typing path: '{}'", fullFilePath);
        filenameField.clear();
        filenameField.sendKeys(fullFilePath);

        WebElement saveBtn = waitForElement(SAVE_DIALOG_SAVE_BUTTON, 5);
        if (saveBtn == null) {
            throw new RuntimeException(
                    "Save button not found in receipt save dialog. " +
                    "Inspect the dialog with Accessibility Insights and confirm @Name='Save'.");
        }
        saveBtn.click();
        log.info("Save button clicked — receipt write initiated");

        // Restore driver context to the main POS window after the dialog closes
        if (originalHandle != null) {
            try {
                driver.switchTo().window(originalHandle);
                log.debug("Driver context restored to main window after save dialog");
            } catch (Exception e) {
                log.debug("Context restore skipped (dialog may have auto-focused main window): {}",
                        e.getMessage());
            }
        }
    }

    /**
     * Iterates all current window handles looking for one whose element tree contains
     * the standard SaveFileDialog filename field (AutomationId "1001").
     * Returns the matching handle, or {@code null} if not found within the timeout.
     *
     * Thread.sleep is intentional here: the wait target is OS-level window-handle
     * availability, not a Selenium element — FluentWait.ignoring() does not help here
     * because handle enumeration itself never throws.
     */
    private String findHandleContainingFilenameField(int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + (long) timeoutSeconds * 1_000;
        while (System.currentTimeMillis() < deadline) {
            for (String handle : driver.getWindowHandles()) {
                try {
                    driver.switchTo().window(handle);
                    if (findElement(SAVE_DIALOG_FILENAME_FIELD) != null) {
                        log.debug("Save dialog found on window handle: {}", handle);
                        return handle;
                    }
                } catch (Exception ignored) {}
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        log.debug("No window handle containing the filename field found within {}s", timeoutSeconds);
        return null;
    }

    /**
     * Polls for an element to become visible, returning {@code null} instead of throwing
     * when the timeout elapses.  Used for controls that are conditional (e.g. the cash
     * discount popup) or that appear after a brief UI transition.
     */
    private WebElement waitForElement(By locator, int timeoutSeconds) {
        try {
            return new FluentWait<>(driver)
                    .withTimeout(Duration.ofSeconds(timeoutSeconds))
                    .pollingEvery(Duration.ofMillis(500))
                    .ignoring(Exception.class)
                    .until(d -> findElement(locator));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Creates the target directory and all parent directories if they do not already exist.
     */
    private void ensureDirectoryExists(String path) {
        File dir = new File(path);
        if (!dir.exists()) {
            boolean created = dir.mkdirs();
            log.info("Receipts directory '{}' {}", path, created ? "created" : "creation failed — check permissions");
        } else {
            log.info("Receipts directory '{}' already exists", path);
        }
    }
}
