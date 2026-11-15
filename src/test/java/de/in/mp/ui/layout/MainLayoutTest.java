package de.in.mp.ui.layout;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

import com.vaadin.flow.component.sidenav.SideNavItem;

import de.in.mp.finance.Household;
import de.in.mp.finance.HouseholdService;
import jakarta.annotation.security.PermitAll;

class MainLayoutTest {

    @Test
    void rendersCommonNavigationEntriesForEveryRole() {
        for (MainLayout.NavigationItem item : MainLayout.navigationItems()) {
            if (item.labelKey().equals("ui.nav.admin")) {
                continue;
            }
            assertThat(MainLayout.isItemVisible(item, Set.of("ROLE_USER")))
                    .as("Common entry %s should be visible for ROLE_USER", item.labelKey())
                    .isTrue();
        }
    }

    @Test
    void rendersAdminEntryOnlyForAdminRole() {
        MainLayout.NavigationItem admin = adminItem();

        assertThat(MainLayout.isItemVisible(admin, Set.of("ROLE_USER"))).isFalse();
        assertThat(MainLayout.isItemVisible(admin, Set.of("ROLE_ADMIN"))).isTrue();
    }

    @Test
    void exposesAllExpectedNavigationEntriesInOrder() {
        assertThat(MainLayout.navigationItems())
                .extracting(MainLayout.NavigationItem::labelKey)
                .containsExactly("ui.nav.dashboard", "ui.nav.finance", "ui.nav.loans", "ui.nav.energy", "ui.nav.admin");
    }

    @Test
    void resolvesDisplayNameForLocalUser() {
        UserDetails principal = User.withUsername("till").password("x").roles("USER").build();
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities());

        assertThat(MainLayout.resolveDisplayName(authentication)).isEqualTo("till");
    }

    @Test
    void resolvesDisplayNameForOAuth2UserByEmail() {
        OAuth2User principal = new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("ROLE_USER")),
                Map.of("sub", "google-sub", "email", "till@example.com"),
                "sub");
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities());

        assertThat(MainLayout.resolveDisplayName(authentication)).isEqualTo("till@example.com");
    }

    @Test
    void returnsEmptyDisplayNameWhenNotAuthenticated() {
        assertThat(MainLayout.resolveDisplayName(null)).isEmpty();
    }

    @Test
    void mainLayoutAllowsAuthenticatedAccess() {
        assertThat(MainLayout.class.isAnnotationPresent(PermitAll.class))
                .as("MainLayout must allow access for every authenticated user")
                .isTrue();
    }

    @Test
    void hidesEnergyEntryWhenUserHasNoAccessibleHouse() {
        MainLayout.NavigationItem energy = MainLayout.navigationItems().stream()
                .filter(item -> "energy".equals(item.path()))
                .findFirst()
                .orElseThrow();

        assertThat(MainLayout.isItemVisible(energy, Set.of("ROLE_USER"), false)).isFalse();
        assertThat(MainLayout.isItemVisible(energy, Set.of("ROLE_USER"), true)).isTrue();
    }

    @Test
    void hidesFinanceTabForBorrowerOnlyUserWhileKeepingLoansTabVisible() {
        MainLayout.NavigationItem finance = MainLayout.navigationItems().stream()
                .filter(item -> "finance".equals(item.path()))
                .findFirst()
                .orElseThrow();
        MainLayout.NavigationItem loans = MainLayout.navigationItems().stream()
                .filter(item -> "loans".equals(item.path()))
                .findFirst()
                .orElseThrow();

        assertThat(MainLayout.isItemVisible(finance, Set.of("ROLE_BORROWER"), false, true, false)).isFalse();
        assertThat(MainLayout.isItemVisible(loans, Set.of("ROLE_BORROWER"), false, true, false)).isTrue();
    }

    @Test
    void financeTreeIncludesExitPlanOnlyWhenHouseholdEnabledIt() {
        HouseholdService householdService = org.mockito.Mockito.mock(HouseholdService.class);
        Household disabled = new Household();
        disabled.setId(java.util.UUID.randomUUID());
        disabled.setName("Jung");
        disabled.setExitPlanEnabled(false);

        Household enabled = new Household();
        enabled.setId(java.util.UUID.randomUUID());
        enabled.setName("Reif");
        enabled.setExitPlanEnabled(true);

        org.mockito.Mockito.when(householdService.getAccessibleHouseholdsForCurrentUser())
                .thenReturn(List.of(disabled, enabled));

        org.springframework.security.core.Authentication auth = new UsernamePasswordAuthenticationToken(
                "user", null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
        try {
            SideNavItem financeTree = MainLayout.buildFinanceTreeItem(
                    new MainLayout.NavigationItem("ui.nav.finance", "finance",
                            com.vaadin.flow.component.icon.VaadinIcon.WALLET, Set.of()),
                    householdService,
                    key -> key);

            List<SideNavItem> householdNodes = financeTree.getItems();
            assertThat(householdNodes).hasSize(2);
            assertThat(householdNodes.get(0).getItems()).hasSize(3);
            assertThat(householdNodes.get(1).getItems()).hasSize(4);
            assertThat(householdNodes.get(1).getItems().get(3).getLabel()).isEqualTo("finance.tab.exit-plan");
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    private MainLayout.NavigationItem adminItem() {
        return MainLayout.navigationItems().stream()
                .filter(item -> item.labelKey().equals("ui.nav.admin"))
                .findFirst()
                .orElseThrow();
    }
}
