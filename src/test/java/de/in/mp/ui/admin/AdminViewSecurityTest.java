package de.in.mp.ui.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.vaadin.flow.router.Route;

import de.in.mp.ui.layout.MainLayout;
import jakarta.annotation.security.RolesAllowed;

class AdminViewSecurityTest {

    @Test
    void adminViewIsRestrictedToAdminRole() {
        RolesAllowed rolesAllowed = AdminView.class.getAnnotation(RolesAllowed.class);

        assertThat(rolesAllowed).isNotNull();
        assertThat(rolesAllowed.value()).containsExactly("ROLE_ADMIN");
    }

    @Test
    void adminViewIsRoutedAtAdminWithMainLayout() {
        Route route = AdminView.class.getAnnotation(Route.class);

        assertThat(route).isNotNull();
        assertThat(route.value()).isEqualTo("admin");
        assertThat(route.layout()).isEqualTo(MainLayout.class);
    }
}
