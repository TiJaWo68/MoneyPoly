package de.in.mp.ui.loans;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent.JustifyContentMode;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.textfield.BigDecimalField;
import com.vaadin.flow.component.textfield.TextField;

import de.in.mp.admin.PersonService;
import de.in.mp.finance.Person;
import de.in.mp.loans.LoanContract;
import de.in.mp.loans.LoanRepayment;
import de.in.mp.loans.LoanRepaymentService;
import de.in.mp.loans.PaymentMethod;

/**
 * Modal dialog for recording a manual loan repayment (bank, cash or external).
 */
public class LoanRepaymentEditorDialog extends Dialog {

    private final LoanRepaymentService loanRepaymentService;
    private final PersonService personService;

    private final DatePicker repaymentDate = new DatePicker();
    private final ComboBox<Person> payer = new ComboBox<>();
    private final BigDecimalField principalAmount = new BigDecimalField();
    private final BigDecimalField interestAmount = new BigDecimalField();
    private final ComboBox<PaymentMethod> paymentMethod = new ComboBox<>();
    private final TextField notes = new TextField();

    private final Button saveButton = new Button();
    private final Button cancelButton = new Button();

    private LoanContract currentContract;
    private Runnable onSaved;

    public LoanRepaymentEditorDialog(LoanRepaymentService loanRepaymentService, PersonService personService) {
        this.loanRepaymentService = loanRepaymentService;
        this.personService = personService;

        configureFields();

        saveButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        saveButton.addClickListener(event -> save());
        cancelButton.addClickListener(event -> close());

        FormLayout form = new FormLayout(repaymentDate, payer, principalAmount, interestAmount, paymentMethod, notes);
        form.setResponsiveSteps(new FormLayout.ResponsiveStep("0", 1));

        HorizontalLayout actions = new HorizontalLayout(cancelButton, saveButton);
        actions.setWidthFull();
        actions.setJustifyContentMode(JustifyContentMode.END);

        add(form, actions);
        setCloseOnEsc(true);
        setCloseOnOutsideClick(false);
    }

    public void openForCreate(LoanContract contract, Runnable onSaved) {
        this.onSaved = onSaved;
        this.currentContract = contract;
        setHeaderTitle(getTranslation("loans.repayment.title"));
        repaymentDate.setValue(LocalDate.now());
        principalAmount.setValue(BigDecimal.ZERO);
        interestAmount.setValue(BigDecimal.ZERO);
        notes.clear();
        refreshPayers();
        open();
    }

    private void configureFields() {
        repaymentDate.setLabel(getTranslation("loans.repayment.field.date"));
        payer.setLabel(getTranslation("loans.repayment.field.payer"));
        principalAmount.setLabel(getTranslation("loans.repayment.field.principal"));
        interestAmount.setLabel(getTranslation("loans.repayment.field.interest"));
        paymentMethod.setLabel(getTranslation("loans.repayment.field.method"));
        notes.setLabel(getTranslation("loans.repayment.field.note"));
        saveButton.setText(getTranslation("loans.repayment.button.save"));
        cancelButton.setText(getTranslation("loans.repayment.button.cancel"));

        payer.setItemLabelGenerator(Person::getName);
        paymentMethod.setItems(PaymentMethod.values());
        paymentMethod.setItemLabelGenerator(method -> getTranslation("loans.payment." + method.name()));
    }

    private void refreshPayers() {
        List<Person> debtors = personService.findAll().stream()
                .filter(person -> currentContract.getDebtorIds().contains(person.getId()))
                .toList();
        payer.setItems(debtors);
    }

    private void save() {
        if (payer.getValue() == null || repaymentDate.getValue() == null
                || paymentMethod.getValue() == null) {
            Notification.show(getTranslation("loans.notification.error", "Missing required field"), 4000,
                    Notification.Position.MIDDLE);
            return;
        }

        LoanRepayment repayment = new LoanRepayment();
        repayment.setLoanContractId(currentContract.getId());
        repayment.setPayerId(payer.getValue().getId());
        repayment.setRepaymentDate(repaymentDate.getValue());
        repayment.setPrincipalAmount(principalAmount.getValue() != null ? principalAmount.getValue()
                : BigDecimal.ZERO);
        repayment.setInterestAmount(interestAmount.getValue() != null ? interestAmount.getValue()
                : BigDecimal.ZERO);
        repayment.setPaymentMethod(paymentMethod.getValue());
        repayment.setNotes(notes.getValue());

        try {
            loanRepaymentService.record(repayment);
            close();
            if (onSaved != null) {
                onSaved.run();
            }
        } catch (RuntimeException exception) {
            Notification.show(getTranslation("loans.notification.error", exception.getMessage()), 5000,
                    Notification.Position.MIDDLE);
        }
    }
}
