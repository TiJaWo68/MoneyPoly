package de.in.mp.ui;

import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.login.LoginForm;
import com.vaadin.flow.component.login.LoginI18n;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;
import com.vaadin.flow.theme.lumo.LumoUtility;

/**
 * Vaadin login view that submits local credentials to the Spring Security login
 * processing endpoint and offers an alternative Google OAuth2 sign-in.
 */
@Route("login")
@AnonymousAllowed
public class LoginView extends VerticalLayout implements BeforeEnterObserver, HasDynamicTitle {

    private static final String GOOGLE_AUTHORIZATION_URL = "/oauth2/authorization/google";

    private final LoginForm loginForm = new LoginForm();

    public LoginView() {
        setSizeFull();
        setJustifyContentMode(JustifyContentMode.CENTER);
        setAlignItems(Alignment.CENTER);

        loginForm.setAction("login");
        loginForm.setForgotPasswordButtonVisible(false);
        loginForm.setI18n(createLoginI18n());

        Anchor googleLogin = new Anchor(GOOGLE_AUTHORIZATION_URL, getTranslation("ui.login.oauth2.google"));
        googleLogin.setRouterIgnore(true);
        googleLogin.addClassNames(
                LumoUtility.Background.PRIMARY,
                LumoUtility.TextColor.PRIMARY_CONTRAST,
                LumoUtility.Padding.Horizontal.LARGE,
                LumoUtility.Padding.Vertical.SMALL,
                LumoUtility.BorderRadius.MEDIUM,
                LumoUtility.FontWeight.SEMIBOLD,
                LumoUtility.Margin.Top.MEDIUM);

        add(new H1(getTranslation("ui.login.title")), loginForm, googleLogin);
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        if (event.getLocation().getQueryParameters().getParameters().containsKey("error")) {
            loginForm.setError(true);
        }
    }

    @Override
    public String getPageTitle() {
        return getTranslation("ui.login.page-title");
    }

    private LoginI18n createLoginI18n() {
        LoginI18n i18n = new LoginI18n();

        LoginI18n.Form form = new LoginI18n.Form();
        form.setTitle(getTranslation("ui.login.form.title"));
        form.setUsername(getTranslation("ui.login.form.username"));
        form.setPassword(getTranslation("ui.login.form.password"));
        form.setSubmit(getTranslation("ui.login.form.submit"));
        i18n.setForm(form);

        LoginI18n.ErrorMessage errorMessage = new LoginI18n.ErrorMessage();
        errorMessage.setTitle(getTranslation("ui.login.error.title"));
        errorMessage.setMessage(getTranslation("ui.login.error.message"));
        errorMessage.setUsername(getTranslation("ui.login.error.username"));
        errorMessage.setPassword(getTranslation("ui.login.error.password"));
        i18n.setErrorMessage(errorMessage);

        return i18n;
    }
}
