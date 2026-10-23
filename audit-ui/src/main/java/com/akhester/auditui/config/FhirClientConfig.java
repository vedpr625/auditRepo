package com.akhester.auditui.config;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IClientInterceptor;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.IHttpRequest;
import ca.uhn.fhir.rest.client.api.IHttpResponse;
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Creates and configures the HAPI FHIR R4 client that talks to Chester.
 *
 * Chester runs at chester.fhir.base-url (default: http://localhost:8080/fhir).
 * The FhirContext is a heavy object and must be a singleton — Spring @Bean
 * with default singleton scope handles this correctly.
 */
@Configuration
public class FhirClientConfig {

    @Value("${chester.fhir.base-url}")
    private String chesterBaseUrl;

    @Value("${chester.fhir.connect-timeout-ms:5000}")
    private int connectTimeoutMs;

    @Value("${chester.fhir.socket-timeout-ms:15000}")
    private int socketTimeoutMs;

    /**
     * Singleton FHIR R4 context — expensive to create, reuse everywhere.
     */
    @Bean
    public FhirContext fhirContext() {
        FhirContext ctx = FhirContext.forR4();
        // Disable startup metadata fetch — Chester may not be up when the UI starts
        ctx.getRestfulClientFactory().setServerValidationMode(ServerValidationModeEnum.NEVER);
        ctx.getRestfulClientFactory().setConnectTimeout(connectTimeoutMs);
        ctx.getRestfulClientFactory().setSocketTimeout(socketTimeoutMs);
        return ctx;
    }

    /**
     * HAPI FHIR generic client pointing at Chester.
     * Inject IGenericClient wherever you need to query Chester's /fhir endpoint.
     */
    @Bean
    public IGenericClient fhirClient(FhirContext fhirContext, ChesterTokenProvider tokens) {
        IGenericClient client = fhirContext.newRestfulGenericClient(chesterBaseUrl);
        // Token relay: each request carries the signed-in user's own access token.
        client.registerInterceptor(new IClientInterceptor() {
            @Override
            public void interceptRequest(IHttpRequest request) {
                request.addHeader("Authorization", "Bearer " + tokens.currentAccessToken());
            }

            @Override
            public void interceptResponse(IHttpResponse response) {
                // nothing to do
            }
        });
        return client;
    }
}
