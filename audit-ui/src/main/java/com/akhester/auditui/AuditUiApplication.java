package com.akhester.auditui;

import java.util.Map;
import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AuditUiApplication {

    /**
     * Older names of the same time zones. The PostgreSQL JDBC driver sends the JVM's zone name to the server, and
     * a PostgreSQL built without the legacy names rejects the connection ("invalid value for parameter TimeZone").
     * Using the current name for the same zone changes nothing visible.
     */
    private static final Map<String, String> CURRENT_ZONE_NAME = Map.of(
        "Asia/Calcutta", "Asia/Kolkata",
        "Asia/Katmandu", "Asia/Kathmandu",
        "Asia/Saigon", "Asia/Ho_Chi_Minh",
        "Asia/Rangoon", "Asia/Yangon",
        "Europe/Kiev", "Europe/Kyiv",
        "America/Buenos_Aires", "America/Argentina/Buenos_Aires");

    public static void main(String[] args) {
        String current = CURRENT_ZONE_NAME.get(TimeZone.getDefault().getID());
        if (current != null) TimeZone.setDefault(TimeZone.getTimeZone(current));
        SpringApplication.run(AuditUiApplication.class, args);
    }
}
