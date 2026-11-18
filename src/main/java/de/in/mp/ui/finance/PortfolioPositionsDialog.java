package de.in.mp.ui.finance;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.ColumnTextAlign;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.theme.lumo.LumoUtility;

import de.in.mp.finance.Account;
import de.in.mp.finance.AccountSnapshot;
import de.in.mp.finance.AccountSnapshotService;
import de.in.mp.finance.DepotImportResult;
import de.in.mp.finance.DepotValuation;
import de.in.mp.finance.InvestmentPerformance;
import de.in.mp.finance.PortfolioPosition;
import de.in.mp.finance.PortfolioPositionImportService;
import de.in.mp.finance.PortfolioPositionService;
import de.in.mp.i18n.MoneyFormatter;
import de.in.mp.ui.GridActionButton;

/**
 * Depot detail dialog showing holdings ({@link PortfolioPosition}) for an
 * {@code INVESTMENT} account, the active valuation mode (Mode A vs. Mode B),
 * quote refresh triggers and position editing actions.
 */
class PortfolioPositionsDialog extends Dialog {

    private static final String DIALOG_WIDTH = "1020px";
    private static final String DIALOG_HEIGHT = "680px";
    private static final String EMPTY_PLACEHOLDER = "-";
    private static final String PDF_FILE_EXTENSION = ".pdf";
    private static final String PDF_MIME_TYPE = "application/pdf";
    private static final int NOTIFICATION_DURATION_MS = 3000;
    private static final int IMPORT_NOTIFICATION_DURATION_MS = 5000;
    private static final int MAX_QUANTITY_FRACTION_DIGITS = 4;
    private static final DateTimeFormatter PRICE_TIME_FORMAT = DateTimeFormatter.ofPattern("dd.MM. HH:mm");

    private final PortfolioPositionService positionService;
    private final AccountSnapshotService snapshotService;
    private final PortfolioPositionImportService importService;
    private final PortfolioPositionEditorDialog editorDialog;
    private final boolean canModify;
    private final ByteArrayOutputStream uploadBuffer = new ByteArrayOutputStream();

    private final Span modeBadge = new Span();
    private final Span totalValueSpan = new Span();
    private final Span investedSpan = new Span();
    private final Span performanceSpan = new Span();
    private final Grid<PortfolioPosition> grid = new Grid<>(PortfolioPosition.class, false);

    private Account currentDepot;
    private Runnable onChangedCallback;

    PortfolioPositionsDialog(
            PortfolioPositionService positionService,
            AccountSnapshotService snapshotService,
            boolean canModify) {
        this(positionService, snapshotService, null, canModify);
    }

    PortfolioPositionsDialog(
            PortfolioPositionService positionService,
            AccountSnapshotService snapshotService,
            PortfolioPositionImportService importService,
            boolean canModify) {
        this.positionService = positionService;
        this.snapshotService = snapshotService;
        this.importService = importService;
        this.canModify = canModify;
        this.editorDialog = new PortfolioPositionEditorDialog(positionService);

        setWidth(DIALOG_WIDTH);
        setMaxWidth("96vw");
        setHeight(DIALOG_HEIGHT);
        setMaxHeight("92vh");

        configureGrid();
        buildContent();
        buildFooter();
    }

    void openForDepot(Account depot, Runnable onChanged) {
        initForDepot(depot, onChanged);
        open();
    }

    void initForDepot(Account depot, Runnable onChanged) {
        this.currentDepot = depot;
        this.onChangedCallback = onChanged;
        setHeaderTitle(getTranslation("finance.positions.dialog.title", depot.getName()));
        refresh();
    }

    private void buildContent() {
        modeBadge.getElement().getThemeList().add("badge");
        modeBadge.getElement().getThemeList().add("pill");

        totalValueSpan.addClassNames(LumoUtility.FontWeight.BOLD);
        investedSpan.addClassName(LumoUtility.TextColor.SECONDARY);

        HorizontalLayout summaryBar = new HorizontalLayout(modeBadge, totalValueSpan, investedSpan, performanceSpan);
        summaryBar.setWidthFull();
        summaryBar.setSpacing(true);
        summaryBar.setDefaultVerticalComponentAlignment(Alignment.CENTER);
        summaryBar.getStyle().set("flex-wrap", "wrap");

        HorizontalLayout toolbar = new HorizontalLayout();
        toolbar.setWidthFull();
        toolbar.setSpacing(true);
        toolbar.setDefaultVerticalComponentAlignment(Alignment.CENTER);
        toolbar.getStyle().set("flex-wrap", "wrap");
        if (canModify) {
            Button addButton = new Button(
                    getTranslation("finance.positions.button.add"),
                    VaadinIcon.PLUS.create(),
                    event -> editorDialog.openForCreate(currentDepot.getId(), this::handlePositionsChanged));
            addButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_SMALL);

            Button refreshButton = new Button(
                    getTranslation("finance.positions.button.refresh"),
                    VaadinIcon.REFRESH.create(),
                    event -> refreshQuotes());
            refreshButton.addThemeVariants(ButtonVariant.LUMO_SMALL);

            toolbar.add(addButton, refreshButton);
            if (importService != null) {
                toolbar.add(createPdfImportUpload());
            }
        }

        VerticalLayout content = new VerticalLayout(summaryBar, toolbar, grid);
        content.setSizeFull();
        content.setPadding(false);
        content.setSpacing(true);
        content.expand(grid);
        add(content);
    }

    private void buildFooter() {
        Button closeButton = new Button(getTranslation("finance.positions.button.close"), event -> close());
        getFooter().add(closeButton);
    }

    private void configureGrid() {
        grid.addColumn(PortfolioPosition::getName)
                .setHeader(getTranslation("finance.positions.column.name"))
                .setAutoWidth(true)
                .setFlexGrow(1);

        grid.addColumn(PortfolioPosition::getSymbolDisplay)
                .setHeader(getTranslation("finance.positions.column.symbol"))
                .setAutoWidth(true);

        grid.addColumn(pos -> formatQuantity(pos.getQuantity()))
                .setHeader(getTranslation("finance.positions.column.quantity"))
                .setTextAlign(ColumnTextAlign.END)
                .setAutoWidth(true);

        grid.addColumn(pos -> pos.getBuyInPrice() != null
                ? MoneyFormatter.format(pos.getBuyInPrice(), locale())
                : EMPTY_PLACEHOLDER)
                .setHeader(getTranslation("finance.positions.column.buy-in"))
                .setTextAlign(ColumnTextAlign.END)
                .setAutoWidth(true);

        grid.addColumn(new ComponentRenderer<>(this::renderPriceCell))
                .setHeader(getTranslation("finance.positions.column.price"))
                .setTextAlign(ColumnTextAlign.END)
                .setAutoWidth(true);

        grid.addColumn(pos -> pos.getMarketValueEur()
                .map(val -> MoneyFormatter.format(val, locale()))
                .orElse(EMPTY_PLACEHOLDER))
                .setHeader(getTranslation("finance.positions.column.value"))
                .setTextAlign(ColumnTextAlign.END)
                .setAutoWidth(true);

        grid.addColumn(new ComponentRenderer<>(this::renderPerformanceCell))
                .setHeader(getTranslation("finance.positions.column.performance"))
                .setTextAlign(ColumnTextAlign.END)
                .setAutoWidth(true);

        if (canModify) {
            grid.addColumn(new ComponentRenderer<>(this::renderRowActions))
                    .setHeader(getTranslation("finance.positions.column.actions"))
                    .setAutoWidth(true);
        }

        grid.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_ROW_STRIPES);
        grid.setSizeFull();
    }

    private Span renderPriceCell(PortfolioPosition pos) {
        Optional<BigDecimal> eurPrice = pos.getEffectivePriceEur();
        if (eurPrice.isEmpty()) {
            Span empty = new Span(EMPTY_PLACEHOLDER);
            empty.addClassName(LumoUtility.TextColor.SECONDARY);
            return empty;
        }
        Span span = new Span(MoneyFormatter.format(eurPrice.get(), locale()));
        span.getStyle().set("white-space", "nowrap");
        StringBuilder tooltip = new StringBuilder();
        if (pos.getLastPrice() != null && !PortfolioPosition.DEFAULT_CURRENCY.equalsIgnoreCase(pos.getCurrency())) {
            tooltip.append(pos.getLastPrice().stripTrailingZeros().toPlainString())
                    .append(" ")
                    .append(pos.getCurrency());
        }
        if (pos.getPriceUpdatedAt() != null) {
            if (!tooltip.isEmpty()) {
                tooltip.append(" · ");
            }
            tooltip.append(PRICE_TIME_FORMAT.format(pos.getPriceUpdatedAt().atZone(ZoneId.systemDefault())));
        }
        if (!tooltip.isEmpty()) {
            span.setTitle(tooltip.toString());
        }
        return span;
    }

    private Span renderPerformanceCell(PortfolioPosition pos) {
        Optional<InvestmentPerformance> perfOpt = pos.getPerformance();
        if (perfOpt.isEmpty()) {
            Span empty = new Span(EMPTY_PLACEHOLDER);
            empty.addClassName(LumoUtility.TextColor.SECONDARY);
            return empty;
        }
        InvestmentPerformance perf = perfOpt.get();
        Span span = new Span(perf.format(locale()));
        span.getStyle().set("white-space", "nowrap");
        applyPerformanceColor(span, perf);
        return span;
    }

    private HorizontalLayout renderRowActions(PortfolioPosition pos) {
        Button editButton = GridActionButton.create(
                VaadinIcon.EDIT,
                getTranslation("finance.action.edit"),
                event -> editorDialog.openForEdit(pos, this::handlePositionsChanged));
        Button deleteButton = GridActionButton.createDanger(
                VaadinIcon.TRASH,
                getTranslation("finance.balance.action.delete-row"),
                event -> {
                    positionService.delete(pos.getId());
                    Notification.show(getTranslation("finance.positions.notification.deleted"),
                            NOTIFICATION_DURATION_MS, Notification.Position.BOTTOM_START);
                    handlePositionsChanged();
                });
        return new HorizontalLayout(editButton, deleteButton);
    }

    private void refreshQuotes() {
        int updated = positionService.refreshAccountPrices(currentDepot.getId());
        Notification.show(getTranslation("finance.positions.notification.refreshed", updated),
                NOTIFICATION_DURATION_MS, Notification.Position.BOTTOM_START);
        handlePositionsChanged();
    }

    private Upload createPdfImportUpload() {
        Upload upload = new Upload((fileName, mimeType) -> {
            uploadBuffer.reset();
            return uploadBuffer;
        });
        upload.setAcceptedFileTypes(PDF_FILE_EXTENSION, PDF_MIME_TYPE);
        upload.setMaxFiles(1);
        upload.setDropAllowed(false);
        Button uploadButton = new Button(
                getTranslation("finance.positions.button.import-pdf"),
                VaadinIcon.UPLOAD.create());
        uploadButton.addThemeVariants(ButtonVariant.LUMO_SMALL);
        upload.setUploadButton(uploadButton);
        upload.getStyle().set("display", "inline-flex");
        upload.getStyle().set("align-items", "center");
        upload.addSucceededListener(event -> {
            try {
                importPdf(uploadBuffer.toByteArray());
            } finally {
                uploadBuffer.reset();
                upload.clearFileList();
            }
        });
        return upload;
    }

    boolean importPdf(byte[] pdfBytes) {
        if (currentDepot == null || importService == null) {
            return false;
        }
        try {
            DepotImportResult result = importService.importDepotPdf(currentDepot.getId(), pdfBytes);
            Notification.show(
                    getTranslation("finance.positions.notification.imported",
                            result.created(), result.updated(), result.consolidated(), result.deleted()),
                    IMPORT_NOTIFICATION_DURATION_MS,
                    Notification.Position.BOTTOM_START);
            handlePositionsChanged();
            return true;
        } catch (IllegalArgumentException ex) {
            Notification error = Notification.show(
                    getTranslation("finance.positions.notification.import-error", ex.getMessage()),
                    IMPORT_NOTIFICATION_DURATION_MS,
                    Notification.Position.BOTTOM_START);
            error.addThemeVariants(NotificationVariant.LUMO_ERROR);
            return false;
        }
    }

    private void handlePositionsChanged() {
        refresh();
        if (onChangedCallback != null) {
            onChangedCallback.run();
        }
    }

    void refresh() {
        if (currentDepot == null) {
            return;
        }
        List<PortfolioPosition> positions = positionService.findByAccount(currentDepot.getId());
        grid.setItems(positions);

        AccountSnapshot latestSnapshot = snapshotService.findLatest(currentDepot.getId()).orElse(null);
        DepotValuation valuation = positionService.resolveValuation(currentDepot, latestSnapshot);

        modeBadge.getElement().getThemeList().remove("success");
        modeBadge.getElement().getThemeList().remove("contrast");
        if (valuation.positionBased()) {
            modeBadge.setText(getTranslation("finance.positions.mode.positions", valuation.positionCount()));
            modeBadge.getElement().getThemeList().add("success");
        } else {
            modeBadge.setText(getTranslation("finance.positions.mode.snapshot"));
            modeBadge.getElement().getThemeList().add("contrast");
        }

        totalValueSpan.setText(getTranslation("finance.positions.summary.total",
                MoneyFormatter.format(valuation.currentValue(), locale())));

        if (valuation.investedCapital() != null) {
            String investedText = getTranslation("finance.positions.summary.invested",
                    MoneyFormatter.format(valuation.investedCapital(), locale()));
            if (valuation.positionBased() && !valuation.costBasisComplete()) {
                investedText = investedText + " (" + getTranslation("finance.positions.mode.partial-cost-hint") + ")";
            }
            investedSpan.setText(investedText);
        } else {
            investedSpan.setText("");
        }

        performanceSpan.removeClassNames(
                LumoUtility.TextColor.SUCCESS,
                LumoUtility.TextColor.ERROR,
                LumoUtility.TextColor.SECONDARY);
        Optional<InvestmentPerformance> perfOpt = valuation.performance();
        if (perfOpt.isPresent()) {
            InvestmentPerformance perf = perfOpt.get();
            performanceSpan.setText(getTranslation("finance.positions.summary.performance", perf.format(locale())));
            applyPerformanceColor(performanceSpan, perf);
        } else {
            performanceSpan.setText("");
        }
    }

    private static void applyPerformanceColor(Span span, InvestmentPerformance perf) {
        if (perf.isPositive()) {
            span.addClassName(LumoUtility.TextColor.SUCCESS);
        } else if (perf.isNegative()) {
            span.addClassName(LumoUtility.TextColor.ERROR);
        } else {
            span.addClassName(LumoUtility.TextColor.SECONDARY);
        }
    }

    private String formatQuantity(BigDecimal quantity) {
        if (quantity == null) {
            return EMPTY_PLACEHOLDER;
        }
        NumberFormat nf = NumberFormat.getNumberInstance(locale());
        nf.setMinimumFractionDigits(0);
        nf.setMaximumFractionDigits(MAX_QUANTITY_FRACTION_DIGITS);
        return nf.format(quantity);
    }

    private static Locale locale() {
        return UI.getCurrent() != null ? UI.getCurrent().getLocale() : Locale.GERMANY;
    }
}
