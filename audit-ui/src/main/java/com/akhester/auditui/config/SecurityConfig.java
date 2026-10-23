package com.akhester.auditui.config;

import com.nimbusds.jwt.JWTParser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.*;

/**
 * Security: every page and every /api/** call requires a Keycloak (OpenID Connect) login. There are no local users.
 *
 * <p>Roles come from the access token's {@code realm_access.roles}. Each becomes {@code ROLE_<name>}; a user holding
 * one of {@code audit.ui.admin-roles} (default {@code AUDIT_ADMIN}) additionally gets {@code ROLE_ADMIN}, which
 * allows marking events reviewed. What a user may <em>see</em> is decided by Chester, which receives the user's own
 * token (see {@link ChesterTokenProvider}); the UI does not filter by role.
 */
@Configuration
@EnableWebSecurity
@org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
@org.springframework.context.annotation.Profile("!demo")
public class SecurityConfig {

    @Value("${audit.ui.admin-roles:AUDIT_ADMIN}")
    private List<String> adminRoles;

    @Value("${audit.ui.cors-origins:}")
    private List<String> corsOrigins;

    /**
     * Metrics and health on the separate management port (bound to 127.0.0.1 by default, see management.server.*).
     * Prometheus cannot do a browser login, so these two endpoints need no login; the network binding is the protection.
     * Other paths on that port stay behind the normal chain.
     */
    @Bean
    @org.springframework.core.annotation.Order(1)
    public SecurityFilterChain managementChain(HttpSecurity http) throws Exception {
        http.securityMatcher(org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest.toAnyEndpoint())
            .authorizeHttpRequests(a -> a.anyRequest().permitAll())
            .csrf(c -> c.disable());
        return http.build();
    }

    @Bean
    @org.springframework.core.annotation.Order(2)
    public SecurityFilterChain filterChain(HttpSecurity http, ClientRegistrationRepository registrations) throws Exception {
        OidcClientInitiatedLogoutSuccessHandler logout = new OidcClientInitiatedLogoutSuccessHandler(registrations);
        logout.setPostLogoutRedirectUri("{baseUrl}/login?logout");

        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/login", "/signed-out", "/error", "/css/**", "/js/**", "/fonts/**", "/img/**", "/favicon.ico").permitAll()
                // only UI admins may change review status (MVC form and REST)
                .requestMatchers(HttpMethod.POST, "/audit/review/**", "/api/v1/audit-events/*/review").hasAnyRole("ADMIN", "MINISTRY_AUDITOR", "PRIVACY_OFFICER")
                // fake registry / consent manager: exist only in demo mode (404 otherwise), called by this app itself
                // Only this app itself may call them (loopback, and not through a reverse proxy), so nobody can read the fake
                // registry directly and skip the consent check.
                .requestMatchers("/demo/**").access((authn, ctx) -> {
                    var req = ctx.getRequest();
                    boolean loop = "127.0.0.1".equals(req.getRemoteAddr()) || "0:0:0:0:0:0:0:1".equals(req.getRemoteAddr()) || "::1".equals(req.getRemoteAddr());
                    return new org.springframework.security.authorization.AuthorizationDecision(loop && req.getHeader("X-Forwarded-For") == null && req.getHeader("Forwarded") == null);
                })
                // Admin page and the registry address are for UI admins only
                .requestMatchers("/admin/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/settings/registry").hasRole("ADMIN")
                // everything else, including /api/** and Swagger, needs a login
                .anyRequest().authenticated()
            )
            // a request that carries an ended session goes to the "Your sign-in has expired" page
            .sessionManagement(sm -> sm.invalidSessionUrl("/signed-out"))
            .oauth2Login(o -> o
                .loginPage("/login")
                .defaultSuccessUrl("/overview", true)
                .userInfoEndpoint(u -> u.oidcUserService(oidcUserService()))
            )
            .logout(l -> l
                .logoutUrl("/logout")
                .logoutSuccessHandler(logout)
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID")
            );
        return http.build();
    }

    /** Adds ROLE_* authorities from the realm roles in the access token issued to this client. */
    @Bean
    public OAuth2UserService<OidcUserRequest, OidcUser> oidcUserService() {
        OidcUserService delegate = new OidcUserService();
        return request -> {
            OidcUser user = delegate.loadUser(request);
            Set<GrantedAuthority> authorities = new LinkedHashSet<>(user.getAuthorities());
            for (String role : realmRoles(request.getAccessToken().getTokenValue())) {
                // a realm role literally named ADMIN must not become the UI admin authority
                if (!"ADMIN".equals(role)) authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
                if (adminRoles.contains(role)) {
                    authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
                }
            }
            String nameKey = user.getClaims().containsKey("preferred_username") ? "preferred_username" : "sub";
            return new DefaultOidcUser(authorities, user.getIdToken(), user.getUserInfo(), nameKey);
        };
    }

    /**
     * Reads {@code realm_access.roles} from the access token. The token was just received directly from Keycloak's
     * token endpoint over TLS in the same login exchange, so it is parsed here without re-verifying the signature;
     * Chester independently verifies every token it receives.
     */
    static List<String> realmRoles(String accessToken) {
        try {
            Object ra = JWTParser.parse(accessToken).getJWTClaimsSet().getClaim("realm_access");
            if (ra instanceof Map<?, ?> m && m.get("roles") instanceof Collection<?> roles) {
                List<String> out = new ArrayList<>();
                roles.forEach(r -> out.add(String.valueOf(r)));
                return out;
            }
        } catch (Exception ignored) {
            // no readable roles: the user keeps only the default authorities
        }
        return List.of();
    }

    /** Lets the signed-in user's token be refreshed when it expires (Keycloak access tokens are short-lived). */
    @Bean
    public OAuth2AuthorizedClientManager authorizedClientManager(
            ClientRegistrationRepository registrations, OAuth2AuthorizedClientRepository clients) {
        DefaultOAuth2AuthorizedClientManager manager = new DefaultOAuth2AuthorizedClientManager(registrations, clients);
        manager.setAuthorizedClientProvider(
                OAuth2AuthorizedClientProviderBuilder.builder().authorizationCode().refreshToken().build());
        return manager;
    }

    /** Cross-origin access to /api/** only for origins listed in audit.ui.cors-origins (none by default). */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        List<String> origins = corsOrigins.stream().map(String::trim).filter(o -> !o.isEmpty()).toList();
        if (!origins.isEmpty()) {
            CorsConfiguration config = new CorsConfiguration();
            config.setAllowedOrigins(origins);
            config.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
            config.setAllowedHeaders(List.of("*"));
            config.setAllowCredentials(true);
            config.setMaxAge(3600L);
            source.registerCorsConfiguration("/api/**", config);
        }
        return source;
    }
}
