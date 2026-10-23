package com.akhester.auditui.page;

/** Optional: a bean implementing this gives the number shown on the Reviews link in the main navigation (cases waiting for the signed-in person). */
public interface NavBadges {
    int reviewsOpen();
}
