package de.in.mp.ui.finance;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.textfield.BigDecimalField;
import com.vaadin.flow.component.textfield.TextField;

import de.in.mp.finance.PortfolioPosition;
import de.in.mp.finance.PortfolioPositionService;

/**
 * Compact editor dialog for creating or updating a single
 * {@link PortfolioPosition} inside an {@code INVESTMENT} depot.
 */
class PortfolioPositionEditorDialog extends Dialog {

    private static final String DIALOG_WIDTH = "520px";
    private static final int NOTIFICATION_DURATION_MS = 3000;
    private static final List<String> SUPPORTED_CURRENCIES = List.of("EUR", "USD", "CHF", "GBP", "JPY", "CAD");

    private final PortfolioPositionService positionService;

    private final TextField nameField = new TextField();
    private final TextField isinField = new TextField();
    private final TextField tickerField = new TextField();
    private final Button lookupTickerButton = new Button();
    private final ComboBox<String> currencyField = new ComboBox<>();
    private final BigDecimalField quantityField = new BigDecimalField();
    private final BigDecimalField buyInPriceField = new BigDecimalField();
    private final Checkbox manualOverrideCheckbox = new Checkbox();
    private final BigDecimalField lastPriceField = new BigDecimalField();
    private final TextField notesField = new TextField();

    private UUID accountId;
    private PortfolioPosition editingPosition;
    private Runnable onSaveCallback;

    PortfolioPositionEditorDialog(PortfolioPositionService positionService) {
        this.positionService = positionService;
        setWidth(DIALOG_WIDTH);
        configureFields();
        buildLayout();
        buildFooter();
    }

    void openForCreate(UUID accountId, Runnable onSave) {
        this.accountId = accountId;
        this.editingPosition = new PortfolioPosition();
        this.editingPosition.setAccountId(accountId);
        this.onSaveCallback = onSave;
        setHeaderTitle(getTranslation("finance.positions.editor.title.new"));
        populateFields(editingPosition);
        open();
    }

    void openForEdit(PortfolioPosition position, Runnable onSave) {
        this.accountId = position.getAccountId();
        this.editingPosition = position;
        this.onSaveCallback = onSave;
        setHeaderTitle(getTranslation("finance.positions.editor.title.edit"));
        populateFields(position);
        open();
    }

    private void configureFields() {
        nameField.setLabel(getTranslation("finance.positions.editor.field.name"));
        nameField.setRequiredIndicatorVisible(true);
        nameField.setWidthFull();

        isinField.setLabel(getTranslation("finance.positions.editor.field.isin"));
        isinField.setWidthFull();

        tickerField.setLabel(getTranslation("finance.positions.editor.field.ticker"));
        tickerField.setWidthFull();

        lookupTickerButton.setText(getTranslation("finance.positions.editor.button.lookup-ticker"));
        lookupTickerButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        lookupTickerButton.addClickListener(event -> lookupTicker());

        currencyField.setLabel(getTranslation("finance.positions.editor.field.currency"));
        currencyField.setItems(SUPPORTED_CURRENCIES);
        currencyField.setAllowCustomValue(true);
        currencyField.addCustomValueSetListener(event -> currencyField.setValue(event.getDetail().toUpperCase()));
        currencyField.setWidthFull();

        quantityField.setLabel(getTranslation("finance.positions.editor.field.quantity"));
        quantityField.setRequiredIndicatorVisible(true);
        quantityField.setWidthFull();

        buyInPriceField.setLabel(getTranslation("finance.positions.editor.field.buy-in"));
        buyInPriceField.setWidthFull();

        manualOverrideCheckbox.setLabel(getTranslation("finance.positions.editor.field.manual-override"));

        lastPriceField.setLabel(getTranslation("finance.positions.editor.field.last-price"));
        lastPriceField.setWidthFull();

        notesField.setLabel(getTranslation("finance.positions.editor.field.notes"));
        notesField.setWidthFull();
    }

    private void buildLayout() {
        HorizontalLayout isinRow = new HorizontalLayout(isinField, lookupTickerButton);
        isinRow.setWidthFull();
        isinRow.setDefaultVerticalComponentAlignment(Alignment.END);
        isinRow.expand(isinField);

        FormLayout form = new FormLayout(
                nameField,
                isinRow,
                tickerField,
                currencyField,
                quantityField,
                buyInPriceField,
                manualOverrideCheckbox,
                lastPriceField,
                notesField);
        form.setColspan(nameField, 2);
        form.setColspan(isinRow, 2);
        form.setColspan(manualOverrideCheckbox, 2);
        form.setColspan(notesField, 2);
        add(form);
    }

    private void buildFooter() {
        Button cancelButton = new Button(getTranslation("ui.button.cancel"), event -> close());
        Button saveButton = new Button(getTranslation("ui.button.save"), event -> save());
        saveButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        getFooter().add(cancelButton, saveButton);
    }

    private void populateFields(PortfolioPosition position) {
        nameField.setErrorMessage(null);
        nameField.setInvalid(false);
        quantityField.setErrorMessage(null);
        quantityField.setInvalid(false);

        nameField.setValue(position.getName() != null ? position.getName() : "");
        isinField.setValue(position.getIsin() != null ? position.getIsin() : "");
        tickerField.setValue(position.getTicker() != null ? position.getTicker() : "");
        currencyField.setValue(position.getCurrency() != null
                ? position.getCurrency()
                : PortfolioPosition.DEFAULT_CURRENCY);
        quantityField.setValue(position.getQuantity() != null ? position.getQuantity() : BigDecimal.ONE);
        buyInPriceField.setValue(position.getBuyInPrice());
        manualOverrideCheckbox.setValue(position.isManualPriceOverride());
        lastPriceField.setValue(position.getLastPrice());
        notesField.setValue(position.getNotes() != null ? position.getNotes() : "");
    }

    private void lookupTicker() {
        String isin = isinField.getValue();
        if (isin == null || isin.isBlank()) {
            return;
        }
        positionService.lookupTickerByIsin(isin).ifPresentOrElse(
                symbol -> {
                    tickerField.setValue(symbol);
                    Notification.show(getTranslation("finance.positions.notification.ticker-found", symbol),
                            NOTIFICATION_DURATION_MS, Notification.Position.BOTTOM_START);
                },
                () -> Notification.show(getTranslation("finance.positions.notification.ticker-not-found"),
                        NOTIFICATION_DURATION_MS, Notification.Position.BOTTOM_START));
    }

    private void save() {
        String name = nameField.getValue() == null ? "" : nameField.getValue().trim();
        if (name.isEmpty()) {
            nameField.setErrorMessage(getTranslation("finance.positions.error.name-required"));
            nameField.setInvalid(true);
            return;
        }
        BigDecimal quantity = quantityField.getValue();
        if (quantity == null || quantity.signum() < 0) {
            quantityField.setErrorMessage(getTranslation("finance.positions.error.quantity-required"));
            quantityField.setInvalid(true);
            return;
        }

        editingPosition.setAccountId(accountId);
        editingPosition.setName(name);
        editingPosition.setIsin(isinField.getValue());
        editingPosition.setTicker(tickerField.getValue());
        editingPosition.setCurrency(currencyField.getValue());
        editingPosition.setQuantity(quantity);
        editingPosition.setBuyInPrice(buyInPriceField.getValue());
        editingPosition.setManualPriceOverride(Boolean.TRUE.equals(manualOverrideCheckbox.getValue()));
        editingPosition.setLastPrice(lastPriceField.getValue());
        if (editingPosition.isManualPriceOverride()) {
            // Force recalculation of lastPriceEur when user edits manual price
            editingPosition.setLastPriceEur(null);
        }
        editingPosition.setNotes(notesField.getValue());

        positionService.save(editingPosition);
        Notification.show(getTranslation("finance.positions.notification.saved"),
                NOTIFICATION_DURATION_MS, Notification.Position.BOTTOM_START);
        close();
        if (onSaveCallback != null) {
            onSaveCallback.run();
        }
    }
}
