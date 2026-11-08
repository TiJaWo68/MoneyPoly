package de.in.mp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;

/**
 * Guards the Vaadin route registry against the {@code DuplicateNavigationTitleException}.
 * <p>
 * Vaadin rejects any route class that combines a static {@link PageTitle}
 * annotation with the {@link HasDynamicTitle} interface. This test scans all
 * {@code @Route} classes and mirrors exactly that validation.
 */
class RouteTitleConfigurationTest {

    private static final String UI_BASE_PACKAGE = "de.in.mp.ui";

    @Test
    void noRouteViewMixesPageTitleAndHasDynamicTitle() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Route.class));

        Set<Class<?>> conflictingRoutes = scanner.findCandidateComponents(UI_BASE_PACKAGE).stream()
                .map(beanDefinition -> loadClass(beanDefinition.getBeanClassName()))
                .filter(routeClass -> routeClass.isAnnotationPresent(PageTitle.class)
                        && HasDynamicTitle.class.isAssignableFrom(routeClass))
                .collect(Collectors.toSet());

        assertThat(conflictingRoutes)
                .as("Route views must not combine @PageTitle and HasDynamicTitle")
                .isEmpty();
    }

    private static Class<?> loadClass(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("Unable to load route class: " + className, exception);
        }
    }
}
