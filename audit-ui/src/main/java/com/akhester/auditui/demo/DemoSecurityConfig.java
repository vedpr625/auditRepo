package com.akhester.auditui.demo;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.stereotype.Component;

import com.akhester.auditui.config.ChesterTokenProvider;

/**
 * DEMONSTRATION BUILD (profile "demo"). No Keycloak, no Chester: a fixed list of made-up people signs in with a form, and the
 * app reads audit records from a stand-in Chester that runs inside the same program (see DemoChester). Never use for real data.
 */
@Configuration
@EnableWebSecurity
@org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
@Profile("demo")
public class DemoSecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() { return PasswordEncoderFactories.createDelegatingPasswordEncoder(); }

    @Bean
    public UserDetailsService demoUsers(PasswordEncoder enc) {
        List<org.springframework.security.core.userdetails.UserDetails> list = new ArrayList<>();
        for (DemoUsers.User u : DemoUsers.ALL) {
            List<GrantedAuthority> auth = new ArrayList<>();
            auth.add(new SimpleGrantedAuthority("ROLE_" + u.role()));
            if ("AUDIT_ADMIN".equals(u.role())) auth.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
            list.add(User.withUsername(u.username()).password(enc.encode(u.password())).authorities(auth).build());
        }
        return new InMemoryUserDetailsManager(list);
    }

    /** Metrics and health on the separate management port (bound to 127.0.0.1 by default). */
    @Bean
    @Order(1)
    public SecurityFilterChain managementChain(HttpSecurity http) throws Exception {
        http.securityMatcher(org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest.toAnyEndpoint())
            .authorizeHttpRequests(a -> a.anyRequest().permitAll()).csrf(c -> c.disable());
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/login", "/signed-out", "/error", "/css/**", "/js/**", "/fonts/**", "/img/**", "/favicon.ico").permitAll()
                // the stand-in Chester, registry and consent manager: only this program itself may call them (loopback, not via a proxy)
                .requestMatchers("/demo/**").access((authn, ctx) -> {
                    var req = ctx.getRequest();
                    String a = req.getRemoteAddr();
                    boolean loop = "127.0.0.1".equals(a) || "0:0:0:0:0:0:0:1".equals(a) || "::1".equals(a);
                    return new AuthorizationDecision(loop && req.getHeader("X-Forwarded-For") == null && req.getHeader("Forwarded") == null);
                })
                .requestMatchers(HttpMethod.POST, "/audit/review/**", "/api/v1/audit-events/*/review").hasAnyRole("ADMIN", "MINISTRY_AUDITOR", "PRIVACY_OFFICER")
                .requestMatchers("/admin/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/settings/registry").hasRole("ADMIN")
                .anyRequest().authenticated())
            // the console writes its own activity to the stand-in Chester (a loopback call with no browser, so no CSRF token)
            .csrf(c -> c.ignoringRequestMatchers("/demo/**"))
            // a request that carries an ended session goes to the "Your sign-in has expired" page
            .sessionManagement(sm -> sm.invalidSessionUrl("/signed-out"))
            .formLogin(f -> f.loginPage("/login").defaultSuccessUrl("/overview", true).failureUrl("/login?error"))
            .logout(l -> l.logoutUrl("/logout").logoutSuccessUrl("/login?logout").invalidateHttpSession(true).deleteCookies("JSESSIONID"));
        return http.build();
    }

    /** The token the demo build sends to its stand-in Chester: not secret and not signed, it only says who is asking. */
    @Component
    @Profile("demo")
    public static class DemoTokenProvider implements ChesterTokenProvider {
        @Override
        public String currentAccessToken() {
            Authentication a = SecurityContextHolder.getContext().getAuthentication();
            if (a == null || !a.isAuthenticated() || a instanceof AnonymousAuthenticationToken)
                throw new IllegalStateException("No signed-in user: refusing to call Chester without a user token");
            DemoUsers.User u = DemoUsers.ALL.stream().filter(x -> x.username().equals(a.getName())).findFirst().orElse(null);
            String roles = a.getAuthorities().stream().map(GrantedAuthority::getAuthority).filter(r -> r.startsWith("ROLE_") && !r.equals("ROLE_ADMIN"))
                .map(r -> r.substring(5)).collect(Collectors.joining(","));
            String body = a.getName() + "|" + roles + "|" + (u == null || u.facility() == null ? "" : u.facility());
            return "demo." + Base64.getUrlEncoder().withoutPadding().encodeToString(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
