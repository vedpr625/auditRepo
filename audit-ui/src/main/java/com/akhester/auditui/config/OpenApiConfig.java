package com.akhester.auditui.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.Scopes;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI 3.0 metadata for Swagger UI.
 *
 * Security scheme is pre-wired for Keycloak Authorization Code flow.
 * Replace the placeholder URLs with your real Keycloak realm URLs when ready.
 * Until then the REST API is open (no token required — see SecurityConfig).
 */
@Configuration
public class OpenApiConfig {

    // Replace these with your real Keycloak URLs when integrating
    @Value("${keycloak.auth-server-url:http://localhost:8180}")
    private String keycloakUrl;

    @Value("${keycloak.realm:chester}")
    private String keycloakRealm;

    @Bean
    public OpenAPI chesterOpenAPI() {
        String authUrl  = keycloakUrl + "/realms/" + keycloakRealm + "/protocol/openid-connect/auth";
        String tokenUrl = keycloakUrl + "/realms/" + keycloakRealm + "/protocol/openid-connect/token";

        return new OpenAPI()
            .info(new Info()
                .title("Audit Repository REST API")
                .version("1.0.0")
                .description("""
                    REST API for the Audit Repository IHE ATNA / BALP Audit Event system.

                    Provides access to AuditEvent resources stored in the audit repository (HAPI FHIR R4).

                    **Auth**: currently open. Keycloak JWT integration is pre-configured —
                    click **Authorize** and use your Keycloak credentials when ready.
                    """)
                .contact(new Contact()
                    .name("Audit Repository Team")
                    .email("admin@chester.local"))
                .license(new License()
                    .name("Private")
                    .url("#"))
            )
            .servers(List.of(
                new Server().url("/").description("Current server"),
                new Server().url("http://localhost:8081").description("Local dev")
            ))
            // Keycloak OAuth2 Authorization Code flow — plug in your realm URLs
            .components(new Components()
                .addSecuritySchemes("keycloak", new SecurityScheme()
                    .type(SecurityScheme.Type.OAUTH2)
                    .description("Keycloak OAuth2 — use your realm credentials")
                    .flows(new OAuthFlows()
                        .authorizationCode(new OAuthFlow()
                            .authorizationUrl(authUrl)
                            .tokenUrl(tokenUrl)
                            .scopes(new Scopes()
                                .addString("openid",  "OpenID Connect")
                                .addString("profile", "User profile")
                                .addString("email",   "User email")
                            )
                        )
                    )
                )
                // Also support raw Bearer token (for Postman / curl testing)
                .addSecuritySchemes("bearerAuth", new SecurityScheme()
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")
                    .description("Paste a Keycloak access token here")
                )
            )
            // Apply security globally — currently unenforced server-side (SecurityConfig permits /api/**)
            .addSecurityItem(new SecurityRequirement()
                .addList("keycloak")
                .addList("bearerAuth")
            );
    }
}
