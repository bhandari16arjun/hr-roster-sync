package com.moveinsync.rostersync.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Static helper to get the authenticated caller inside controllers. */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static TenantUser get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof TenantUser user)) {
            throw new IllegalStateException("no authenticated tenant user");
        }
        return user;
    }

    public static String tenantId() {
        return get().tenantId();
    }
}
