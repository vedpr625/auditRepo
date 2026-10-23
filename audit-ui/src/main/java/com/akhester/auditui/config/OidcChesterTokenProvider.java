package com.akhester.auditui.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Supplies the signed-in user's own Keycloak access token (refreshed if it has expired) so every call to Chester
 * is made as that user. Only usable on a request thread with a logged-in user; otherwise it fails closed.
 */
@Component
@org.springframework.context.annotation.Profile("!demo")
public class OidcChesterTokenProvider implements ChesterTokenProvider {

    private final OAuth2AuthorizedClientManager manager;

    public OidcChesterTokenProvider(OAuth2AuthorizedClientManager manager) {
        this.manager = manager;
    }

    @Override
    public String currentAccessToken() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof OAuth2AuthenticationToken token)) {
            throw new IllegalStateException("No signed-in user: refusing to call Chester without a user token");
        }
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            throw new IllegalStateException("Not on a request thread: cannot obtain the user's token");
        }
        OAuth2AuthorizeRequest request = OAuth2AuthorizeRequest
                .withClientRegistrationId(token.getAuthorizedClientRegistrationId())
                .principal(token)
                .attribute(HttpServletRequest.class.getName(), attrs.getRequest())
                .attribute(HttpServletResponse.class.getName(), attrs.getResponse())
                .build();
        OAuth2AuthorizedClient client = manager.authorize(request);
        if (client == null || client.getAccessToken() == null) {
            throw new IllegalStateException("The user's session has no usable access token; sign in again");
        }
        return client.getAccessToken().getTokenValue();
    }
}
