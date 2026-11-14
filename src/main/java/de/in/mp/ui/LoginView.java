package de.in.mp.ui;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.login.LoginForm;
import com.vaadin.flow.component.login.LoginI18n;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.VaadinServletRequest;
import com.vaadin.flow.server.auth.AnonymousAllowed;
import com.vaadin.flow.theme.lumo.LumoUtility;

import de.in.mp.admin.DemoSeedService;
import de.in.mp.security.AppUser;
import de.in.mp.security.AppUserRepository;
import de.in.mp.security.GoogleOAuth2Config;
import de.in.mp.security.UserDetailsServiceImpl;
import de.in.mp.ui.setup.SetupView;

/**
 * Vaadin login view that submits local credentials to the Spring Security login
 * processing endpoint and offers an alternative Google OAuth2 sign-in.
 * <p>
 * The Google sign-in link is only rendered when a Google client id is
 * configured. While the database has no users, the view forwards every request
 * to the initial setup view.
 */
@Route("login")
@AnonymousAllowed
public class LoginView extends VerticalLayout implements BeforeEnterObserver, HasDynamicTitle {

    private static final String GOOGLE_AUTHORIZATION_URL = "/oauth2/authorization/google";
    private static final String LOGO_PATH = "/icons/moneypoly-logo.svg";
    private static final String LOGO_SIZE = "64px";
    private static final String ROOT_LOCATION = "/";

    private final AppUserRepository appUserRepository;
    private final UserDetailsServiceImpl userDetailsService;
    private final LoginForm loginForm = new LoginForm();

    public LoginView(AppUserRepository appUserRepository,
            @Value("${GOOGLE_CLIENT_ID:}") String googleClientId) {
        this(appUserRepository, null, googleClientId);
    }

    @Autowired
    public LoginView(AppUserRepository appUserRepository,
            @Autowired(required = false) UserDetailsServiceImpl userDetailsService,
            @Value("${GOOGLE_CLIENT_ID:}") String googleClientId) {
        this.appUserRepository = appUserRepository;
        this.userDetailsService = userDetailsService;
        setSizeFull();
        setJustifyContentMode(JustifyContentMode.CENTER);
        setAlignItems(Alignment.CENTER);

        loginForm.setAction("login");
        loginForm.setForgotPasswordButtonVisible(false);
        loginForm.setI18n(createLoginI18n());

        add(createBrandHeader(), loginForm);

        if (GoogleOAuth2Config.isConfigured(googleClientId)) {
            add(createGoogleLoginAnchor());
        }
        if (userDetailsService != null && appUserRepository.findByUsername(DemoSeedService.DEMO_USERNAME)
                .filter(AppUser::isEnabled)
                .isPresent()) {
            add(createDemoButton());
        }
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        if (appUserRepository.count() == 0) {
            event.forwardTo(SetupView.class);
            return;
        }

        if (event.getLocation().getQueryParameters().getParameters().containsKey("error")) {
            loginForm.setError(true);
        }
    }

    @Override
    public String getPageTitle() {
        return getTranslation("ui.login.page-title");
    }

    private VerticalLayout createBrandHeader() {
        Image logo = new Image(LOGO_PATH, getTranslation("ui.login.title"));
        logo.setWidth(LOGO_SIZE);
        logo.setHeight(LOGO_SIZE);

        H1 title = new H1(getTranslation("ui.login.title"));
        title.addClassNames(LumoUtility.Margin.NONE);

        Span subtitle = new Span(getTranslation("ui.login.subtitle"));
        subtitle.addClassNames(LumoUtility.TextColor.SECONDARY, LumoUtility.FontSize.MEDIUM);

        VerticalLayout brandHeader = new VerticalLayout(logo, title, subtitle);
        brandHeader.setPadding(false);
        brandHeader.setSpacing(false);
        brandHeader.setAlignItems(Alignment.CENTER);
        brandHeader.addClassNames(LumoUtility.Gap.XSMALL, LumoUtility.Margin.Bottom.SMALL);
        return brandHeader;
    }

    private Anchor createGoogleLoginAnchor() {
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
        return googleLogin;
    }

    private Button createDemoButton() {
        Button demoButton = new Button(getTranslation("ui.login.demo.button"), VaadinIcon.PLAY_CIRCLE_O.create());
        demoButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        demoButton.addClassName(LumoUtility.Margin.Top.SMALL);
        demoButton.addClickListener(event -> loginAsDemoUser());
        return demoButton;
    }

    private void loginAsDemoUser() {
        UserDetails userDetails = userDetailsService.loadUserByUsername(DemoSeedService.DEMO_USERNAME);
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                userDetails, null, userDetails.getAuthorities());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        String targetLocation = ROOT_LOCATION;
        VaadinServletRequest servletRequest = VaadinServletRequest.getCurrent();
        if (servletRequest != null) {
            servletRequest.getHttpServletRequest().getSession(true)
                    .setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
            String contextPath = servletRequest.getHttpServletRequest().getContextPath();
            if (contextPath != null && !contextPath.isEmpty()) {
                targetLocation = contextPath + ROOT_LOCATION;
            }
        }
        UI.getCurrent().getPage().setLocation(targetLocation);
    }

    LoginI18n createLoginI18n() {
        LoginI18n i18n = new LoginI18n();

        LoginI18n.Form form = new LoginI18n.Form();
        form.setTitle("");
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
