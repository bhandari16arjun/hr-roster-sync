package com.moveinsync.rostersync.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * An authenticated caller. The tenant comes from the credential, NEVER from the request:
 * there is no API parameter that lets a caller choose which client's data to read.
 */
public final class TenantUser implements UserDetails {

    private final String username;
    private final String passwordHash;
    private final Role role;
    private final String tenantId;
    private final String employeeId; // only for EMPLOYEE users

    public TenantUser(String username, String passwordHash, Role role, String tenantId, String employeeId) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.tenantId = tenantId;
        this.employeeId = employeeId;
    }

    public Role role() {
        return role;
    }

    public String tenantId() {
        return tenantId;
    }

    public String employeeId() {
        return employeeId;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }
}
