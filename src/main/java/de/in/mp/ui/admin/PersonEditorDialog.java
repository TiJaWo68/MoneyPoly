package de.in.mp.ui.admin;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent.JustifyContentMode;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.textfield.EmailField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.binder.Binder;

import de.in.mp.admin.PersonService;
import de.in.mp.finance.Person;
import de.in.mp.finance.PersonRole;

/**
 * Modal editor for creating and updating a person.
 */
public class PersonEditorDialog extends Dialog {

    private final PersonService personService;

    private final Binder<Person> binder = new Binder<>(Person.class);

    private final TextField name = new TextField();
    private final ComboBox<PersonRole> role = new ComboBox<>();
    private final EmailField loginEmail = new EmailField();

    private final Button saveButton = new Button();
    private final Button cancelButton = new Button();

    private Person currentPerson;
    private Runnable onSaved;

    public PersonEditorDialog(PersonService personService) {
        this.personService = personService;

        configureFields();
        configureBinder();

        saveButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        saveButton.addClickListener(event -> save());
        cancelButton.addClickListener(event -> close());

        FormLayout form = new FormLayout(name, role, loginEmail);
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
        this.currentPerson = new Person();
        setHeaderTitle(getTranslation("admin.person.editor.title.new"));
        binder.readBean(currentPerson);
        open();
    }

    public void openForEdit(Person person, Runnable onSaved) {
        this.onSaved = onSaved;
        this.currentPerson = person;
        setHeaderTitle(getTranslation("admin.person.editor.title.edit"));
        binder.readBean(person);
        open();
    }

    private void configureFields() {
        name.setLabel(getTranslation("admin.person.editor.field.name"));
        role.setLabel(getTranslation("admin.person.editor.field.role"));
        loginEmail.setLabel(getTranslation("admin.person.editor.field.login-email"));
        saveButton.setText(getTranslation("admin.person.editor.button.save"));
        cancelButton.setText(getTranslation("admin.person.editor.button.cancel"));

        role.setItems(PersonRole.values());
        role.setItemLabelGenerator(value -> getTranslation("admin.person.role." + value.name()));
    }

    private void configureBinder() {
        binder.forField(name)
                .asRequired(getTranslation("admin.person.editor.error.name-required"))
                .bind(Person::getName, Person::setName);

        binder.forField(role)
                .asRequired(getTranslation("admin.person.editor.error.role-required"))
                .bind(Person::getRole, Person::setRole);

        binder.forField(loginEmail)
                .bind(Person::getLoginEmail, Person::setLoginEmail);
    }

    private void save() {
        if (!binder.writeBeanIfValid(currentPerson)) {
            return;
        }
        try {
            personService.save(currentPerson);
            close();
            if (onSaved != null) {
                onSaved.run();
            }
        } catch (IllegalArgumentException exception) {
            Notification.show(getTranslation("admin.notification.error", exception.getMessage()), 5000,
                    Notification.Position.MIDDLE);
        }
    }
}
