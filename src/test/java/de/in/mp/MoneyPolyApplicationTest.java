package de.in.mp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.vaadin.flow.server.LoadDependenciesOnStartup;

class MoneyPolyApplicationTest {

    /**
     * The root route must not depend on lazily loaded component chunks,
     * otherwise grids and progress bars stay invisible on the initial load.
     */
    @Test
    void loadsAllFrontendDependenciesOnStartup() {
        assertThat(MoneyPolyApplication.class.isAnnotationPresent(LoadDependenciesOnStartup.class)).isTrue();
    }
}
