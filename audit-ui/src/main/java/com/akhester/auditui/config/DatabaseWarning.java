package com.akhester.auditui.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Review decisions are an audit trail; warn loudly when they are kept in the development H2 file. */
@Component
public class DatabaseWarning {
    private static final Logger log = LoggerFactory.getLogger(DatabaseWarning.class);

    @Value("${spring.datasource.url}")
    private String url;

    @EventListener(ApplicationReadyEvent.class)
    public void check() {
        if (url.startsWith("jdbc:h2")) {
            log.warn("Review decisions are stored in a local H2 file ({}). Set AUDIT_UI_DB_URL to a PostgreSQL database for production.", url);
        }
    }
}
