package de.in.mp.ui.admin;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.FlexComponent.JustifyContentMode;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.data.renderer.ComponentRenderer;

import de.in.mp.admin.CategoryService;
import de.in.mp.finance.Category;

/**
 * Tab 3: booking categories (category).
 */
public class CategoriesTab extends VerticalLayout implements AdminTab {

    private final CategoryService categoryService;
    private final CategoryEditorDialog editorDialog;

    private final Grid<Category> grid = new Grid<>(Category.class, false);

    public CategoriesTab(CategoryService categoryService) {
        this.categoryService = categoryService;
        this.editorDialog = new CategoryEditorDialog(categoryService);

        setSizeFull();
        setPadding(false);
        setSpacing(false);

        add(createHeader(), grid);
        configureGrid();
    }

    @Override
    public String getLabelKey() {
        return "admin.tab.categories";
    }

    @Override
    public Component asComponent() {
        return this;
    }

    @Override
    public void refresh() {
        grid.setItems(categoryService.findAll());
    }

    private HorizontalLayout createHeader() {
        Span title = new Span(getTranslation("admin.categories.title"));
        title.addClassName("admin-tab-title");

        Button newCategoryButton = new Button(getTranslation("admin.categories.button.new-category"));
        newCategoryButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        newCategoryButton.addClickListener(event -> editorDialog.openForCreate(this::refresh));

        HorizontalLayout header = new HorizontalLayout(title, newCategoryButton);
        header.setWidthFull();
        header.setDefaultVerticalComponentAlignment(Alignment.CENTER);
        header.expand(title);
        return header;
    }

    private void configureGrid() {
        grid.addColumn(Category::getName)
                .setHeader(getTranslation("admin.grid.column.name"))
                .setAutoWidth(true);

        grid.addColumn(category -> categoryService.countAllocations(category.getId()))
                .setHeader(getTranslation("admin.grid.column.allocation-count"))
                .setAutoWidth(true);

        grid.addColumn(new ComponentRenderer<>(this::renderActions))
                .setHeader(getTranslation("admin.grid.column.actions"))
                .setAutoWidth(true);

        grid.addThemeVariants(GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_COLUMN_BORDERS);
    }

    private HorizontalLayout renderActions(Category category) {
        Button editButton = new Button(getTranslation("admin.action.edit"),
                event -> editorDialog.openForEdit(category, this::refresh));
        editButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_SMALL);

        Button deleteButton = new Button(getTranslation("admin.action.delete"), event -> confirmDelete(category));
        deleteButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_SMALL,
                ButtonVariant.LUMO_ERROR);

        return new HorizontalLayout(editButton, deleteButton);
    }

    private void confirmDelete(Category category) {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle(getTranslation("admin.category.confirm.delete.title"));

        VerticalLayout content = new VerticalLayout(
                new Span(getTranslation("admin.category.confirm.delete.text", category.getName())));

        Button cancelButton = new Button(getTranslation("admin.button.cancel"), event -> dialog.close());
        Button deleteButton = new Button(getTranslation("admin.action.delete"), event -> {
            dialog.close();
            deleteCategory(category);
        });
        deleteButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_ERROR);

        HorizontalLayout actions = new HorizontalLayout(cancelButton, deleteButton);
        actions.setWidthFull();
        actions.setJustifyContentMode(JustifyContentMode.END);

        dialog.add(content, actions);
        dialog.open();
    }

    private void deleteCategory(Category category) {
        try {
            categoryService.delete(category.getId());
            Notification.show(getTranslation("admin.notification.deleted"), 3000, Notification.Position.MIDDLE);
            refresh();
        } catch (IllegalArgumentException exception) {
            Notification.show(getTranslation("admin.notification.error", exception.getMessage()), 5000,
                    Notification.Position.MIDDLE);
        }
    }
}
