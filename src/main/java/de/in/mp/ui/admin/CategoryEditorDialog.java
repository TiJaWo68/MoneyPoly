package de.in.mp.ui.admin;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent.JustifyContentMode;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.binder.Binder;

import de.in.mp.admin.CategoryService;
import de.in.mp.finance.Category;

/**
 * Modal editor for creating and renaming a booking category.
 */
public class CategoryEditorDialog extends Dialog {

    private final CategoryService categoryService;

    private final Binder<Category> binder = new Binder<>(Category.class);

    private final TextField name = new TextField();

    private final Button saveButton = new Button();
    private final Button cancelButton = new Button();

    private Category currentCategory;
    private Runnable onSaved;

    public CategoryEditorDialog(CategoryService categoryService) {
        this.categoryService = categoryService;

        configureFields();
        configureBinder();

        saveButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        saveButton.addClickListener(event -> save());
        cancelButton.addClickListener(event -> close());

        FormLayout form = new FormLayout(name);
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
        this.currentCategory = new Category();
        setHeaderTitle(getTranslation("admin.category.editor.title.new"));
        binder.readBean(currentCategory);
        open();
    }

    public void openForEdit(Category category, Runnable onSaved) {
        this.onSaved = onSaved;
        this.currentCategory = category;
        setHeaderTitle(getTranslation("admin.category.editor.title.edit"));
        binder.readBean(category);
        open();
    }

    private void configureFields() {
        name.setLabel(getTranslation("admin.category.editor.field.name"));
        saveButton.setText(getTranslation("admin.category.editor.button.save"));
        cancelButton.setText(getTranslation("admin.category.editor.button.cancel"));
    }

    private void configureBinder() {
        binder.forField(name)
                .asRequired(getTranslation("admin.category.editor.error.name-required"))
                .bind(Category::getName, Category::setName);
    }

    private void save() {
        if (!binder.writeBeanIfValid(currentCategory)) {
            return;
        }
        try {
            categoryService.save(currentCategory);
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
