package com.example.stock;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.web.util.HttpSessionMutexListener;

@Configuration(proxyBeanMethods = false)
public class StockSecurityConfiguration {
    private static final Logger log = LoggerFactory.getLogger(StockSecurityConfiguration.class);

    @Bean
    SecurityFilterChain stockSecurity(HttpSecurity http, SecurityContextRepository contexts,
                                      CsrfTokenRepository csrfTokens) throws Exception {
        var paths = PathPatternRequestMatcher.withDefaults();
        var presenterWorkspace = new AndRequestMatcher(paths.matcher(HttpMethod.GET, "/sor-app.html"),
                request -> "presenter".equals(request.getParameter("mode")));
        var success = new SavedRequestAwareAuthenticationSuccessHandler();
        success.setDefaultTargetUrl("/sor-app.html?mode=presenter");
        success.setAlwaysUseDefaultTargetUrl(true);
        success.setRedirectStrategy(StockSecurityConfiguration::redirectWithinSite);

        var failure = new SimpleUrlAuthenticationFailureHandler("/presenter.html?error");
        failure.setRedirectStrategy(StockSecurityConfiguration::redirectWithinSite);

        http.authorizeHttpRequests(auth -> auth
                    .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                    .requestMatchers("/login.html", "/presenter.html", "/csrf", "/sor-auth.js", "/sor-app.css").permitAll()
                    .requestMatchers(HttpMethod.POST, "/access/open", "/access/connect", "/guest/start").permitAll()
                    .requestMatchers(presenterWorkspace).hasRole("TRADER")
                    .requestMatchers(HttpMethod.POST, "/api/guest/restart").hasRole("GUEST")
                    .requestMatchers(HttpMethod.POST, "/api/sor/quotes", "/api/sor/match", "/api/sor/reset",
                            "/api/sor/market-hours", "/api/account-access/presenter-code").hasRole("TRADER")
                    .requestMatchers("/api/sor/auto-market", "/api/sor/auto-market/**", "/api/sor/quote-events",
                            "/sor-check.html", "/trade.html", "/api/buy", "/api/sell").hasRole("TRADER")
                    .anyRequest().authenticated())
                .securityContext(security -> security.securityContextRepository(contexts))
                .csrf(csrf -> csrf.csrfTokenRepository(csrfTokens))
                .exceptionHandling(errors -> errors
                        .defaultAuthenticationEntryPointFor(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                                paths.matcher("/api/**"))
                        .defaultAuthenticationEntryPointFor((request, response, missing) ->
                                redirectWithinSite(request, response, "/presenter.html?session"), presenterWorkspace)
                        .defaultAuthenticationEntryPointFor((request, response, missing) ->
                                redirectWithinSite(request, response, "/login.html"), AnyRequestMatcher.INSTANCE)
                        .defaultAccessDeniedHandlerFor((request, response, denied) -> {
                            if (!(denied instanceof CsrfException)) {
                                response.sendError(HttpStatus.FORBIDDEN.value());
                                return;
                            }
                            String form = request.getServletPath().equals("/login") ? "/presenter.html" : "/login.html";
                            response.setStatus(HttpStatus.SEE_OTHER.value());
                            response.setHeader("Location", request.getContextPath() + form + "?expired");
                        }, new OrRequestMatcher(paths.matcher(HttpMethod.POST, "/login"),
                                paths.matcher(HttpMethod.POST, "/access/open"),
                                paths.matcher(HttpMethod.POST, "/access/connect"),
                                paths.matcher(HttpMethod.POST, "/access/continue")))
                        .defaultAccessDeniedHandlerFor((request, response, denied) ->
                                redirectWithinSite(request, response, "/presenter.html?session"), presenterWorkspace)
                        .defaultAccessDeniedHandlerFor(new AccessDeniedHandlerImpl(), AnyRequestMatcher.INSTANCE))
                .formLogin(form -> form.loginPage("/presenter.html")
                        .loginProcessingUrl("/login")
                        .successHandler(success)
                        .failureHandler(failure)
                        .permitAll())
                .logout(logout -> logout.logoutSuccessHandler((request, response, authentication) ->
                        redirectWithinSite(request, response, "/login.html?logout")).deleteCookies("JSESSIONID"));
        return http.build();
    }

    private static void redirectWithinSite(HttpServletRequest request, HttpServletResponse response, String path) {
        // Keep the browser's address and protocol when an HTTPS proxy uses HTTP to reach the app.
        response.setStatus(HttpStatus.FOUND.value());
        response.setHeader(HttpHeaders.LOCATION, request.getContextPath() + path);
    }

    @Bean
    SecurityContextRepository securityContexts() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    HttpSessionMutexListener sessionMutexListener() {
        return new HttpSessionMutexListener();
    }

    @Bean
    CsrfTokenRepository csrfTokens() {
        return new HttpSessionCsrfTokenRepository();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    @Bean
    UserDetailsService stockLogin(@Value("${stock.login.username:demo}") String username,
                                  @Value("${stock.login.password:}") String password,
                                  PasswordEncoder encoder) {
        if (password.isEmpty()) {
            password = UUID.randomUUID().toString();
            log.warn("Temporary SOR login password for {}: {}", username, password);
        }
        var user = User.withUsername(username).password(encoder.encode(password)).roles("TRADER").build();
        return new InMemoryUserDetailsManager(user);
    }
}
