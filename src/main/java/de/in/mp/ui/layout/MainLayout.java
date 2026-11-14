package de.in.mp.ui.layout;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.user.OAuth2User;

import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.component.applayout.DrawerToggle;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.sidenav.SideNav;
import com.vaadin.flow.component.sidenav.SideNavItem;
import com.vaadin.flow.router.QueryParameters;
import com.vaadin.flow.spring.security.AuthenticationContext;
import com.vaadin.flow.theme.lumo.LumoUtility;

import jakarta.annotation.security.PermitAll;

import de.in.mp.energy.EnergyHouse;
import de.in.mp.energy.EnergyHouseService;
import de.in.mp.finance.Household;
import de.in.mp.finance.HouseholdService;
import de.in.mp.ui.ResponsiveStyles;

/**
 * Application shell for all protected views: responsive navigation drawer,
 * header with session information and a logout action.
 * <p>
 * Accessible to every authenticated user via {@link PermitAll}. Individual
 * views may apply stricter rules without conflicting with this layout.
 */
@PermitAll
public class MainLayout extends AppLayout {

    private static final String ROLE_ADMIN = "ROLE_ADMIN";
    private static final String FINANCE_PATH = "finance";
    private static final String LOANS_PATH = "loans";
    private static final String ENERGY_PATH = "energy";

    private final AuthenticationContext authenticationContext;
    private final EnergyHouseService energyHouseService;
    private final HouseholdService householdService;

    public MainLayout(AuthenticationContext authenticationContext) {
        this(authenticationContext, null, null);
    }

    public MainLayout(
            AuthenticationContext authenticationContext,
            EnergyHouseService energyHouseService) {
        this(authenticationContext, energyHouseService, null);
    }

    @Autowired
    public MainLayout(
            AuthenticationContext authenticationContext,
            @Autowired(required = false) EnergyHouseService energyHouseService,
            @Autowired(required = false) HouseholdService householdService) {
        this.authenticationContext = authenticationContext;
        this.energyHouseService = energyHouseService;
        this.householdService = householdService;
        addToNavbar(createHeader());
        addToDrawer(createDrawer());
    }

    private HorizontalLayout createHeader() {
        DrawerToggle drawerToggle = new DrawerToggle();
        drawerToggle.setTooltipText(getTranslation("ui.header.toggle"));

        H1 title = new H1(getTranslation("ui.app.title"));
        title.addClassNames(LumoUtility.FontSize.MEDIUM, LumoUtility.Margin.NONE);

        String displayName = resolveDisplayName(SecurityContextHolder.getContext().getAuthentication());
        Span userBadge = new Span(getTranslation("ui.header.logged-in-as", displayName));
        userBadge.addClassNames(LumoUtility.TextColor.SECONDARY, LumoUtility.FontSize.SMALL);
        userBadge.addClassNames(LumoUtility.TextColor.SECONDARY, LumoUtility.FontSize.SMALL,
                ResponsiveStyles.HIDE_ON_SHORT_SCREEN);

        Button logoutButton = new Button(
                VaadinIcon.SIGN_OUT.create(),
                event -> authenticationContext.logout());
        logoutButton.setTooltipText(getTranslation("ui.header.logout"));
        logoutButton.setAriaLabel(getTranslation("ui.header.logout"));

        HorizontalLayout header = new HorizontalLayout(drawerToggle, title);
        if (householdService != null && householdService.isDemoUser()) {
            Span demoBadge = new Span(getTranslation("ui.header.demo-badge"));
            demoBadge.getElement().getThemeList().add("badge contrast small");
            header.add(demoBadge);
        }
        header.add(userBadge, logoutButton);
        header.setWidthFull();
        header.setDefaultVerticalComponentAlignment(Alignment.CENTER);
        header.expand(title);
        return header;
    }

    private SideNav createDrawer() {
        SideNav sideNav = new SideNav(getTranslation("ui.nav.title"));

        Set<String> grantedAuthorities = authenticationContext.getGrantedAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());

        boolean hasFinanceAccess = householdService == null || householdService.hasFinanceAccessForCurrentUser();
        boolean hasLoansAccess = householdService == null || householdService.hasAnyLoanAccessForCurrentUser();
        boolean hasEnergyAccess = energyHouseService == null || energyHouseService.hasAccessibleHouseForCurrentUser();
        navigationItems().stream()
                .filter(item -> isItemVisible(item, grantedAuthorities, hasFinanceAccess, hasLoansAccess, hasEnergyAccess))
                .forEach(item -> sideNav.addItem(toSideNavItem(item)));

        return sideNav;
    }

    private SideNavItem toSideNavItem(NavigationItem item) {
        if (FINANCE_PATH.equals(item.path())) {
            return buildFinanceTreeItem(item);
        }
        if (ENERGY_PATH.equals(item.path())) {
            return buildEnergyTreeItem(item);
        }
        SideNavItem navItem = new SideNavItem(getTranslation(item.labelKey()), item.path());
        navItem.setPrefixComponent(new Icon(item.icon()));
        navItem.setTooltipText(getTranslation(item.labelKey()));
        return navItem;
    }

    private SideNavItem buildFinanceTreeItem(NavigationItem item) {
        List<Household> households = householdService != null
                ? householdService.getAccessibleHouseholdsForCurrentUser()
                : List.of();
        if (households.size() > 1) {
            SideNavItem root = new SideNavItem(getTranslation(item.labelKey()));
            root.setPrefixComponent(new Icon(item.icon()));
            root.setTooltipText(getTranslation(item.labelKey()));
            for (Household household : households) {
                SideNavItem hNode = new SideNavItem(household.getName());
                hNode.setTooltipText(household.getName());
                String hId = household.getId().toString();
                hNode.addItem(createQueryNavItem("finance.tab.chart", FINANCE_PATH,
                        Map.of("household", hId, "tab", "chart")));
                hNode.addItem(createQueryNavItem("finance.tab.balances", FINANCE_PATH,
                        Map.of("household", hId, "tab", "balances")));
                hNode.addItem(createQueryNavItem("finance.tab.accounts", FINANCE_PATH,
                        Map.of("household", hId, "tab", "accounts")));
                root.addItem(hNode);
            }
            return root;
        }
        SideNavItem root = new SideNavItem(getTranslation(item.labelKey()), item.path());
        root.setPrefixComponent(new Icon(item.icon()));
        root.setTooltipText(getTranslation(item.labelKey()));
        root.addItem(createQueryNavItem("finance.tab.chart", FINANCE_PATH, Map.of("tab", "chart")));
        root.addItem(createQueryNavItem("finance.tab.balances", FINANCE_PATH, Map.of("tab", "balances")));
        root.addItem(createQueryNavItem("finance.tab.accounts", FINANCE_PATH, Map.of("tab", "accounts")));
        return root;
    }

    private SideNavItem buildEnergyTreeItem(NavigationItem item) {
        List<EnergyHouse> houses = energyHouseService != null
                ? energyHouseService.findAccessibleHousesForCurrentUser()
                : List.of();
        if (houses.size() > 1) {
            SideNavItem root = new SideNavItem(getTranslation(item.labelKey()));
            root.setPrefixComponent(new Icon(item.icon()));
            root.setTooltipText(getTranslation(item.labelKey()));
            for (EnergyHouse house : houses) {
                SideNavItem hNode = new SideNavItem(house.getName());
                hNode.setTooltipText(house.getName());
                String hId = house.getId().toString();
                hNode.addItem(createQueryNavItem("energy.tab.readings", ENERGY_PATH,
                        Map.of("house", hId, "tab", "readings")));
                hNode.addItem(createQueryNavItem("energy.tab.amortization", ENERGY_PATH,
                        Map.of("house", hId, "tab", "amortization")));
                hNode.addItem(createQueryNavItem("energy.tab.masterdata", ENERGY_PATH,
                        Map.of("house", hId, "tab", "masterdata")));
                root.addItem(hNode);
            }
            return root;
        }
        SideNavItem root = new SideNavItem(getTranslation(item.labelKey()), item.path());
        root.setPrefixComponent(new Icon(item.icon()));
        root.setTooltipText(getTranslation(item.labelKey()));
        root.addItem(createQueryNavItem("energy.tab.readings", ENERGY_PATH, Map.of("tab", "readings")));
        root.addItem(createQueryNavItem("energy.tab.amortization", ENERGY_PATH, Map.of("tab", "amortization")));
        root.addItem(createQueryNavItem("energy.tab.masterdata", ENERGY_PATH, Map.of("tab", "masterdata")));
        return root;
    }

    private SideNavItem createQueryNavItem(String labelKey, String path, Map<String, String> params) {
        String label = getTranslation(labelKey);
        SideNavItem child = new SideNavItem(label, path);
        child.setTooltipText(label);
        child.setQueryParameters(QueryParameters.simple(params));
        return child;
    }

    static List<NavigationItem> navigationItems() {
        return List.of(
                new NavigationItem("ui.nav.dashboard", "", VaadinIcon.DASHBOARD, Set.of()),
                new NavigationItem("ui.nav.finance", "finance", VaadinIcon.WALLET, Set.of()),
                new NavigationItem("ui.nav.loans", "loans", VaadinIcon.FAMILY, Set.of()),
                new NavigationItem("ui.nav.energy", "energy", VaadinIcon.BOLT, Set.of()),
                new NavigationItem("ui.nav.admin", "admin", VaadinIcon.COG, Set.of(ROLE_ADMIN)));
    }

    static boolean isItemVisible(NavigationItem item, Collection<String> grantedAuthorities) {
        return isItemVisible(item, grantedAuthorities, true);
    }

    static boolean isItemVisible(
            NavigationItem item, Collection<String> grantedAuthorities, boolean hasEnergyAccess) {
        return isItemVisible(item, grantedAuthorities, true, true, hasEnergyAccess);
    }

    static boolean isItemVisible(
            NavigationItem item,
            Collection<String> grantedAuthorities,
            boolean hasFinanceAccess,
            boolean hasLoansAccess,
            boolean hasEnergyAccess) {
        if (FINANCE_PATH.equals(item.path()) && !hasFinanceAccess) {
            return false;
        }
        if (LOANS_PATH.equals(item.path()) && !hasLoansAccess) {
            return false;
        }
        if (ENERGY_PATH.equals(item.path()) && !hasEnergyAccess) {
            return false;
        }
        return item.requiredRoles().isEmpty()
                || grantedAuthorities.stream().anyMatch(item.requiredRoles()::contains);
    }

    static String resolveDisplayName(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getPrincipal() == null) {
            return "";
        }

        Object principal = authentication.getPrincipal();
        if (principal instanceof UserDetails userDetails) {
            return userDetails.getUsername();
        }
        if (principal instanceof OAuth2User oAuth2User) {
            Object email = oAuth2User.getAttribute("email");
            return email != null ? email.toString() : oAuth2User.getName();
        }
        return authentication.getName();
    }

    record NavigationItem(String labelKey, String path, VaadinIcon icon, Set<String> requiredRoles) {
    }
}
