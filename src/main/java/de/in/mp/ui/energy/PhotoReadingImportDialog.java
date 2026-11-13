package de.in.mp.ui.energy;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.vaadin.flow.component.ClientCallable;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.FlexComponent.JustifyContentMode;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.BigDecimalField;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.theme.lumo.LumoUtility;

import de.in.mp.admin.AppSettingService;
import de.in.mp.energy.Meter;
import de.in.mp.energy.MeterReading;
import de.in.mp.energy.MeterReadingService;
import de.in.mp.energy.MeterService;
import de.in.mp.energy.vision.ExifDateExtractor;
import de.in.mp.energy.vision.ExtractedMeterReading;
import de.in.mp.energy.vision.VisionConfig;
import de.in.mp.energy.vision.VisionMeterClient;
import de.in.mp.ui.GridActionButton;

/**
 * Multi-photo upload and human-in-the-loop review dialog for AI-extracted
 * meter readings. Users can inspect thumbnails, source quotes, adjust matched
 * meters, dates or values (including delta-to-cumulative addition), and save
 * all confirmed readings in one step.
 */
@SuppressWarnings("removal")
public class PhotoReadingImportDialog extends Dialog {

    private static final int MAX_FILES = 20;
    private static final int HINT_MAX_LENGTH = 255;
    private static final double HIGH_CONFIDENCE_THRESHOLD = 0.85;

    private static final String PASTE_LISTENER_INSTALL_JS = """
            const host = $0;
            if (host._mpPasteHandler) {
                return;
            }
            host._mpPasteHandler = (e) => {
                if (!host.opened) return;
                const dt = e.clipboardData;
                if (!dt) return;
                const files = [];
                if (dt.items) {
                    for (const item of dt.items) {
                        if (item.kind === 'file' && item.type && item.type.startsWith('image/')) {
                            const f = item.getAsFile();
                            if (f) files.push(f);
                        }
                    }
                }
                if (files.length === 0 && dt.files) {
                    for (const f of dt.files) {
                        if (f.type && f.type.startsWith('image/')) files.push(f);
                    }
                }
                if (files.length === 0) return;
                e.preventDefault();
                let idx = 0;
                for (const file of files) {
                    const reader = new FileReader();
                    const ext = (file.type && file.type.includes('/')) ? file.type.split('/')[1] : 'png';
                    const fileName = (file.name && file.name !== 'image.png')
                        ? file.name
                        : ('clipboard-' + Date.now() + '-' + (++idx) + '.' + ext);
                    const mimeType = file.type || 'image/png';
                    reader.onload = () => {
                        const result = reader.result || '';
                        const comma = result.indexOf(',');
                        if (comma >= 0) {
                            host.$server.handlePastedImage(fileName, mimeType, result.substring(comma + 1));
                        }
                    };
                    reader.readAsDataURL(file);
                }
            };
            document.addEventListener('paste', host._mpPasteHandler);
            """;

    private static final String PASTE_LISTENER_REMOVE_JS = """
            const host = $0;
            if (host._mpPasteHandler) {
                document.removeEventListener('paste', host._mpPasteHandler);
                delete host._mpPasteHandler;
            }
            """;

    private static final String CLIPBOARD_READ_JS = """
            const host = $0;
            (async () => {
                if (!navigator.clipboard || !navigator.clipboard.read) {
                    host.$server.notifyPasteStatus('unsupported');
                    return;
                }
                try {
                    const items = await navigator.clipboard.read();
                    let found = false;
                    let idx = 0;
                    for (const item of items) {
                        const imageType = item.types.find(t => t.startsWith('image/'));
                        if (!imageType) continue;
                        found = true;
                        const blob = await item.getType(imageType);
                        const reader = new FileReader();
                        const ext = imageType.includes('/') ? imageType.split('/')[1] : 'png';
                        const fileName = 'clipboard-' + Date.now() + '-' + (++idx) + '.' + ext;
                        reader.onload = () => {
                            const result = reader.result || '';
                            const comma = result.indexOf(',');
                            if (comma >= 0) {
                                host.$server.handlePastedImage(fileName, imageType, result.substring(comma + 1));
                            }
                        };
                        reader.readAsDataURL(blob);
                    }
                    if (!found) {
                        host.$server.notifyPasteStatus('empty');
                    }
                } catch (err) {
                    host.$server.notifyPasteStatus('unsupported');
                }
            })();
            """;

    private final MeterService meterService;
    private final MeterReadingService readingService;
    private final AppSettingService appSettingService;
    private final VisionMeterClient visionMeterClient;

    private final Upload upload;
    private final Grid<ReviewRow> grid = new Grid<>();
    private final Checkbox persistHints = new Checkbox();
    private final Button saveButton = new Button();

    private final Map<String, ByteArrayOutputStream> buffers = new HashMap<>();
    private final List<ReviewRow> rows = new ArrayList<>();

    private List<Meter> activeMeters = List.of();
    private Map<UUID, Meter> metersById = Map.of();
    private Map<UUID, BigDecimal> latestValuesByMeter = Map.of();
    private Map<UUID, List<MeterReading>> timelinesByMeter = Map.of();
    private Runnable onSaved;

    public PhotoReadingImportDialog(
            MeterService meterService,
            MeterReadingService readingService,
            AppSettingService appSettingService,
            VisionMeterClient visionMeterClient) {
        this.meterService = meterService;
        this.readingService = readingService;
        this.appSettingService = appSettingService;
        this.visionMeterClient = visionMeterClient;

        setHeaderTitle(getTranslation("energy.photo.title"));
        setWidth("1320px");
        setMaxWidth("96vw");

        upload = new Upload(this::receiveUpload);
        upload.setAcceptedFileTypes(".jpg", ".jpeg", ".png", ".webp", "image/jpeg", "image/png", "image/webp");
        upload.setMaxFiles(MAX_FILES);
        upload.setUploadButton(new Button(getTranslation("energy.photo.upload.button")));
        upload.setDropLabel(new Span(getTranslation("energy.photo.upload.drop")));
        upload.setWidthFull();
        upload.addSucceededListener(event -> analyze(event.getFileName(), event.getMIMEType()));

        configureGrid();

        persistHints.setLabel(getTranslation("energy.photo.persist-hints"));
        persistHints.setValue(true);

        saveButton.setText(getTranslation("energy.photo.button.save"));
        saveButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        saveButton.addClickListener(event -> requestSave());
        Button cancelButton = new Button(getTranslation("energy.button.cancel"), event -> close());

        HorizontalLayout footer = new HorizontalLayout(persistHints, new HorizontalLayout(cancelButton, saveButton));
        footer.setWidthFull();
        footer.setDefaultVerticalComponentAlignment(Alignment.CENTER);
        footer.expand(persistHints);
        footer.setJustifyContentMode(JustifyContentMode.BETWEEN);

        Button pasteButton = new Button(getTranslation("energy.photo.paste.button"),
                VaadinIcon.CLIPBOARD_TEXT.create(),
                event -> getElement().executeJs(CLIPBOARD_READ_JS, getElement()));
        Paragraph description = new Paragraph(getTranslation("energy.photo.description"));
        HorizontalLayout introBar = new HorizontalLayout(description, pasteButton);
        introBar.setWidthFull();
        introBar.setDefaultVerticalComponentAlignment(Alignment.CENTER);
        introBar.expand(description);

        VerticalLayout layout = new VerticalLayout(introBar, upload, grid, footer);
        layout.setPadding(false);
        layout.setSpacing(true);
        layout.setSizeFull();
        add(layout);
        setCloseOnEsc(true);
        setCloseOnOutsideClick(false);

        addOpenedChangeListener(event -> getElement().executeJs(
                event.isOpened() ? PASTE_LISTENER_INSTALL_JS : PASTE_LISTENER_REMOVE_JS,
                getElement()));
        addDetachListener(event -> getElement().executeJs(PASTE_LISTENER_REMOVE_JS, getElement()));
    }

    public void openForImport(Runnable onSaved) {
        this.onSaved = onSaved;
        this.rows.clear();
        this.buffers.clear();
        this.upload.clearFileList();
        reloadCatalog();
        refreshGrid();
        open();
    }

    private void reloadCatalog() {
        this.activeMeters = meterService.findActive();
        this.metersById = new HashMap<>();
        this.latestValuesByMeter = new HashMap<>();
        this.timelinesByMeter = new HashMap<>();
        for (Meter meter : activeMeters) {
            if (meter.getId() == null) {
                continue;
            }
            metersById.put(meter.getId(), meter);
            List<MeterReading> timeline = readingService.findByMeter(meter.getId());
            timelinesByMeter.put(meter.getId(), timeline);
            if (!timeline.isEmpty()) {
                latestValuesByMeter.put(meter.getId(), timeline.get(timeline.size() - 1).getReadingValue());
            }
        }
    }

    private OutputStream receiveUpload(String fileName, String mimeType) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        buffers.put(fileName, buffer);
        return buffer;
    }

    void analyze(String fileName, String mimeType) {
        ByteArrayOutputStream buffer = buffers.remove(fileName);
        if (buffer == null) {
            return;
        }
        analyzeBytes(fileName, mimeType, buffer.toByteArray());
    }

    @ClientCallable
    public void handlePastedImage(String fileName, String mimeType, String base64Payload) {
        if (base64Payload == null || base64Payload.isBlank()) {
            return;
        }
        byte[] bytes = Base64.getDecoder().decode(base64Payload);
        String effectiveName = (fileName == null || fileName.isBlank()) ? "clipboard.png" : fileName.trim();
        analyzeBytes(effectiveName, mimeType, bytes);
    }

    @ClientCallable
    public void notifyPasteStatus(String status) {
        String key = "empty".equals(status) ? "energy.photo.paste.empty" : "energy.photo.paste.unsupported";
        Notification.show(getTranslation(key), 4000, Notification.Position.MIDDLE);
    }

    void analyzeBytes(String fileName, String mimeType, byte[] bytes) {
        VisionConfig config = appSettingService.getVisionConfig();
        if (config == null || !config.isConfigured()) {
            Notification.show(getTranslation("energy.photo.error.not-configured"), 5000,
                    Notification.Position.MIDDLE);
            return;
        }
        String effectiveMime = (mimeType == null || mimeType.isBlank()) ? "image/jpeg" : mimeType;
        String dataUri = "data:" + effectiveMime + ";base64," + Base64.getEncoder().encodeToString(bytes);
        Optional<LocalDate> exifDate = ExifDateExtractor.extractCaptureDate(bytes);

        try {
            List<ExtractedMeterReading> extracted = visionMeterClient.extractReadings(
                    config, bytes, effectiveMime, exifDate.orElse(null), activeMeters, latestValuesByMeter);
            if (extracted.isEmpty()) {
                rows.add(new ReviewRow(
                        fileName, dataUri, null, exifDate.orElse(LocalDate.now()),
                        null, false, 0.0, getTranslation("energy.photo.no-match")));
            } else {
                for (ExtractedMeterReading candidate : extracted) {
                    Meter matched = candidate.meterId() == null ? null : metersById.get(candidate.meterId());
                    BigDecimal normalized = ExtractedMeterReading.normalizeForMeter(
                            candidate.value(), candidate.unit(), matched);
                    LocalDate date = candidate.readingDate() != null
                            ? candidate.readingDate()
                            : exifDate.orElse(LocalDate.now());
                    rows.add(new ReviewRow(
                            fileName, dataUri, matched, date, normalized,
                            candidate.delta(), candidate.confidence(), candidate.sourceHint()));
                }
            }
            refreshGrid();
        } catch (RuntimeException exception) {
            Notification.show(getTranslation("energy.photo.error.analysis", fileName, exception.getMessage()),
                    5000, Notification.Position.MIDDLE);
        }
    }

    private void configureGrid() {
        grid.setHeight("380px");
        grid.setWidthFull();

        grid.addComponentColumn(this::renderPreviewCell)
                .setHeader(getTranslation("energy.photo.column.preview"))
                .setWidth("210px")
                .setFlexGrow(0);
        grid.addComponentColumn(this::renderMeterSelector)
                .setHeader(getTranslation("energy.photo.column.meter"))
                .setWidth("240px")
                .setFlexGrow(1);
        grid.addComponentColumn(this::renderDateField)
                .setHeader(getTranslation("energy.photo.column.date"))
                .setWidth("155px")
                .setFlexGrow(0);
        grid.addComponentColumn(this::renderValueField)
                .setHeader(getTranslation("energy.photo.column.value"))
                .setWidth("180px")
                .setFlexGrow(0);
        grid.addComponentColumn(this::renderDeltaToggle)
                .setHeader(getTranslation("energy.photo.column.delta"))
                .setWidth("220px")
                .setFlexGrow(0);
        grid.addComponentColumn(this::renderStatusCell)
                .setHeader(getTranslation("energy.photo.column.status"))
                .setWidth("145px")
                .setFlexGrow(0);
        grid.addComponentColumn(row -> GridActionButton.createDanger(VaadinIcon.TRASH,
                getTranslation("energy.photo.action.remove"),
                event -> {
                    rows.remove(row);
                    refreshGrid();
                }))
                .setWidth("60px")
                .setFlexGrow(0);
    }

    private VerticalLayout renderPreviewCell(ReviewRow row) {
        Image thumb = new Image(row.dataUri, row.fileName);
        thumb.setHeight("56px");
        thumb.getStyle().set("border-radius", "var(--lumo-border-radius-s)")
                .set("object-fit", "cover")
                .set("cursor", "pointer");
        thumb.addClickListener(event -> openLightbox(row));

        Span hint = new Span(row.sourceHint == null ? "" : row.sourceHint);
        hint.addClassNames(LumoUtility.FontSize.XSMALL, LumoUtility.TextColor.SECONDARY);

        VerticalLayout cell = new VerticalLayout(thumb, hint);
        cell.setPadding(false);
        cell.setSpacing(false);
        return cell;
    }

    private void openLightbox(ReviewRow row) {
        Dialog preview = new Dialog();
        preview.setHeaderTitle(row.fileName);
        Image full = new Image(row.dataUri, row.fileName);
        full.setMaxWidth("90vw");
        full.setMaxHeight("80vh");
        preview.add(full);
        preview.setCloseOnEsc(true);
        preview.setCloseOnOutsideClick(true);
        preview.open();
    }

    private ComboBox<Meter> renderMeterSelector(ReviewRow row) {
        ComboBox<Meter> selector = new ComboBox<>();
        selector.setItems(activeMeters);
        selector.setItemLabelGenerator(Meter::getName);
        selector.setValue(row.meter);
        selector.setWidthFull();
        selector.addValueChangeListener(event -> {
            row.meter = event.getValue();
            row.value = ExtractedMeterReading.roundForMeter(row.value, row.meter);
            refreshGrid();
        });
        return selector;
    }

    private DatePicker renderDateField(ReviewRow row) {
        DatePicker picker = new DatePicker();
        picker.setValue(row.readingDate);
        picker.setWidthFull();
        picker.addValueChangeListener(event -> {
            row.readingDate = event.getValue();
            refreshGrid();
        });
        return picker;
    }

    private VerticalLayout renderValueField(ReviewRow row) {
        BigDecimalField field = new BigDecimalField();
        field.setValue(row.value);
        field.setWidthFull();
        if (row.meter != null) {
            field.setSuffixComponent(new Span(row.meter.getUnit()));
        }
        field.addValueChangeListener(event -> {
            row.value = event.getValue();
            refreshGrid();
        });

        VerticalLayout wrapper = new VerticalLayout(field);
        wrapper.setPadding(false);
        wrapper.setSpacing(false);
        BigDecimal effective = effectiveValue(row);
        if (row.addToPrevious && effective != null) {
            Span targetLabel = new Span("= " + effective.toPlainString()
                    + (row.meter != null ? " " + row.meter.getUnit() : ""));
            targetLabel.addClassNames(LumoUtility.FontSize.XSMALL, LumoUtility.TextColor.PRIMARY);
            wrapper.add(targetLabel);
        }
        return wrapper;
    }

    private Checkbox renderDeltaToggle(ReviewRow row) {
        Checkbox toggle = new Checkbox(getTranslation("energy.photo.delta.add-previous"));
        toggle.setValue(row.addToPrevious);
        toggle.setEnabled(row.meter != null && previousValueFor(row.meter.getId(), row.readingDate) != null);
        toggle.addValueChangeListener(event -> {
            row.addToPrevious = Boolean.TRUE.equals(event.getValue());
            refreshGrid();
        });
        return toggle;
    }

    private Span renderStatusCell(ReviewRow row) {
        Span badge = new Span();
        badge.getElement().getThemeList().add("badge");
        badge.getElement().getThemeList().add("pill");

        if (row.meter == null || row.value == null || row.readingDate == null) {
            badge.setText(getTranslation("energy.photo.status.incomplete"));
            badge.getElement().getThemeList().add("error");
            return badge;
        }
        if (isBelowPrevious(row)) {
            badge.setText(getTranslation("energy.photo.status.below-previous"));
            badge.getElement().getThemeList().add("error");
            return badge;
        }
        int pct = (int) Math.round(row.confidence * 100.0);
        badge.setText(pct + " %");
        badge.getElement().getThemeList().add(row.confidence >= HIGH_CONFIDENCE_THRESHOLD ? "success" : "contrast");
        return badge;
    }

    boolean isBelowPrevious(ReviewRow row) {
        if (row.meter == null || row.meter.getOverflowThreshold() != null) {
            return false;
        }
        BigDecimal prev = previousValueFor(row.meter.getId(), row.readingDate);
        BigDecimal effective = effectiveValue(row);
        return prev != null && effective != null && effective.compareTo(prev) < 0;
    }

    private BigDecimal previousValueFor(UUID meterId, LocalDate readingDate) {
        if (meterId == null) {
            return null;
        }
        List<MeterReading> timeline = timelinesByMeter.get(meterId);
        if (timeline == null || timeline.isEmpty()) {
            return latestValuesByMeter.get(meterId);
        }
        if (readingDate == null) {
            return latestValuesByMeter.get(meterId);
        }
        BigDecimal previous = null;
        for (MeterReading entry : timeline) {
            if (entry.getReadingDate() != null && entry.getReadingDate().isBefore(readingDate)) {
                previous = entry.getReadingValue();
            }
        }
        return previous;
    }

    BigDecimal effectiveValue(ReviewRow row) {
        if (row.value == null) {
            return null;
        }
        BigDecimal result = row.value;
        if (row.addToPrevious && row.meter != null) {
            BigDecimal prev = previousValueFor(row.meter.getId(), row.readingDate);
            if (prev != null) {
                result = prev.add(row.value);
            }
        }
        return ExtractedMeterReading.roundForMeter(result, row.meter);
    }

    private void refreshGrid() {
        grid.setItems(rows);
        boolean canSave = rows.stream().anyMatch(r -> r.meter != null && r.value != null && r.readingDate != null);
        saveButton.setEnabled(canSave);
    }

    boolean requestSave() {
        List<ReviewRow> belowPrevious = rows.stream()
                .filter(r -> r.meter != null && r.readingDate != null && effectiveValue(r) != null)
                .filter(this::isBelowPrevious)
                .toList();
        if (belowPrevious.isEmpty()) {
            saveAll();
            return true;
        }
        ReviewRow first = belowPrevious.get(0);
        BigDecimal prev = previousValueFor(first.meter.getId(), first.readingDate);
        BigDecimal eff = effectiveValue(first);
        String summary = first.meter.getName() + ": " + eff.toPlainString()
                + " < " + (prev != null ? prev.stripTrailingZeros().toPlainString() : "");
        openBelowPreviousConfirmation(summary);
        return false;
    }

    private void openBelowPreviousConfirmation(String summary) {
        Dialog confirm = new Dialog();
        confirm.setHeaderTitle(getTranslation("energy.photo.confirm.below-previous.title"));
        confirm.setWidth("480px");
        confirm.setCloseOnEsc(true);
        confirm.setCloseOnOutsideClick(false);

        Paragraph body = new Paragraph(getTranslation("energy.photo.confirm.below-previous.body", summary));
        Button fixButton = new Button(getTranslation("energy.photo.confirm.below-previous.fix"),
                event -> confirm.close());
        fixButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button proceedButton = new Button(getTranslation("energy.photo.confirm.below-previous.proceed"),
                event -> {
                    confirm.close();
                    saveAll();
                });
        proceedButton.addThemeVariants(ButtonVariant.LUMO_ERROR);

        HorizontalLayout actions = new HorizontalLayout(fixButton, proceedButton);
        actions.setJustifyContentMode(JustifyContentMode.END);
        actions.setWidthFull();
        confirm.add(new VerticalLayout(body, actions));
        confirm.open();
    }

    void saveAll() {
        int saved = 0;
        for (ReviewRow row : rows) {
            BigDecimal effective = effectiveValue(row);
            if (row.meter == null || row.readingDate == null || effective == null) {
                continue;
            }
            readingService.recordReading(row.meter.getId(), row.readingDate, effective, row.sourceHint);
            if (Boolean.TRUE.equals(persistHints.getValue())
                    && (row.meter.getRecognitionHint() == null || row.meter.getRecognitionHint().isBlank())
                    && row.sourceHint != null && !row.sourceHint.isBlank()) {
                String hint = row.sourceHint.trim();
                row.meter.setRecognitionHint(
                        hint.length() > HINT_MAX_LENGTH ? hint.substring(0, HINT_MAX_LENGTH) : hint);
                meterService.save(row.meter);
            }
            saved++;
        }
        Notification.show(getTranslation("energy.photo.notification.saved", saved), 3000,
                Notification.Position.BOTTOM_START);
        close();
        if (onSaved != null) {
            onSaved.run();
        }
    }

    List<ReviewRow> getRowsForTest() {
        return rows;
    }

    static final class ReviewRow {
        final String fileName;
        final String dataUri;
        Meter meter;
        LocalDate readingDate;
        BigDecimal value;
        boolean addToPrevious;
        final double confidence;
        final String sourceHint;

        ReviewRow(String fileName, String dataUri, Meter meter, LocalDate readingDate, BigDecimal value,
                boolean addToPrevious, double confidence, String sourceHint) {
            this.fileName = fileName;
            this.dataUri = dataUri;
            this.meter = meter;
            this.readingDate = readingDate;
            this.value = value;
            this.addToPrevious = addToPrevious;
            this.confidence = confidence;
            this.sourceHint = sourceHint;
        }
    }
}
