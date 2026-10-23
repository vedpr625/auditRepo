package com.akhester.auditui.config;

/** Supplies the bearer token sent to Chester for the signed-in person (see OidcChesterTokenProvider; the demo build has its own). */
public interface ChesterTokenProvider {
    String currentAccessToken();
}
