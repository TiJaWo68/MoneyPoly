package de.in.mp.ui.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.spring.security.AuthenticationContext;

import de.in.mp.admin.AppSettingService;
import de.in.mp.admin.CategoryService;
import de.in.mp.admin.ClassificationRuleService;
import de.in.mp.admin.DatabaseResetService;
import de.in.mp.admin.PersonService;
import de.in.mp.admin.SystemHealthService;
import de.in.mp.admin.UserService;
import de.in.mp.energy.EnergyOdsImportService;
import de.in.mp.importer.FinanceOdsImportService;
import de.in.mp.loans.LoanContractRepository;

class AdminViewTest {

    @Test
    void rendersSelectedTabContentImmediatelyOnInitialLoad() {
        UserService userService = mock(UserService.class);
        PersonService personService = mock(PersonService.class);
        CategoryService categoryService = mock(CategoryService.class);
        ClassificationRuleService classificationRuleService = mock(ClassificationRuleService.class);
        SystemHealthService systemHealthService = mock(SystemHealthService.class);
        AuthenticationContext authenticationContext = mock(AuthenticationContext.class);
        LoanContractRepository loanContractRepository = mock(LoanContractRepository.class);
        FinanceOdsImportService financeOdsImportService = mock(FinanceOdsImportService.class);
        EnergyOdsImportService energyOdsImportService = mock(EnergyOdsImportService.class);
        DatabaseResetService databaseResetService = mock(DatabaseResetService.class);
        AppSettingService appSettingService = mock(AppSettingService.class);

        AdminView view = new AdminView(userService, personService, categoryService, classificationRuleService,
                systemHealthService, authenticationContext, loanContractRepository,
                financeOdsImportService, energyOdsImportService, databaseResetService,
                appSettingService);

        VerticalLayout content = (VerticalLayout) view.getChildren()
                .filter(VerticalLayout.class::isInstance)
                .findFirst()
                .orElseThrow();

        assertThat(content.getChildren())
                .as("AdminView must immediately render the active tab content on first load without requiring a tab click")
                .isNotEmpty();
    }
}

