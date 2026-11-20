package de.in.mp.ui.worktime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.Route;

import de.in.mp.ui.layout.MainLayout;
import de.in.mp.worktime.WorkTimeCategory;
import de.in.mp.worktime.WorkTimeEntry;
import de.in.mp.worktime.WorkTimeProfile;
import de.in.mp.worktime.WorkTimeService;
import de.in.mp.worktime.WorkTimeSummary.DaySummary;
import de.in.mp.worktime.WorkTimeSummary.PeriodSummary;
import de.in.mp.worktime.WorkTimeSummary.ProfileOption;
import jakarta.annotation.security.PermitAll;

class WorkTimeViewTest {

    private UI ui;
    private WorkTimeService workTimeService;
    private WorkTimeProfile wifeProfile;
    private WorkTimeProfile secondProfile;

    @BeforeEach
    void setUp() {
        ui = new UI();
        UI.setCurrent(ui);

        workTimeService = mock(WorkTimeService.class);

        wifeProfile = new WorkTimeProfile();
        wifeProfile.setId(UUID.randomUUID());
        wifeProfile.setAppUserId(UUID.randomUUID());
        wifeProfile.setEnabled(true);
        wifeProfile.setFullTimeWeeklyHours(new BigDecimal("40.00"));
        wifeProfile.setFullTimeTeachingUnits(new BigDecimal("27.00"));
        wifeProfile.setContractTeachingUnits(new BigDecimal("20.00"));
        wifeProfile.setWorkDaysPerWeek(5);
        wifeProfile.setDailyWarningHours(new BigDecimal("10.00"));
        wifeProfile.setMinRestHours(new BigDecimal("11.00"));

        secondProfile = new WorkTimeProfile();
        secondProfile.setId(UUID.randomUUID());
        secondProfile.setAppUserId(UUID.randomUUID());
        secondProfile.setEnabled(true);
        secondProfile.setFullTimeWeeklyHours(new BigDecimal("40.00"));
        secondProfile.setFullTimeTeachingUnits(new BigDecimal("27.00"));
        secondProfile.setContractTeachingUnits(new BigDecimal("27.00"));
        secondProfile.setWorkDaysPerWeek(5);
        secondProfile.setDailyWarningHours(new BigDecimal("10.00"));
        secondProfile.setMinRestHours(new BigDecimal("11.00"));

        when(workTimeService.canCurrentUserAccessWorkTime()).thenReturn(true);
        when(workTimeService.summarizeDay(any(), any())).thenAnswer(inv -> {
            LocalDate date = inv.getArgument(1);
            return new DaySummary(date, List.of(), 0L, 356L, -356L, 0L, 0L, 0L, false, false, null, false);
        });
        when(workTimeService.summarizePeriod(any(), any(), any())).thenAnswer(inv -> {
            WorkTimeProfile profile = inv.getArgument(0);
            LocalDate from = inv.getArgument(1);
            LocalDate to = inv.getArgument(2);
            return new PeriodSummary(
                    profile.getId(), from, to, 0L, 356L, -356L,
                    0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0, 0, List.of());
        });
    }

    @AfterEach
    void tearDown() {
        UI.setCurrent(null);
    }

    @Test
    void routeAnnotationsMatchMainLayoutAndPermitAll() {
        Route route = WorkTimeView.class.getAnnotation(Route.class);
        assertThat(route).isNotNull();
        assertThat(route.value()).isEqualTo("work-time");
        assertThat(route.layout()).isEqualTo(MainLayout.class);
        assertThat(WorkTimeView.class.isAnnotationPresent(PermitAll.class)).isTrue();
    }

    @Test
    void redirectsUnauthorizedUserOnBeforeEnter() {
        when(workTimeService.getAccessibleProfilesForCurrentUser()).thenReturn(List.of());
        WorkTimeView view = new WorkTimeView(workTimeService);

        when(workTimeService.canCurrentUserAccessWorkTime()).thenReturn(false);
        BeforeEnterEvent event = mock(BeforeEnterEvent.class);
        view.beforeEnter(event);

        verify(event).forwardTo("");
    }

    @Test
    void hidesProfileSelectorWhenOnlyOneProfileAccessibleAndPrefillsMultipleBlocks() {
        when(workTimeService.getAccessibleProfilesForCurrentUser())
                .thenReturn(List.of(new ProfileOption(wifeProfile, "anna", "Anna (anna)")));

        WorkTimeView view = new WorkTimeView(workTimeService);

        assertThat(view.getProfileSelectorForTest().isVisible()).isFalse();
        assertThat(view.getStartTimePickerForTest().getValue()).isEqualTo(LocalTime.of(7, 45));
        assertThat(view.getEndTimePickerForTest().getValue()).isEqualTo(LocalTime.of(13, 15));
        assertThat(view.getCategoryPickerForTest().getValue()).isEqualTo(WorkTimeCategory.SCHOOL);

        WorkTimeEntry morning = new WorkTimeEntry();
        morning.setId(UUID.randomUUID());
        morning.setWorkDate(LocalDate.now());
        morning.setStartTime(LocalTime.of(7, 45));
        morning.setEndTime(LocalTime.of(13, 15));
        morning.setCategory(WorkTimeCategory.SCHOOL);
        morning.setCountsAsWork(true);
        morning.setNote("Vormittag");

        when(workTimeService.summarizeDay(eq(wifeProfile), any())).thenAnswer(inv -> new DaySummary(
                inv.getArgument(1), List.of(morning), 330L, 356L, -26L, 0L, 0L, 0L, false, false, null, false));

        view.getStartTimePickerForTest().setValue(LocalTime.of(7, 45));
        view.getEndTimePickerForTest().setValue(LocalTime.of(13, 15));
        view.getCategoryPickerForTest().setValue(WorkTimeCategory.SCHOOL);
        view.getNoteFieldForTest().setValue("Vormittag");
        view.getSaveBlockButtonForTest().click();

        verify(workTimeService).saveEntry(
                eq(wifeProfile.getId()),
                eq(null),
                eq(LocalDate.now()),
                eq(LocalTime.of(7, 45)),
                eq(LocalTime.of(13, 15)),
                eq(WorkTimeCategory.SCHOOL),
                eq(true),
                eq("Vormittag"));

        // After first block ends at 13:15, next block should default to 13:30–15:00 Home Office
        assertThat(view.getStartTimePickerForTest().getValue()).isEqualTo(LocalTime.of(13, 30));
        assertThat(view.getEndTimePickerForTest().getValue()).isEqualTo(LocalTime.of(15, 0));
        assertThat(view.getCategoryPickerForTest().getValue()).isEqualTo(WorkTimeCategory.HOME_OFFICE);
        assertThat(view.getDayBlocksGridForTest().isVisible()).isTrue();
    }

    @Test
    void showsProfileSelectorForAdminsManagingMultipleProfiles() {
        when(workTimeService.getAccessibleProfilesForCurrentUser()).thenReturn(List.of(
                new ProfileOption(wifeProfile, "anna", "Anna (anna)"),
                new ProfileOption(secondProfile, "till", "Till (till)")));

        WorkTimeView view = new WorkTimeView(workTimeService);

        assertThat(view.getProfileSelectorForTest().isVisible()).isTrue();
    }
}
