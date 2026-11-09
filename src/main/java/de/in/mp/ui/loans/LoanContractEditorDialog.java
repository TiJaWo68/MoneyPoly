package de.in.mp.ui.loans;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.MultiSelectComboBox;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent.JustifyContentMode;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.textfield.BigDecimalField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.binder.Binder;

import de.in.mp.admin.PersonService;
import de.in.mp.finance.Person;
import de.in.mp.loans.LoanContract;
import de.in.mp.loans.LoanContractService;
import de.in.mp.ui.admin.PersonEditorDialog;

/**
 * Modal editor for creating and updating a private loan contract.
 */
public class LoanContractEditorDialog extends Dialog {

    private final LoanContractService loanContractService;
    private final PersonService personService;
    private final PersonEditorDialog personEditorDialog;

    private final Binder<LoanContract> binder = new Binder<>(LoanContract.class);

    private final TextField designation = new TextField();
    private final MultiSelectComboBox<Person> debtors = new MultiSelectComboBox<>();
    private final Button newDebtorButton = new Button();
    private final BigDecimalField originalAmount = new BigDecimalField();
    private final BigDecimalField initialRepaid = new BigDecimalField();
    private final BigDecimalField annualInterestRate = new BigDecimalField();
    private final DatePicker startDate = new DatePicker();
    private final TextArea notes = new TextArea();

    private final Button saveButton = new Button();
    private final Button cancelButton = new Button();

    private LoanContract currentContract;
    private Runnable onSaved;
    private List<Person> persons = List.of();

    public LoanContractEditorDialog(LoanContractService loanContractService, PersonService personService) {
        this.loanContractService = loanContractService;
        this.personService = personService;
        this.personEditorDialog = new PersonEditorDialog(personService);

        configureFields();
        configureBinder();

        saveButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        saveButton.addClickListener(event -> save());
        cancelButton.addClickListener(event -> close());

        FormLayout form = new FormLayout(designation, debtors, newDebtorButton, originalAmount, initialRepaid,
                annualInterestRate, startDate, notes);
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
        this.currentContract = new LoanContract();
        setHeaderTitle(getTranslation("loans.editor.title.new"));
        binder.readBean(currentContract);
        debtors.clear();
        refreshPersons();
        open();
    }

    public void openForEdit(LoanContract contract, Runnable onSaved) {
        this.onSaved = onSaved;
        this.currentContract = contract;
        setHeaderTitle(getTranslation("loans.editor.title.edit"));
        binder.readBean(contract);
        refreshPersons();
        debtors.setValue(resolveDebtors(contract.getDebtorIds()));
        open();
    }

    private void configureFields() {
        designation.setLabel(getTranslation("loans.editor.field.designation"));
        debtors.setLabel(getTranslation("loans.editor.field.debtors"));
        originalAmount.setLabel(getTranslation("loans.editor.field.original-amount"));
        initialRepaid.setLabel(getTranslation("loans.editor.field.initial-repaid"));
        annualInterestRate.setLabel(getTranslation("loans.editor.field.annual-interest-rate"));
        startDate.setLabel(getTranslation("loans.editor.field.start-date"));
        notes.setLabel(getTranslation("loans.editor.field.notes"));
        saveButton.setText(getTranslation("loans.editor.button.save"));
        cancelButton.setText(getTranslation("loans.editor.button.cancel"));

        debtors.setItemLabelGenerator(Person::getName);

        newDebtorButton.setText(getTranslation("loans.editor.button.new-debtor"));
        newDebtorButton.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
        newDebtorButton.addClickListener(event -> openNewDebtorDialog());
    }

    private void configureBinder() {
        binder.forField(designation)
                .asRequired(getTranslation("loans.editor.error.designation-required"))
                .bind(LoanContract::getDesignation, LoanContract::setDesignation);

        binder.forField(originalAmount)
                .asRequired(getTranslation("loans.editor.error.original-amount-required"))
                .bind(LoanContract::getOriginalAmount, LoanContract::setOriginalAmount);

        binder.forField(initialRepaid)
                .bind(LoanContract::getInitialRepaid, LoanContract::setInitialRepaid);

        binder.forField(annualInterestRate)
                .bind(LoanContract::getAnnualInterestRate, LoanContract::setAnnualInterestRate);

        binder.forField(startDate)
                .asRequired(getTranslation("loans.editor.error.start-date-required"))
                .bind(LoanContract::getStartDate, LoanContract::setStartDate);

        binder.forField(notes)
                .bind(LoanContract::getNotes, LoanContract::setNotes);
    }

    private void refreshPersons() {
        persons = personService.findAll();
        debtors.setItems(persons);
    }

    private void openNewDebtorDialog() {
        personEditorDialog.openForCreate(this::selectNewDebtor);
    }

    private void selectNewDebtor(Person savedPerson) {
        refreshPersons();
        persons.stream()
                .filter(person -> person.getId() != null && person.getId().equals(savedPerson.getId()))
                .findFirst()
                .ifPresent(person -> {
                    Set<Person> selected = new HashSet<>(debtors.getValue());
                    selected.add(person);
                    debtors.setValue(selected);
                });
    }

    private Set<Person> resolveDebtors(Set<UUID> debtorIds) {
        if (debtorIds == null) {
            return Set.of();
        }
        return persons.stream()
                .filter(person -> debtorIds.contains(person.getId()))
                .collect(Collectors.toSet());
    }

    private void save() {
        if (!binder.writeBeanIfValid(currentContract)) {
            return;
        }
        Set<UUID> debtorIds = debtors.getValue().stream()
                .map(Person::getId)
                .collect(Collectors.toSet());
        if (debtorIds.isEmpty()) {
            Notification.show(getTranslation("loans.editor.error.debtors-required"), 4000,
                    Notification.Position.MIDDLE);
            return;
        }
        currentContract.setDebtorIds(debtorIds);

        try {
            loanContractService.save(currentContract);
            close();
            if (onSaved != null) {
                onSaved.run();
            }
        } catch (IllegalArgumentException exception) {
            Notification.show(getTranslation("loans.notification.error", exception.getMessage()), 5000,
                    Notification.Position.MIDDLE);
        }
    }
}
