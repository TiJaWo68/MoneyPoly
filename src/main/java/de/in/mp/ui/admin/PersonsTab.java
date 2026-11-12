package de.in.mp.ui.admin;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.FlexComponent.JustifyContentMode;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.data.renderer.ComponentRenderer;

import de.in.mp.admin.PersonService;
import de.in.mp.finance.Person;
import de.in.mp.ui.GridActionButton;

/**
 * Tab 2: persons participating in household finances and private loans.
 */
public class PersonsTab extends VerticalLayout implements AdminTab {

    private final PersonService personService;
    private final PersonEditorDialog editorDialog;

    private final Grid<Person> grid = new Grid<>(Person.class, false);

    public PersonsTab(PersonService personService) {
        this.personService = personService;
        this.editorDialog = new PersonEditorDialog(personService);

        setSizeFull();
        setPadding(false);
        setSpacing(false);

        add(createHeader(), grid);
        configureGrid();
    }

    @Override
    public String getLabelKey() {
        return "admin.tab.persons";
    }

    @Override
    public Component asComponent() {
        return this;
    }

    @Override
    public void refresh() {
        grid.setItems(personService.findAll());
    }

    private HorizontalLayout createHeader() {
        Span title = new Span(getTranslation("admin.persons.title"));
        title.addClassName("admin-tab-title");

        Button newPersonButton = new Button(
                getTranslation("admin.persons.button.new-person"),
                VaadinIcon.PLUS.create());
        newPersonButton.setTooltipText(getTranslation("admin.persons.button.new-person"));
        newPersonButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        newPersonButton.addClickListener(event -> editorDialog.openForCreate(this::refresh));

        HorizontalLayout header = new HorizontalLayout(title, newPersonButton);
        header.setWidthFull();
        header.setDefaultVerticalComponentAlignment(Alignment.CENTER);
        header.expand(title);
        return header;
    }

    private void configureGrid() {
        grid.addColumn(Person::getName)
                .setHeader(getTranslation("admin.grid.column.name"))
                .setAutoWidth(true);

        grid.addColumn(new ComponentRenderer<>(this::renderRole))
                .setHeader(getTranslation("admin.grid.column.role"))
                .setAutoWidth(true);

        grid.addColumn(person -> person.getLoginEmail() != null ? person.getLoginEmail() : "")
                .setHeader(getTranslation("admin.grid.column.login-email"))
                .setAutoWidth(true);

        grid.addColumn(person -> personService.countActiveLoans(person.getId()))
                .setHeader(getTranslation("admin.grid.column.active-loans"))
                .setAutoWidth(true);

        grid.addColumn(new ComponentRenderer<>(this::renderActions))
                .setHeader(getTranslation("admin.grid.column.actions"))
                .setAutoWidth(true);

        grid.addThemeVariants(GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_COLUMN_BORDERS);
    }

    private Span renderRole(Person person) {
        Span badge = new Span(getTranslation("admin.person.role." + person.getRole().name()));
        badge.getElement().getThemeList().add("badge");
        badge.getElement().getThemeList().add("pill");
        return badge;
    }

    private HorizontalLayout renderActions(Person person) {
        Button editButton = GridActionButton.create(
                VaadinIcon.EDIT,
                getTranslation("admin.action.edit"),
                event -> editorDialog.openForEdit(person, this::refresh));

        Button deleteButton = GridActionButton.createDanger(
                VaadinIcon.TRASH,
                getTranslation("admin.action.delete"),
                event -> confirmDelete(person));

        return new HorizontalLayout(editButton, deleteButton);
    }

    private void confirmDelete(Person person) {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle(getTranslation("admin.person.confirm.delete.title"));

        VerticalLayout content = new VerticalLayout(
                new Span(getTranslation("admin.person.confirm.delete.text", person.getName())));

        Button cancelButton = new Button(getTranslation("admin.button.cancel"), event -> dialog.close());
        Button deleteButton = new Button(getTranslation("admin.action.delete"), event -> {
            dialog.close();
            deletePerson(person);
        });
        deleteButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_ERROR);

        HorizontalLayout actions = new HorizontalLayout(cancelButton, deleteButton);
        actions.setWidthFull();
        actions.setJustifyContentMode(JustifyContentMode.END);

        dialog.add(content, actions);
        dialog.open();
    }

    private void deletePerson(Person person) {
        try {
            personService.delete(person.getId());
            Notification.show(getTranslation("admin.notification.deleted"), 3000, Notification.Position.MIDDLE);
            refresh();
        } catch (IllegalArgumentException exception) {
            Notification.show(getTranslation("admin.notification.error", exception.getMessage()), 5000,
                    Notification.Position.MIDDLE);
        }
    }
}
