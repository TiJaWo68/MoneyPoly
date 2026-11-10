package de.in.mp.ui.energy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.FlexComponent.JustifyContentMode;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.BigDecimalField;
import com.vaadin.flow.component.upload.Upload;

import de.in.mp.energy.EnergyOdsImportService;
import de.in.mp.energy.EnergyOdsImportService.OdsAssetCostDefaults;
import de.in.mp.energy.EnergyOdsImportService.OdsImportResult;

/**
 * Modal dialog for uploading and importing an OpenDocument Spreadsheet
 * ({@code .ods}, such as {@code Zählerstände Abrechnungen TBS10.ods}) into the
 * Energy & Meters module.
 */
public class EnergyOdsImportDialog extends Dialog {

    private static final BigDecimal DEFAULT_PV_DACH_COST = new BigDecimal("12000.00");
    private static final BigDecimal DEFAULT_PV_GARTENHAUS_COST = new BigDecimal("1500.00");
    private static final BigDecimal DEFAULT_WALLBOX_COST = new BigDecimal("1200.00");

    private final EnergyOdsImportService importService;

    private final BigDecimalField pvDachCost = new BigDecimalField();
    private final BigDecimalField pvGartenhausCost = new BigDecimalField();
    private final BigDecimalField wallboxCost = new BigDecimalField();
    private final Upload upload;
    private final Button runImportButton = new Button();
    private final Button cancelButton = new Button();

    private ByteArrayOutputStream uploadedBuffer;
    private Runnable onImported;

    public EnergyOdsImportDialog(EnergyOdsImportService importService) {
        this.importService = importService;

        setHeaderTitle(getTranslation("energy.import.title"));
        setWidth("580px");

        Paragraph description = new Paragraph(getTranslation("energy.import.description"));

        pvDachCost.setLabel(getTranslation("energy.import.field.pv-dach-cost"));
        pvGartenhausCost.setLabel(getTranslation("energy.import.field.pv-gartenhaus-cost"));
        wallboxCost.setLabel(getTranslation("energy.import.field.wallbox-cost"));

        FormLayout costsForm = new FormLayout(pvDachCost, pvGartenhausCost, wallboxCost);
        costsForm.setResponsiveSteps(new FormLayout.ResponsiveStep("0", 1));

        upload = new Upload(this::receiveUpload);
        upload.setAcceptedFileTypes(".ods", "application/vnd.oasis.opendocument.spreadsheet");
        upload.setMaxFiles(1);
        upload.setUploadButton(new Button(getTranslation("energy.import.upload.button")));
        upload.setDropLabel(new Span(getTranslation("energy.import.upload.drop")));
        upload.setWidthFull();
        upload.addSucceededListener(event -> runImportButton.setEnabled(true));

        runImportButton.setText(getTranslation("energy.import.button.run"));
        runImportButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        runImportButton.addClickListener(event -> executeImport());

        cancelButton.setText(getTranslation("energy.button.cancel"));
        cancelButton.addClickListener(event -> close());

        HorizontalLayout actions = new HorizontalLayout(cancelButton, runImportButton);
        actions.setWidthFull();
        actions.setJustifyContentMode(JustifyContentMode.END);

        VerticalLayout layout = new VerticalLayout(description, costsForm, upload, actions);
        layout.setPadding(false);
        add(layout);
        setCloseOnEsc(true);
        setCloseOnOutsideClick(false);
    }

    public void openForImport(Runnable onImported) {
        this.onImported = onImported;
        this.uploadedBuffer = null;
        this.pvDachCost.setValue(DEFAULT_PV_DACH_COST);
        this.pvGartenhausCost.setValue(DEFAULT_PV_GARTENHAUS_COST);
        this.wallboxCost.setValue(DEFAULT_WALLBOX_COST);
        this.upload.clearFileList();
        this.runImportButton.setEnabled(false);
        open();
    }

    private ByteArrayOutputStream receiveUpload(String fileName, String mimeType) {
        this.uploadedBuffer = new ByteArrayOutputStream();
        return this.uploadedBuffer;
    }

    private void executeImport() {
        if (uploadedBuffer == null || uploadedBuffer.size() == 0) {
            Notification.show(getTranslation("energy.import.error.no-file"),
                    4000, Notification.Position.MIDDLE);
            return;
        }

        try {
            OdsAssetCostDefaults costDefaults = new OdsAssetCostDefaults(
                    pvDachCost.getValue(),
                    pvGartenhausCost.getValue(),
                    wallboxCost.getValue());

            OdsImportResult result = importService.importOds(
                    new ByteArrayInputStream(uploadedBuffer.toByteArray()),
                    costDefaults);

            Notification notification = Notification.show(
                    getTranslation("energy.import.success",
                            result.readingDatesImported(),
                            result.totalReadingsSaved(),
                            result.metersConfigured(),
                            result.assetsConfigured()),
                    5000,
                    Notification.Position.BOTTOM_START);
            notification.addThemeVariants(NotificationVariant.LUMO_SUCCESS);

            close();
            if (onImported != null) {
                onImported.run();
            }
        } catch (RuntimeException exception) {
            Notification.show(getTranslation("energy.notification.error", exception.getMessage()),
                    6000, Notification.Position.MIDDLE);
        }
    }
}

