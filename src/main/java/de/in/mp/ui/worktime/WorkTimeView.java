package de.in.mp.ui.worktime;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.AttachmentType;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.FlexComponent.JustifyContentMode;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.timepicker.TimePicker;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.streams.DownloadHandler;
import com.vaadin.flow.server.streams.DownloadResponse;
import com.vaadin.flow.theme.lumo.LumoUtility;

import de.in.mp.ui.GridActionButton;
import de.in.mp.ui.layout.MainLayout;
import de.in.mp.worktime.OverloadNoticePdfGenerator;
import de.in.mp.worktime.OverloadNoticePdfGenerator.OverloadNoticeOptions;
import de.in.mp.worktime.WorkTimeCategory;
import de.in.mp.worktime.WorkTimeEntry;
import de.in.mp.worktime.WorkTimeProfile;
import de.in.mp.worktime.WorkTimeService;
import de.in.mp.worktime.WorkTimeSummary.DaySummary;
import de.in.mp.worktime.WorkTimeSummary.PeriodSummary;
import de.in.mp.worktime.WorkTimeSummary.ProfileOption;
import jakarta.annotation.security.PermitAll;

/**
 * Personal work time tracking view supporting multiple blocks per day (school,
 * home office, meetings, optional commute), teacher pro-rata contract targets,
 * health/rest-period warnings, and CSV export.
 */
@Route(value = "work-time", layout = MainLayout.class)
@PermitAll
public class WorkTimeView extends VerticalLayout implements HasDynamicTitle, BeforeEnterObserver {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    public enum PeriodFilter {
        THIS_WEEK,
        LAST_WEEK,
        THIS_MONTH,
        LAST_MONTH,
        ALL
    }

    private final WorkTimeService workTimeService;
    private final OverloadNoticePdfGenerator overloadNoticePdfGenerator;

    private final Span contractBadge = new Span();
    private final ComboBox<ProfileOption> profileSelector = new ComboBox<>();
    private final ComboBox<PeriodFilter> periodSelector = new ComboBox<>();

    private final Span totalValue = new Span();
    private final Span totalSub = new Span();
    private final Span breakdownValue = new Span();
    private final Span breakdownSub = new Span();
    private final Span offHoursValue = new Span();
    private final Span offHoursSub = new Span();
    private final Span healthValue = new Span();
    private final Span healthSub = new Span();

    private final DatePicker selectedDatePicker = new DatePicker();
    private final Span daySummaryText = new Span();
    private final HorizontalLayout dayWarningBadges = new HorizontalLayout();

    private final TimePicker startTimePicker = new TimePicker();
    private final TimePicker endTimePicker = new TimePicker();
    private final ComboBox<WorkTimeCategory> categoryPicker = new ComboBox<>();
    private final Checkbox countsAsWorkBox = new Checkbox();
    private final TextField noteField = new TextField();
    private final Button saveBlockButton = new Button();
    private final Button cancelEditButton = new Button();

    private final Grid<WorkTimeEntry> dayBlocksGrid = new Grid<>(WorkTimeEntry.class, false);
    private final Span dayEmptyHint = new Span();
    private final Grid<DaySummary> periodDaysGrid = new Grid<>(DaySummary.class, false);

    private List<ProfileOption> accessibleProfiles = List.of();
    private ProfileOption selectedProfile;
    private UUID editingEntryId;

    public WorkTimeView(WorkTimeService workTimeService) {
        this(workTimeService, new OverloadNoticePdfGenerator());
    }

    @Autowired
    public WorkTimeView(
            WorkTimeService workTimeService,
            OverloadNoticePdfGenerator overloadNoticePdfGenerator) {
        this.workTimeService = workTimeService;
        this.overloadNoticePdfGenerator = overloadNoticePdfGenerator;

        setSizeFull();
        setPadding(true);
        setSpacing(true);

        add(createHeader(), createKpiRow(), createDayEditorCard(), createPeriodOverviewCard());
        reloadProfilesAndRefresh();
    }

    @Override
    public String getPageTitle() {
        return getTranslation("worktime.view.page-title");
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        if (workTimeService == null || !workTimeService.canCurrentUserAccessWorkTime()) {
            event.forwardTo("");
            return;
        }
        reloadProfilesAndRefresh();
    }

    private VerticalLayout createHeader() {
        H2 title = new H2(getTranslation("worktime.view.title"));
        title.addClassNames(LumoUtility.Margin.NONE);

        Span subtitle = new Span(getTranslation("worktime.view.subtitle"));
        subtitle.addClassNames(LumoUtility.FontSize.SMALL, LumoUtility.TextColor.SECONDARY);

        contractBadge.getElement().getThemeList().addAll(List.of("badge", "pill", "contrast"));

        profileSelector.setLabel(getTranslation("worktime.profile.label"));
        profileSelector.setItemLabelGenerator(ProfileOption::displayName);
        profileSelector.addValueChangeListener(event -> {
            if (event.getValue() != null) {
                selectedProfile = event.getValue();
                cancelEditing();
                refreshAll();
            }
        });

        periodSelector.setLabel(getTranslation("worktime.period.label"));
        periodSelector.setItems(PeriodFilter.values());
        periodSelector.setItemLabelGenerator(filter -> getTranslation("worktime.period." + toPeriodKey(filter)));
        periodSelector.setValue(PeriodFilter.THIS_WEEK);
        periodSelector.addValueChangeListener(event -> refreshPeriodSummary());

        Button exportButton = new Button(getTranslation("worktime.button.export-csv"), VaadinIcon.DOWNLOAD_ALT.create());
        exportButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        DownloadHandler handler = DownloadHandler.fromInputStream(event -> buildCsvDownloadResponse());
        Anchor exportAnchor = new Anchor(handler, AttachmentType.DOWNLOAD, "");
        exportAnchor.add(exportButton);

        Button overloadNoticeButton = new Button(
                getTranslation("worktime.button.overload-pdf"),
                VaadinIcon.FILE_TEXT_O.create(),
                event -> openOverloadNoticeDialog());
        overloadNoticeButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

        HorizontalLayout controls = new HorizontalLayout(
                profileSelector, periodSelector, exportAnchor, overloadNoticeButton);
        controls.setDefaultVerticalComponentAlignment(Alignment.END);
        controls.getStyle().set("flex-wrap", "wrap");

        VerticalLayout titleBlock = new VerticalLayout(title, subtitle, contractBadge);
        titleBlock.setPadding(false);
        titleBlock.setSpacing(false);
        titleBlock.getStyle().set("gap", "var(--lumo-space-xs)");

        HorizontalLayout headerRow = new HorizontalLayout(titleBlock, controls);
        headerRow.setWidthFull();
        headerRow.setDefaultVerticalComponentAlignment(Alignment.END);
        headerRow.expand(titleBlock);
        headerRow.getStyle().set("flex-wrap", "wrap");

        VerticalLayout wrapper = new VerticalLayout(headerRow);
        wrapper.setPadding(false);
        wrapper.setSpacing(false);
        return wrapper;
    }

    private HorizontalLayout createKpiRow() {
        HorizontalLayout row = new HorizontalLayout(
                createKpiCard(getTranslation("worktime.kpi.total.title"), totalValue, totalSub),
                createKpiCard(getTranslation("worktime.kpi.breakdown.title"), breakdownValue, breakdownSub),
                createKpiCard(getTranslation("worktime.kpi.offhours.title"), offHoursValue, offHoursSub),
                createKpiCard(getTranslation("worktime.kpi.health.title"), healthValue, healthSub));
        row.setWidthFull();
        row.setSpacing(true);
        row.getStyle().set("flex-wrap", "wrap");
        return row;
    }

    private VerticalLayout createKpiCard(String label, Span value, Span sub) {
        Span labelSpan = new Span(label);
        labelSpan.addClassNames(LumoUtility.TextColor.SECONDARY, LumoUtility.FontSize.XSMALL);

        value.addClassNames(LumoUtility.FontSize.LARGE, LumoUtility.FontWeight.BOLD);
        sub.addClassNames(LumoUtility.FontSize.XSMALL, LumoUtility.TextColor.SECONDARY);

        VerticalLayout card = new VerticalLayout(labelSpan, value, sub);
        card.setPadding(false);
        card.setSpacing(false);
        card.getStyle().set("flex", "1 1 200px");
        card.setMinWidth("200px");
        card.addClassNames(
                LumoUtility.Padding.Vertical.SMALL,
                LumoUtility.Padding.Horizontal.MEDIUM,
                LumoUtility.Background.CONTRAST_5,
                LumoUtility.BorderRadius.MEDIUM,
                LumoUtility.BoxShadow.SMALL);
        return card;
    }

    private VerticalLayout createDayEditorCard() {
        H3 heading = new H3(getTranslation("worktime.day.title"));
        heading.addClassNames(LumoUtility.Margin.NONE, LumoUtility.FontSize.MEDIUM);

        Button prevButton = GridActionButton.create(
                VaadinIcon.ANGLE_LEFT,
                getTranslation("worktime.day.prev"),
                event -> shiftSelectedDate(-1));
        Button nextButton = GridActionButton.create(
                VaadinIcon.ANGLE_RIGHT,
                getTranslation("worktime.day.next"),
                event -> shiftSelectedDate(1));

        selectedDatePicker.setValue(LocalDate.now());
        selectedDatePicker.addValueChangeListener(event -> {
            if (event.getValue() != null) {
                cancelEditing();
                refreshDayBlocks();
            }
        });

        Button todayButton = new Button(getTranslation("worktime.day.today"), event -> {
            selectedDatePicker.setValue(LocalDate.now());
        });
        todayButton.addThemeVariants(ButtonVariant.LUMO_SMALL);

        Button yesterdayButton = new Button(getTranslation("worktime.day.yesterday"), event -> {
            selectedDatePicker.setValue(LocalDate.now().minusDays(1));
        });
        yesterdayButton.addThemeVariants(ButtonVariant.LUMO_SMALL);

        Button copyLastWeekButton = new Button(
                getTranslation("worktime.day.copy-last-week"),
                VaadinIcon.COPY_O.create(),
                event -> copyLastWeekSchoolBlocks());
        copyLastWeekButton.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);

        HorizontalLayout dateControls = new HorizontalLayout(
                prevButton, selectedDatePicker, nextButton, todayButton, yesterdayButton, copyLastWeekButton);
        dateControls.setDefaultVerticalComponentAlignment(Alignment.CENTER);
        dateControls.getStyle().set("flex-wrap", "wrap");

        daySummaryText.addClassNames(LumoUtility.FontSize.SMALL, LumoUtility.FontWeight.SEMIBOLD);
        dayWarningBadges.setSpacing(true);
        HorizontalLayout daySummaryBar = new HorizontalLayout(daySummaryText, dayWarningBadges);
        daySummaryBar.setDefaultVerticalComponentAlignment(Alignment.CENTER);
        daySummaryBar.getStyle().set("flex-wrap", "wrap");

        configureEntryFormFields();

        HorizontalLayout entryFormRow = new HorizontalLayout(
                startTimePicker, endTimePicker, categoryPicker, countsAsWorkBox,
                noteField, saveBlockButton, cancelEditButton);
        entryFormRow.setWidthFull();
        entryFormRow.setDefaultVerticalComponentAlignment(Alignment.END);
        entryFormRow.expand(noteField);
        entryFormRow.getStyle().set("flex-wrap", "wrap");

        configureDayBlocksGrid();
        dayEmptyHint.setText(getTranslation("worktime.entry.empty"));
        dayEmptyHint.addClassNames(LumoUtility.FontSize.SMALL, LumoUtility.TextColor.SECONDARY);

        VerticalLayout card = new VerticalLayout(
                heading, dateControls, daySummaryBar, entryFormRow, dayEmptyHint, dayBlocksGrid);
        card.setWidthFull();
        card.setPadding(true);
        card.setSpacing(true);
        card.addClassNames(
                LumoUtility.Background.CONTRAST_5,
                LumoUtility.BorderRadius.MEDIUM);
        return card;
    }

    private void configureEntryFormFields() {
        startTimePicker.setLabel(getTranslation("worktime.entry.start"));
        startTimePicker.setStep(Duration.ofMinutes(15));
        startTimePicker.setWidth("120px");

        endTimePicker.setLabel(getTranslation("worktime.entry.end"));
        endTimePicker.setStep(Duration.ofMinutes(15));
        endTimePicker.setWidth("120px");

        categoryPicker.setLabel(getTranslation("worktime.entry.category"));
        categoryPicker.setItems(WorkTimeCategory.values());
        categoryPicker.setItemLabelGenerator(cat -> getTranslation("worktime.category." + cat.name()));
        categoryPicker.setValue(WorkTimeCategory.SCHOOL);
        categoryPicker.setWidth("240px");
        categoryPicker.addValueChangeListener(event -> {
            if (event.isFromClient() && event.getValue() != null) {
                countsAsWorkBox.setValue(event.getValue().isDefaultCountsAsWork());
            }
        });

        countsAsWorkBox.setLabel(getTranslation("worktime.entry.counts-as-work"));
        countsAsWorkBox.setValue(true);

        noteField.setLabel(getTranslation("worktime.entry.note"));
        noteField.setPlaceholder(getTranslation("worktime.entry.note.placeholder"));
        noteField.setMinWidth("180px");

        saveBlockButton.setText(getTranslation("worktime.entry.add"));
        saveBlockButton.setIcon(VaadinIcon.PLUS.create());
        saveBlockButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        saveBlockButton.addClickListener(event -> saveBlock());

        cancelEditButton.setText(getTranslation("worktime.entry.cancel"));
        cancelEditButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        cancelEditButton.setVisible(false);
        cancelEditButton.addClickListener(event -> {
            cancelEditing();
            prefillSmartBlockTimes(dayBlocksGrid.getListDataView().getItems().toList());
        });
    }

    private void configureDayBlocksGrid() {
        dayBlocksGrid.setWidthFull();
        dayBlocksGrid.setAllRowsVisible(true);
        dayBlocksGrid.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_ROW_STRIPES);

        dayBlocksGrid.addColumn(entry -> entry.getStartTime().format(TIME_FORMAT)
                + " – " + entry.getEndTime().format(TIME_FORMAT))
                .setHeader(getTranslation("worktime.block.col.time"))
                .setAutoWidth(true);

        dayBlocksGrid.addColumn(entry -> WorkTimeService.formatMinutes(entry.getDurationMinutes()))
                .setHeader(getTranslation("worktime.block.col.duration"))
                .setAutoWidth(true);

        dayBlocksGrid.addColumn(new ComponentRenderer<>(this::renderCategoryBadge))
                .setHeader(getTranslation("worktime.block.col.category"))
                .setAutoWidth(true);

        dayBlocksGrid.addColumn(entry -> entry.isCountsAsWork()
                ? getTranslation("worktime.block.yes")
                : getTranslation("worktime.block.no"))
                .setHeader(getTranslation("worktime.block.col.work"))
                .setAutoWidth(true);

        dayBlocksGrid.addColumn(entry -> entry.getNote() != null ? entry.getNote() : "")
                .setHeader(getTranslation("worktime.block.col.note"))
                .setFlexGrow(1);

        dayBlocksGrid.addColumn(new ComponentRenderer<>(this::renderBlockActions))
                .setHeader(getTranslation("worktime.block.col.actions"))
                .setAutoWidth(true);
    }

    private Component renderCategoryBadge(WorkTimeEntry entry) {
        Span badge = new Span(getTranslation("worktime.category." + entry.getCategory().name()));
        badge.getElement().getThemeList().addAll(List.of("badge", "pill", "small"));
        String variant = switch (entry.getCategory()) {
            case SCHOOL -> "success";
            case HOME_OFFICE -> "primary";
            case MEETING -> "contrast";
            case OTHER -> "contrast";
        };
        badge.getElement().getThemeList().add(variant);
        return badge;
    }

    private HorizontalLayout renderBlockActions(WorkTimeEntry entry) {
        Button editBtn = GridActionButton.create(
                VaadinIcon.EDIT,
                getTranslation("worktime.block.edit"),
                event -> startEditing(entry));
        Button deleteBtn = GridActionButton.createDanger(
                VaadinIcon.TRASH,
                getTranslation("worktime.block.delete"),
                event -> deleteBlock(entry));
        return new HorizontalLayout(editBtn, deleteBtn);
    }

    private VerticalLayout createPeriodOverviewCard() {
        H3 heading = new H3(getTranslation("worktime.grid.title"));
        heading.addClassNames(LumoUtility.Margin.NONE, LumoUtility.FontSize.MEDIUM);

        periodDaysGrid.setWidthFull();
        periodDaysGrid.setAllRowsVisible(true);
        periodDaysGrid.addThemeVariants(GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_COLUMN_BORDERS);

        periodDaysGrid.addColumn(day -> day.date().format(DATE_FORMAT))
                .setHeader(getTranslation("worktime.grid.col.date"))
                .setAutoWidth(true);

        periodDaysGrid.addColumn(day -> formatWeekday(day.date()))
                .setHeader(getTranslation("worktime.grid.col.weekday"))
                .setAutoWidth(true);

        periodDaysGrid.addColumn(this::formatDayBlocksSummary)
                .setHeader(getTranslation("worktime.grid.col.blocks"))
                .setFlexGrow(1);

        periodDaysGrid.addColumn(day -> WorkTimeService.formatMinutes(day.workMinutes()))
                .setHeader(getTranslation("worktime.grid.col.work"))
                .setAutoWidth(true);

        periodDaysGrid.addColumn(day -> WorkTimeService.formatMinutes(day.targetMinutes()))
                .setHeader(getTranslation("worktime.grid.col.target"))
                .setAutoWidth(true);

        periodDaysGrid.addColumn(new ComponentRenderer<>(this::renderDeltaBadge))
                .setHeader(getTranslation("worktime.grid.col.delta"))
                .setAutoWidth(true);

        periodDaysGrid.addColumn(new ComponentRenderer<>(this::renderDayHints))
                .setHeader(getTranslation("worktime.grid.col.hints"))
                .setAutoWidth(true);

        periodDaysGrid.addItemClickListener(event -> {
            if (event.getItem() != null) {
                selectedDatePicker.setValue(event.getItem().date());
            }
        });

        VerticalLayout card = new VerticalLayout(heading, periodDaysGrid);
        card.setWidthFull();
        card.setPadding(false);
        card.setSpacing(true);
        return card;
    }

    private Span renderDeltaBadge(DaySummary day) {
        Span badge = new Span(WorkTimeService.formatSignedMinutes(day.overtimeMinutes()));
        badge.getElement().getThemeList().addAll(List.of("badge", "pill", "small"));
        if (day.overtimeMinutes() > 60) {
            badge.getElement().getThemeList().add("error");
        } else if (day.overtimeMinutes() > 0) {
            badge.getElement().getThemeList().add("contrast");
        } else {
            badge.getElement().getThemeList().add("success");
        }
        return badge;
    }

    private Component renderDayHints(DaySummary day) {
        HorizontalLayout hints = new HorizontalLayout();
        hints.setSpacing(false);
        hints.getStyle().set("gap", "var(--lumo-space-xs)");
        WorkTimeProfile profile = selectedProfile != null ? selectedProfile.profile() : null;

        if (day.weekend()) {
            hints.add(createHintBadge(getTranslation("worktime.hint.weekend"), "contrast"));
        }
        if (day.overDailyLimit() && profile != null) {
            hints.add(createHintBadge(
                    getTranslation("worktime.hint.over-limit", stripTrailingZeros(profile.getDailyWarningHours())),
                    "error"));
        }
        if (day.shortRestViolation() && profile != null) {
            hints.add(createHintBadge(
                    getTranslation("worktime.hint.short-rest", stripTrailingZeros(profile.getMinRestHours())),
                    "error"));
        }
        return hints;
    }

    private static Span createHintBadge(String text, String theme) {
        Span badge = new Span(text);
        badge.getElement().getThemeList().addAll(List.of("badge", "pill", "small", theme));
        return badge;
    }

    private void reloadProfilesAndRefresh() {
        if (workTimeService == null) {
            return;
        }
        accessibleProfiles = workTimeService.getAccessibleProfilesForCurrentUser();
        profileSelector.setItems(accessibleProfiles);
        profileSelector.setVisible(accessibleProfiles.size() > 1);

        if (accessibleProfiles.isEmpty()) {
            selectedProfile = null;
            return;
        }
        UUID previousProfileId = selectedProfile != null ? selectedProfile.profile().getId() : null;
        selectedProfile = accessibleProfiles.stream()
                .filter(option -> option.profile().getId().equals(previousProfileId))
                .findFirst()
                .orElse(accessibleProfiles.get(0));
        profileSelector.setValue(selectedProfile);
        refreshAll();
    }

    private void refreshAll() {
        updateContractBadge();
        refreshDayBlocks();
        refreshPeriodSummary();
    }

    private void updateContractBadge() {
        if (selectedProfile == null) {
            contractBadge.setText("");
            return;
        }
        WorkTimeProfile p = selectedProfile.profile();
        String pct = p.getPartTimeRatio()
                .multiply(BigDecimal.valueOf(100))
                .setScale(1, RoundingMode.HALF_UP)
                .toPlainString();
        contractBadge.setText(getTranslation(
                "worktime.contract.badge",
                stripTrailingZeros(p.getContractTeachingUnits()),
                stripTrailingZeros(p.getFullTimeTeachingUnits()),
                pct,
                stripTrailingZeros(p.getFullTimeWeeklyHours()),
                WorkTimeService.formatMinutes(p.getPaidWeeklyMinutes()),
                WorkTimeService.formatMinutes(p.getPaidDailyMinutes())));
    }

    private void refreshDayBlocks() {
        if (selectedProfile == null || selectedDatePicker.getValue() == null) {
            return;
        }
        LocalDate date = selectedDatePicker.getValue();
        WorkTimeProfile profile = selectedProfile.profile();
        DaySummary day = workTimeService.summarizeDay(profile, date);

        List<WorkTimeEntry> entries = day.entries();
        dayBlocksGrid.setItems(entries);
        dayBlocksGrid.setVisible(!entries.isEmpty());
        dayEmptyHint.setVisible(entries.isEmpty());

        daySummaryText.setText(getTranslation(
                "worktime.day.summary",
                WorkTimeService.formatMinutes(day.workMinutes()),
                WorkTimeService.formatMinutes(day.targetMinutes()),
                WorkTimeService.formatSignedMinutes(day.overtimeMinutes())));

        dayWarningBadges.removeAll();
        if (day.weekend()) {
            dayWarningBadges.add(createHintBadge(getTranslation("worktime.day.weekend"), "contrast"));
        }
        if (day.overDailyLimit()) {
            dayWarningBadges.add(createHintBadge(
                    getTranslation("worktime.hint.over-limit", stripTrailingZeros(profile.getDailyWarningHours())),
                    "error"));
        }
        if (day.shortRestViolation()) {
            dayWarningBadges.add(createHintBadge(
                    getTranslation("worktime.hint.short-rest", stripTrailingZeros(profile.getMinRestHours())),
                    "error"));
        }

        if (editingEntryId == null) {
            prefillSmartBlockTimes(entries);
        }
    }

    private void refreshPeriodSummary() {
        if (selectedProfile == null) {
            return;
        }
        WorkTimeProfile profile = selectedProfile.profile();
        LocalDate[] range = resolveDateRange(periodSelector.getValue(), profile.getId());
        PeriodSummary summary = workTimeService.summarizePeriod(profile, range[0], range[1]);

        totalValue.setText(WorkTimeService.formatMinutes(summary.totalWorkMinutes())
                + " (" + WorkTimeService.formatSignedMinutes(summary.overtimeMinutes()) + ")");
        totalSub.setText(getTranslation(
                "worktime.kpi.total.sub",
                WorkTimeService.formatMinutes(summary.targetMinutes()),
                WorkTimeService.formatSignedMinutes(summary.overtimeMinutes())));

        breakdownValue.setText(WorkTimeService.formatMinutes(summary.schoolMinutes())
                + " / " + WorkTimeService.formatMinutes(summary.homeOfficeMinutes()));
        breakdownSub.setText(getTranslation(
                "worktime.kpi.breakdown.sub",
                WorkTimeService.formatMinutes(summary.schoolMinutes()),
                WorkTimeService.formatMinutes(summary.homeOfficeMinutes()),
                WorkTimeService.formatMinutes(summary.meetingMinutes()),
                WorkTimeService.formatMinutes(summary.otherMinutes())));

        offHoursValue.setText(WorkTimeService.formatMinutes(summary.eveningMinutes())
                + " / " + WorkTimeService.formatMinutes(summary.weekendMinutes()));
        offHoursSub.setText(getTranslation(
                "worktime.kpi.offhours.sub",
                WorkTimeService.formatMinutes(summary.eveningMinutes()),
                WorkTimeService.formatMinutes(summary.weekendMinutes())));

        int alerts = summary.daysOverDailyLimit() + summary.shortRestCount();
        healthValue.removeClassNames(LumoUtility.TextColor.ERROR, LumoUtility.TextColor.SUCCESS);
        if (alerts > 0) {
            healthValue.setText(String.valueOf(alerts));
            healthValue.addClassNames(LumoUtility.TextColor.ERROR);
            healthSub.setText(getTranslation(
                    "worktime.kpi.health.sub",
                    summary.daysOverDailyLimit(),
                    stripTrailingZeros(profile.getDailyWarningHours()),
                    summary.shortRestCount(),
                    stripTrailingZeros(profile.getMinRestHours())));
        } else {
            healthValue.setText("0");
            healthValue.addClassNames(LumoUtility.TextColor.SUCCESS);
            healthSub.setText(getTranslation("worktime.kpi.health.ok"));
        }

        periodDaysGrid.setItems(summary.days());
    }

    private void saveBlock() {
        if (selectedProfile == null || selectedDatePicker.getValue() == null) {
            return;
        }
        try {
            workTimeService.saveEntry(
                    selectedProfile.profile().getId(),
                    editingEntryId,
                    selectedDatePicker.getValue(),
                    startTimePicker.getValue(),
                    endTimePicker.getValue(),
                    categoryPicker.getValue(),
                    Boolean.TRUE.equals(countsAsWorkBox.getValue()),
                    noteField.getValue());
            cancelEditing();
            refreshAll();
            Notification.show(getTranslation("worktime.entry.saved"), 2500, Notification.Position.BOTTOM_START);
        } catch (IllegalArgumentException exception) {
            Notification.show(
                    getTranslation("worktime.entry.error", exception.getMessage()),
                    5000,
                    Notification.Position.MIDDLE);
        }
    }

    private void startEditing(WorkTimeEntry entry) {
        editingEntryId = entry.getId();
        startTimePicker.setValue(entry.getStartTime());
        endTimePicker.setValue(entry.getEndTime());
        categoryPicker.setValue(entry.getCategory());
        countsAsWorkBox.setValue(entry.isCountsAsWork());
        noteField.setValue(entry.getNote() != null ? entry.getNote() : "");
        saveBlockButton.setText(getTranslation("worktime.entry.save"));
        saveBlockButton.setIcon(VaadinIcon.CHECK.create());
        cancelEditButton.setVisible(true);
    }

    private void cancelEditing() {
        editingEntryId = null;
        noteField.clear();
        saveBlockButton.setText(getTranslation("worktime.entry.add"));
        saveBlockButton.setIcon(VaadinIcon.PLUS.create());
        cancelEditButton.setVisible(false);
    }

    private void deleteBlock(WorkTimeEntry entry) {
        if (entry == null || entry.getId() == null) {
            return;
        }
        workTimeService.deleteEntry(entry.getId());
        if (entry.getId().equals(editingEntryId)) {
            cancelEditing();
        }
        refreshAll();
        Notification.show(getTranslation("worktime.entry.deleted"), 2500, Notification.Position.BOTTOM_START);
    }

    private void copyLastWeekSchoolBlocks() {
        if (selectedProfile == null || selectedDatePicker.getValue() == null) {
            return;
        }
        LocalDate target = selectedDatePicker.getValue();
        int copied = workTimeService.copyDayBlocks(
                selectedProfile.profile().getId(), target.minusDays(7), target, true);
        if (copied > 0) {
            refreshAll();
            Notification.show(getTranslation("worktime.day.copied", copied), 3000, Notification.Position.BOTTOM_START);
        } else {
            Notification.show(getTranslation("worktime.day.nothing-to-copy"), 3500, Notification.Position.MIDDLE);
        }
    }

    private void shiftSelectedDate(int deltaDays) {
        LocalDate current = selectedDatePicker.getValue() != null ? selectedDatePicker.getValue() : LocalDate.now();
        selectedDatePicker.setValue(current.plusDays(deltaDays));
    }

    private void prefillSmartBlockTimes(List<WorkTimeEntry> existingBlocks) {
        if (existingBlocks == null || existingBlocks.isEmpty()) {
            startTimePicker.setValue(LocalTime.of(7, 45));
            endTimePicker.setValue(LocalTime.of(13, 15));
            categoryPicker.setValue(WorkTimeCategory.SCHOOL);
            countsAsWorkBox.setValue(true);
            return;
        }
        LocalTime lastEnd = existingBlocks.get(existingBlocks.size() - 1).getEndTime();
        LocalTime suggestedStart = lastEnd.isBefore(LocalTime.of(22, 30)) ? lastEnd.plusMinutes(15) : lastEnd;
        LocalTime suggestedEnd = suggestedStart.isBefore(LocalTime.of(22, 15))
                ? suggestedStart.plusMinutes(90)
                : LocalTime.of(23, 45);
        if (!suggestedEnd.isAfter(suggestedStart)) {
            suggestedStart = LocalTime.of(19, 0);
            suggestedEnd = LocalTime.of(20, 30);
        }
        startTimePicker.setValue(suggestedStart);
        endTimePicker.setValue(suggestedEnd);
        categoryPicker.setValue(WorkTimeCategory.HOME_OFFICE);
        countsAsWorkBox.setValue(true);
    }

    private LocalDate[] resolveDateRange(PeriodFilter filter, UUID profileId) {
        LocalDate today = LocalDate.now();
        PeriodFilter effective = filter != null ? filter : PeriodFilter.THIS_WEEK;
        return switch (effective) {
            case THIS_WEEK -> {
                LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                yield new LocalDate[] { monday, monday.plusDays(6) };
            }
            case LAST_WEEK -> {
                LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1);
                yield new LocalDate[] { monday, monday.plusDays(6) };
            }
            case THIS_MONTH -> new LocalDate[] { today.withDayOfMonth(1), today.withDayOfMonth(today.lengthOfMonth()) };
            case LAST_MONTH -> {
                LocalDate first = today.minusMonths(1).withDayOfMonth(1);
                yield new LocalDate[] { first, first.withDayOfMonth(first.lengthOfMonth()) };
            }
            case ALL -> {
                LocalDate earliest = workTimeService.findEarliestEntryDate(profileId).orElse(today.minusMonths(1));
                yield new LocalDate[] { earliest, today };
            }
        };
    }

    private DownloadResponse buildCsvDownloadResponse() {
        if (selectedProfile == null) {
            byte[] empty = new byte[0];
            return new DownloadResponse(new ByteArrayInputStream(empty), "arbeitszeit.csv", "text/csv", 0);
        }
        WorkTimeProfile profile = selectedProfile.profile();
        LocalDate[] range = resolveDateRange(periodSelector.getValue(), profile.getId());
        String csv = workTimeService.buildCsvExport(profile, range[0], range[1]);
        byte[] bytes = ("\uFEFF" + csv).getBytes(StandardCharsets.UTF_8);
        String fileName = "arbeitszeit-" + selectedProfile.username() + "-" + range[0] + ".csv";
        return new DownloadResponse(
                new ByteArrayInputStream(bytes),
                fileName,
                "text/csv; charset=utf-8",
                bytes.length);
    }

    private void openOverloadNoticeDialog() {
        if (selectedProfile == null) {
            return;
        }
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle(getTranslation("worktime.overload.dialog.title"));
        dialog.setWidth("540px");
        dialog.setMaxWidth("95vw");

        TextField senderField = new TextField(getTranslation("worktime.overload.field.sender"));
        String defaultSender = selectedProfile.displayName().replaceAll("\\s*\\([^)]*\\)$", "");
        senderField.setValue(defaultSender);

        TextField schoolField = new TextField(getTranslation("worktime.overload.field.school"));
        TextArea recipientField = new TextArea(getTranslation("worktime.overload.field.recipient"));
        recipientField.setValue(getTranslation("worktime.overload.default.recipient"));
        recipientField.setMinHeight("70px");

        TextField copyToField = new TextField(getTranslation("worktime.overload.field.copy-to"));
        copyToField.setValue(getTranslation("worktime.overload.default.copy-to"));

        DatePicker dateField = new DatePicker(getTranslation("worktime.overload.field.date"));
        dateField.setValue(LocalDate.now());

        TextArea noteArea = new TextArea(getTranslation("worktime.overload.field.custom-note"));
        noteArea.setPlaceholder(getTranslation("worktime.overload.field.custom-note.placeholder"));
        noteArea.setMinHeight("90px");

        FormLayout form = new FormLayout(
                senderField, schoolField, recipientField, copyToField, dateField, noteArea);
        form.setResponsiveSteps(new FormLayout.ResponsiveStep("0", 1));

        Button downloadBtn = new Button(
                getTranslation("worktime.overload.button.download"),
                VaadinIcon.FILE_TEXT_O.create());
        downloadBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        DownloadHandler pdfHandler = DownloadHandler.fromInputStream(event -> {
            WorkTimeProfile profile = selectedProfile.profile();
            LocalDate[] range = resolveDateRange(periodSelector.getValue(), profile.getId());
            PeriodSummary summary = workTimeService.summarizePeriod(profile, range[0], range[1]);
            OverloadNoticeOptions options = new OverloadNoticeOptions(
                    senderField.getValue(),
                    schoolField.getValue(),
                    recipientField.getValue(),
                    copyToField.getValue(),
                    noteArea.getValue(),
                    dateField.getValue());
            byte[] pdf = overloadNoticePdfGenerator.generate(profile, summary, options);
            String fileName = "ueberlastungsanzeige-" + range[0] + "-bis-" + range[1] + ".pdf";
            return new DownloadResponse(new ByteArrayInputStream(pdf), fileName, "application/pdf", pdf.length);
        });
        Anchor pdfAnchor = new Anchor(pdfHandler, AttachmentType.DOWNLOAD, "");
        pdfAnchor.add(downloadBtn);

        Button closeBtn = new Button(getTranslation("worktime.entry.cancel"), e -> dialog.close());
        HorizontalLayout actions = new HorizontalLayout(closeBtn, pdfAnchor);
        actions.setWidthFull();
        actions.setJustifyContentMode(JustifyContentMode.END);

        dialog.add(form, actions);
        dialog.open();
    }

    private String formatWeekday(LocalDate date) {
        Locale locale = UI.getCurrent() != null ? UI.getCurrent().getLocale() : Locale.GERMAN;
        return date.getDayOfWeek().getDisplayName(TextStyle.SHORT, locale);
    }

    private String formatDayBlocksSummary(DaySummary day) {
        return day.entries().stream()
                .map(entry -> entry.getStartTime().format(TIME_FORMAT)
                        + "–" + entry.getEndTime().format(TIME_FORMAT)
                        + " (" + getTranslation("worktime.category." + entry.getCategory().name()) + ")")
                .collect(Collectors.joining(" · "));
    }

    private static String toPeriodKey(PeriodFilter filter) {
        return switch (filter) {
            case THIS_WEEK -> "this-week";
            case LAST_WEEK -> "last-week";
            case THIS_MONTH -> "this-month";
            case LAST_MONTH -> "last-month";
            case ALL -> "all";
        };
    }

    private static String stripTrailingZeros(BigDecimal value) {
        if (value == null) {
            return "0";
        }
        return value.stripTrailingZeros().toPlainString();
    }

    ComboBox<ProfileOption> getProfileSelectorForTest() {
        return profileSelector;
    }

    Span getContractBadgeForTest() {
        return contractBadge;
    }

    DatePicker getSelectedDatePickerForTest() {
        return selectedDatePicker;
    }

    TimePicker getStartTimePickerForTest() {
        return startTimePicker;
    }

    TimePicker getEndTimePickerForTest() {
        return endTimePicker;
    }

    ComboBox<WorkTimeCategory> getCategoryPickerForTest() {
        return categoryPicker;
    }

    Checkbox getCountsAsWorkBoxForTest() {
        return countsAsWorkBox;
    }

    TextField getNoteFieldForTest() {
        return noteField;
    }

    Button getSaveBlockButtonForTest() {
        return saveBlockButton;
    }

    Grid<WorkTimeEntry> getDayBlocksGridForTest() {
        return dayBlocksGrid;
    }

    Grid<DaySummary> getPeriodDaysGridForTest() {
        return periodDaysGrid;
    }
}
