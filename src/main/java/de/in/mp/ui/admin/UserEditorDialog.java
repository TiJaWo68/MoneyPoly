package de.in.mp.ui.admin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.combobox.MultiSelectComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent.JustifyContentMode;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.textfield.BigDecimalField;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.binder.Binder;
import com.vaadin.flow.theme.lumo.LumoUtility;

import de.in.mp.admin.PersonService;
import de.in.mp.admin.UserService;
import de.in.mp.energy.EnergyHouse;
import de.in.mp.energy.EnergyHouseService;
import de.in.mp.energy.HouseAccessRole;
import de.in.mp.finance.Household;
import de.in.mp.finance.HouseholdService;
import de.in.mp.finance.Person;
import de.in.mp.security.AppRole;
import de.in.mp.security.AppUser;
import de.in.mp.security.AuthProvider;
import de.in.mp.worktime.WorkTimeProfile;
import de.in.mp.worktime.WorkTimeService;

/**
 * Modal editor for creating and updating a technical {@link AppUser} account.
 */
public class UserEditorDialog extends Dialog {

    private final UserService userService;
    private final PersonService personService;
    private final EnergyHouseService houseService;
    private final HouseholdService householdService;
    private final WorkTimeService workTimeService;

    private final Binder<AppUser> binder = new Binder<>(AppUser.class);

    private final TextField username = new TextField();
    private final TextField email = new TextField();
    private final ComboBox<AuthProvider> authProvider = new ComboBox<>();
    private final MultiSelectComboBox<AppRole> roles = new MultiSelectComboBox<>();
    private final ComboBox<Household> household = new ComboBox<>();
    private final ComboBox<Person> person = new ComboBox<>();
    private final MultiSelectComboBox<EnergyHouse> assignedHouses = new MultiSelectComboBox<>();
    private final ComboBox<HouseAccessRole> houseAccessRole = new ComboBox<>();
    private final Checkbox enabled = new Checkbox();
    private final PasswordField password = new PasswordField();

    private final Checkbox workTimeEnabled = new Checkbox();
    private final BigDecimalField contractTeachingUnits = new BigDecimalField();
    private final BigDecimalField fullTimeTeachingUnits = new BigDecimalField();
    private final BigDecimalField fullTimeWeeklyHours = new BigDecimalField();
    private final IntegerField workDaysPerWeek = new IntegerField();
    private final BigDecimalField dailyWarningHours = new BigDecimalField();
    private final BigDecimalField minRestHours = new BigDecimalField();
    private final Span workTimePreview = new Span();

    private final Button saveButton = new Button();
    private final Button cancelButton = new Button();

    private AppUser currentUser;
    private Runnable onSaved;
    private List<Person> persons = List.of();
    private List<Household> households = List.of();
    private List<EnergyHouse> houses = List.of();

    public UserEditorDialog(UserService userService, PersonService personService) {
        this(userService, personService, null, null, null);
    }

    public UserEditorDialog(UserService userService, PersonService personService, EnergyHouseService houseService) {
        this(userService, personService, houseService, null, null);
    }

    public UserEditorDialog(UserService userService, PersonService personService,
            EnergyHouseService houseService, HouseholdService householdService) {
        this(userService, personService, houseService, householdService, null);
    }

    public UserEditorDialog(UserService userService, PersonService personService,
            EnergyHouseService houseService, HouseholdService householdService,
            WorkTimeService workTimeService) {
        this.userService = userService;
        this.personService = personService;
        this.houseService = houseService;
        this.householdService = householdService;
        this.workTimeService = workTimeService;

        configureFields();
        configureBinder();

        authProvider.addValueChangeListener(event -> updateFieldVisibility());
        workTimeEnabled.addValueChangeListener(event -> updateWorkTimeFields());
        contractTeachingUnits.addValueChangeListener(event -> updateWorkTimePreview());
        fullTimeTeachingUnits.addValueChangeListener(event -> updateWorkTimePreview());
        fullTimeWeeklyHours.addValueChangeListener(event -> updateWorkTimePreview());
        workDaysPerWeek.addValueChangeListener(event -> updateWorkTimePreview());

        saveButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        saveButton.addClickListener(event -> save());
        cancelButton.addClickListener(event -> close());

        FormLayout form = new FormLayout(username, email, authProvider, roles);
        if (householdService != null) {
            form.add(household);
        }
        form.add(person);
        if (houseService != null) {
            form.add(assignedHouses, houseAccessRole);
        }
        form.add(enabled, password);
        if (workTimeService != null) {
            form.add(workTimeEnabled, contractTeachingUnits, fullTimeTeachingUnits,
                    fullTimeWeeklyHours, workDaysPerWeek, dailyWarningHours, minRestHours, workTimePreview);
        }
        form.setResponsiveSteps(new FormLayout.ResponsiveStep("0", 1));

        HorizontalLayout actions = new HorizontalLayout(cancelButton, saveButton);
        actions.setWidthFull();
        actions.setJustifyContentMode(JustifyContentMode.END);

        add(form, actions);
        setCloseOnEsc(true);
        setCloseOnOutsideClick(false);
    }

    public void openForCreate(Runnable onSaved) {
        this.onSaved = onSaved;
        this.currentUser = new AppUser();
        setHeaderTitle(getTranslation("admin.user.editor.title.new"));
        binder.readBean(currentUser);
        password.clear();
        refreshPersons();
        refreshHouseholds();
        household.clear();
        refreshHouses(Map.of());
        resetWorkTimeFields(Optional.empty());
        updateFieldVisibility();
        open();
    }

    public void openForEdit(AppUser user, Runnable onSaved) {
        this.onSaved = onSaved;
        this.currentUser = user;
        setHeaderTitle(getTranslation("admin.user.editor.title.edit"));
        binder.readBean(user);
        password.clear();
        refreshPersons();
        person.setValue(resolvePerson(user.getPersonId()));
        refreshHouseholds();
        household.setValue(resolveHousehold(user.getHouseholdId()));
        Map<UUID, HouseAccessRole> assignments = houseService != null && user.getId() != null
                ? houseService.findAssignmentsMapForUser(user.getId())
                : Map.of();
        refreshHouses(assignments);
        Optional<WorkTimeProfile> workTimeProfile = workTimeService != null && user.getId() != null
                ? workTimeService.findProfileForUser(user.getId())
                : Optional.empty();
        resetWorkTimeFields(workTimeProfile);
        updateFieldVisibility();
        open();
    }

    private void configureFields() {
        username.setLabel(getTranslation("admin.user.editor.field.username"));
        email.setLabel(getTranslation("admin.user.editor.field.email"));
        authProvider.setLabel(getTranslation("admin.user.editor.field.auth-provider"));
        roles.setLabel(getTranslation("admin.user.editor.field.roles"));
        household.setLabel(getTranslation("admin.user.editor.field.household"));
        household.setItemLabelGenerator(Household::getName);
        household.setClearButtonVisible(true);
        person.setLabel(getTranslation("admin.user.editor.field.person"));
        enabled.setLabel(getTranslation("admin.user.editor.field.enabled"));
        password.setLabel(getTranslation("admin.user.editor.field.password"));
        saveButton.setText(getTranslation("admin.user.editor.button.save"));
        cancelButton.setText(getTranslation("admin.user.editor.button.cancel"));

        authProvider.setItems(AuthProvider.values());
        authProvider.setItemLabelGenerator(provider -> getTranslation("admin.auth." + provider.name()));

        roles.setItems(AppRole.values());
        roles.setItemLabelGenerator(role -> getTranslation("admin.role." + role.name()));

        person.setItemLabelGenerator(Person::getName);
        person.setClearButtonVisible(true);

        assignedHouses.setLabel(getTranslation("admin.user.editor.field.houses"));
        assignedHouses.setItemLabelGenerator(EnergyHouse::getName);

        houseAccessRole.setLabel(getTranslation("admin.user.editor.field.house-role"));
        houseAccessRole.setItems(HouseAccessRole.values());
        houseAccessRole.setItemLabelGenerator(role -> getTranslation("energy.house.role." + role.name()));
        houseAccessRole.setValue(HouseAccessRole.EDITOR);

        workTimeEnabled.setLabel(getTranslation("admin.user.editor.worktime.enabled"));
        contractTeachingUnits.setLabel(getTranslation("admin.user.editor.worktime.contract-ue"));
        fullTimeTeachingUnits.setLabel(getTranslation("admin.user.editor.worktime.fulltime-ue"));
        fullTimeWeeklyHours.setLabel(getTranslation("admin.user.editor.worktime.fulltime-hours"));
        workDaysPerWeek.setLabel(getTranslation("admin.user.editor.worktime.work-days"));
        workDaysPerWeek.setMin(1);
        workDaysPerWeek.setMax(7);
        workDaysPerWeek.setStepButtonsVisible(true);
        dailyWarningHours.setLabel(getTranslation("admin.user.editor.worktime.daily-warning-hours"));
        minRestHours.setLabel(getTranslation("admin.user.editor.worktime.min-rest-hours"));
        workTimePreview.addClassNames(LumoUtility.FontSize.SMALL, LumoUtility.TextColor.SECONDARY);
    }

    private void configureBinder() {
        binder.forField(username)
                .asRequired(getTranslation("admin.user.editor.error.username-required"))
                .bind(AppUser::getUsername, AppUser::setUsername);

        binder.forField(email)
                .asRequired(getTranslation("admin.user.editor.error.email-required"))
                .bind(AppUser::getEmail, AppUser::setEmail);

        binder.forField(authProvider)
                .asRequired(getTranslation("admin.user.editor.error.auth-provider-required"))
                .bind(AppUser::getAuthProvider, AppUser::setAuthProvider);

        binder.forField(roles)
                .bind(AppUser::getRoles, AppUser::setRoles);

        binder.forField(enabled)
                .bind(AppUser::isEnabled, AppUser::setEnabled);
    }

    private void refreshPersons() {
        persons = personService.findAll();
        person.setItems(persons);
    }

    private void refreshHouses(Map<UUID, HouseAccessRole> existingAssignments) {
        if (houseService == null) {
            return;
        }
        houses = houseService.findAll();
        assignedHouses.setItems(houses);
        Set<EnergyHouse> selected = houses.stream()
                .filter(h -> existingAssignments.containsKey(h.getId()))
                .collect(Collectors.toSet());
        assignedHouses.setValue(selected);
        HouseAccessRole role = existingAssignments.values().stream()
                .findFirst()
                .orElse(HouseAccessRole.EDITOR);
        houseAccessRole.setValue(role);
    }

    private void refreshHouseholds() {
        households = householdService == null ? List.of() : householdService.findAll();
        household.setItems(households);
    }

    private Household resolveHousehold(UUID householdId) {
        if (householdId == null) {
            return null;
        }
        return households.stream()
                .filter(candidate -> householdId.equals(candidate.getId()))
                .findFirst()
                .orElse(null);
    }

    private Person resolvePerson(UUID personId) {
        if (personId == null) {
            return null;
        }
        return persons.stream()
                .filter(candidate -> personId.equals(candidate.getId()))
                .findFirst()
                .orElse(null);
    }

    private void updateFieldVisibility() {
        boolean local = AuthProvider.LOCAL.equals(authProvider.getValue());
        password.setVisible(local);
        if (!local) {
            password.clear();
        }
    }

    private void save() {
        if (!binder.writeBeanIfValid(currentUser)) {
            return;
        }
        currentUser.setPersonId(person.getValue() == null ? null : person.getValue().getId());
        if (householdService != null) {
            currentUser.setHouseholdId(household.getValue() == null ? null : household.getValue().getId());
        }

        String passwordValue = password.getValue();
        if (currentUser.getId() == null
                && AuthProvider.LOCAL.equals(currentUser.getAuthProvider())
                && (passwordValue == null || passwordValue.isBlank())) {
            Notification.show(getTranslation("admin.user.editor.error.password-required"), 4000,
                    Notification.Position.MIDDLE);
            return;
        }

        try {
            boolean shouldSaveWorkTime = workTimeService != null
                    && (Boolean.TRUE.equals(workTimeEnabled.getValue())
                            || (currentUser.getId() != null
                                    && workTimeService.findProfileForUser(currentUser.getId()).isPresent()));
            BigDecimal weeklyHoursValue = defaultIfNull(
                    fullTimeWeeklyHours.getValue(), WorkTimeProfile.DEFAULT_FULL_TIME_WEEKLY_HOURS);
            BigDecimal fullTimeUnitsValue = defaultIfNull(
                    fullTimeTeachingUnits.getValue(), WorkTimeProfile.DEFAULT_FULL_TIME_TEACHING_UNITS);
            BigDecimal contractUnitsValue = defaultIfNull(
                    contractTeachingUnits.getValue(), WorkTimeProfile.DEFAULT_CONTRACT_TEACHING_UNITS);
            int workDaysValue = workDaysPerWeek.getValue() != null
                    ? workDaysPerWeek.getValue()
                    : WorkTimeProfile.DEFAULT_WORK_DAYS_PER_WEEK;
            BigDecimal dailyWarnValue = defaultIfNull(
                    dailyWarningHours.getValue(), WorkTimeProfile.DEFAULT_DAILY_WARNING_HOURS);
            BigDecimal minRestValue = defaultIfNull(
                    minRestHours.getValue(), WorkTimeProfile.DEFAULT_MIN_REST_HOURS);

            AppUser saved = currentUser.getId() == null
                    ? userService.create(currentUser, passwordValue)
                    : userService.update(currentUser, passwordValue);
            if (houseService != null && saved.getId() != null) {
                HouseAccessRole selectedRole = houseAccessRole.getValue() != null
                        ? houseAccessRole.getValue()
                        : HouseAccessRole.EDITOR;
                Map<UUID, HouseAccessRole> assignments = new LinkedHashMap<>();
                for (EnergyHouse house : assignedHouses.getValue()) {
                    if (house.getId() != null) {
                        assignments.put(house.getId(), selectedRole);
                    }
                }
                houseService.replaceAssignmentsForUser(saved.getId(), assignments);
            }
            if (shouldSaveWorkTime && saved.getId() != null) {
                workTimeService.saveProfileForUser(
                        saved.getId(),
                        Boolean.TRUE.equals(workTimeEnabled.getValue()),
                        weeklyHoursValue,
                        fullTimeUnitsValue,
                        contractUnitsValue,
                        workDaysValue,
                        dailyWarnValue,
                        minRestValue);
            }
            close();
            if (onSaved != null) {
                onSaved.run();
            }
        } catch (IllegalArgumentException exception) {
            Notification.show(getTranslation("admin.notification.error", exception.getMessage()), 5000,
                    Notification.Position.MIDDLE);
        }
    }

    private void resetWorkTimeFields(Optional<WorkTimeProfile> profile) {
        if (workTimeService == null) {
            return;
        }
        WorkTimeProfile source = profile.orElse(null);
        workTimeEnabled.setValue(source != null && source.isEnabled());
        contractTeachingUnits.setValue(source != null
                ? source.getContractTeachingUnits()
                : WorkTimeProfile.DEFAULT_CONTRACT_TEACHING_UNITS);
        fullTimeTeachingUnits.setValue(source != null
                ? source.getFullTimeTeachingUnits()
                : WorkTimeProfile.DEFAULT_FULL_TIME_TEACHING_UNITS);
        fullTimeWeeklyHours.setValue(source != null
                ? source.getFullTimeWeeklyHours()
                : WorkTimeProfile.DEFAULT_FULL_TIME_WEEKLY_HOURS);
        workDaysPerWeek.setValue(source != null
                ? source.getWorkDaysPerWeek()
                : WorkTimeProfile.DEFAULT_WORK_DAYS_PER_WEEK);
        dailyWarningHours.setValue(source != null
                ? source.getDailyWarningHours()
                : WorkTimeProfile.DEFAULT_DAILY_WARNING_HOURS);
        minRestHours.setValue(source != null
                ? source.getMinRestHours()
                : WorkTimeProfile.DEFAULT_MIN_REST_HOURS);
        updateWorkTimeFields();
    }

    private void updateWorkTimeFields() {
        boolean visible = workTimeService != null && Boolean.TRUE.equals(workTimeEnabled.getValue());
        contractTeachingUnits.setVisible(visible);
        fullTimeTeachingUnits.setVisible(visible);
        fullTimeWeeklyHours.setVisible(visible);
        workDaysPerWeek.setVisible(visible);
        dailyWarningHours.setVisible(visible);
        minRestHours.setVisible(visible);
        workTimePreview.setVisible(visible);
        if (visible) {
            updateWorkTimePreview();
        }
    }

    private void updateWorkTimePreview() {
        if (workTimeService == null) {
            return;
        }
        WorkTimeProfile preview = new WorkTimeProfile();
        preview.setFullTimeWeeklyHours(defaultIfNull(
                fullTimeWeeklyHours.getValue(), WorkTimeProfile.DEFAULT_FULL_TIME_WEEKLY_HOURS));
        preview.setFullTimeTeachingUnits(defaultIfNull(
                fullTimeTeachingUnits.getValue(), WorkTimeProfile.DEFAULT_FULL_TIME_TEACHING_UNITS));
        preview.setContractTeachingUnits(defaultIfNull(
                contractTeachingUnits.getValue(), WorkTimeProfile.DEFAULT_CONTRACT_TEACHING_UNITS));
        preview.setWorkDaysPerWeek(workDaysPerWeek.getValue() != null
                ? workDaysPerWeek.getValue()
                : WorkTimeProfile.DEFAULT_WORK_DAYS_PER_WEEK);

        String pct = preview.getPartTimeRatio()
                .multiply(BigDecimal.valueOf(100))
                .setScale(1, RoundingMode.HALF_UP)
                .toPlainString();
        workTimePreview.setText(getTranslation(
                "admin.user.editor.worktime.preview",
                pct,
                WorkTimeService.formatMinutes(preview.getPaidWeeklyMinutes()),
                WorkTimeService.formatMinutes(preview.getPaidDailyMinutes())));
    }

    private static BigDecimal defaultIfNull(BigDecimal value, BigDecimal fallback) {
        return value != null && value.signum() > 0 ? value : fallback;
    }
}
