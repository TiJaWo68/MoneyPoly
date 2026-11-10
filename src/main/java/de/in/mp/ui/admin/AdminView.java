package de.in.mp.ui.admin;

import java.util.LinkedHashMap;
import java.util.Map;

import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.Tabs;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.spring.security.AuthenticationContext;

import de.in.mp.admin.CategoryService;
import de.in.mp.admin.ClassificationRuleService;
import de.in.mp.admin.PersonService;
import de.in.mp.admin.SystemHealthService;
import de.in.mp.admin.UserService;
import de.in.mp.energy.EnergyOdsImportService;
import de.in.mp.importer.FinanceOdsImportService;
import de.in.mp.loans.LoanContractRepository;
import de.in.mp.ui.layout.MainLayout;
import jakarta.annotation.security.RolesAllowed;

/**
 * Administration area: master data, one-time data migrations and system
 * management, restricted to {@code ROLE_ADMIN}.
 */
@Route(value = "admin", layout = MainLayout.class)
@RolesAllowed("ROLE_ADMIN")
public class AdminView extends VerticalLayout implements HasDynamicTitle {

    private final Map<Tab, AdminTab> tabs = new LinkedHashMap<>();
    private final Tabs tabBar = new Tabs();
    private final VerticalLayout content = new VerticalLayout();

    public AdminView(UserService userService, PersonService personService,
            CategoryService categoryService, ClassificationRuleService classificationRuleService,
            SystemHealthService systemHealthService, AuthenticationContext authenticationContext,
            LoanContractRepository loanContractRepository, FinanceOdsImportService financeOdsImportService,
            EnergyOdsImportService energyOdsImportService) {
        setSizeFull();
        setPadding(true);
        setSpacing(false);

        tabBar.setWidthFull();
        content.setSizeFull();
        content.setPadding(false);

        registerTab(new UsersTab(userService, personService, authenticationContext));
        registerTab(new PersonsTab(personService));
        registerTab(new CategoriesTab(categoryService));
        registerTab(new ClassificationRulesTab(classificationRuleService, categoryService, loanContractRepository));
        registerTab(new DataMigrationTab(financeOdsImportService, energyOdsImportService));
        registerTab(new SystemHealthTab(systemHealthService));

        tabBar.addSelectedChangeListener(event -> showSelectedTab());

        add(tabBar, content);

        tabBar.setSelectedIndex(0);
    }

    @Override
    public String getPageTitle() {
        return getTranslation("admin.view.page-title");
    }

    private void registerTab(AdminTab adminTab) {
        Tab tab = new Tab(getTranslation(adminTab.getLabelKey()));
        tabs.put(tab, adminTab);
        tabBar.add(tab);
    }

    private void showSelectedTab() {
        AdminTab selected = tabs.get(tabBar.getSelectedTab());
        if (selected == null) {
            return;
        }
        content.removeAll();
        content.add(selected.asComponent());
        selected.refresh();
    }
}
