package com.moveinsync.rostersync.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.rostersync.common.error.GlobalExceptionHandler;
import com.moveinsync.rostersync.config.AppProperties;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

import java.io.IOException;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Stateless HTTP Basic over TLS (TLS terminated at the load balancer). Role checks are done here
 * per URL; tenant checks are done in every repository query (WHERE tenant_id = :callerTenant).
 */
@Configuration
public class SecurityConfig {

    private static final String HR = Role.HR_SYSTEM.name();
    private static final String TRANSPORT = Role.TRANSPORT_ADMIN.name();
    private static final String OPERATOR = Role.OPERATOR.name();
    private static final String EMPLOYEE = Role.EMPLOYEE.name();

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailsService(AppProperties props, PasswordEncoder encoder) {
        Map<String, TenantUser> users = props.security().users().stream()
                .map(u -> new TenantUser(u.username(), encoder.encode(u.password()), Role.valueOf(u.role()),
                        u.tenant(), u.employeeId()))
                .collect(Collectors.toUnmodifiableMap(TenantUser::getUsername, Function.identity()));
        return username -> {
            TenantUser u = users.get(username);
            if (u == null) {
                throw new UsernameNotFoundException("unknown user");
            }
            // return a copy so Spring's credential erasure never mutates the shared instance
            return new TenantUser(u.getUsername(), u.getPassword(), u.role(), u.tenantId(), u.employeeId());
        };
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, ObjectMapper mapper) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable) // stateless API, no cookies
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(b -> b.authenticationEntryPoint((req, res, ex) ->
                        writeProblem(res, mapper, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "valid credentials are required")))
                .exceptionHandling(e -> e.accessDeniedHandler((req, res, ex) ->
                        writeProblem(res, mapper, HttpStatus.FORBIDDEN, "FORBIDDEN", "your role is not allowed to perform this action")))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/**").hasRole(OPERATOR)
                        // HR system: real-time events and nightly export
                        .requestMatchers(HttpMethod.POST, "/api/v1/hr/**").hasRole(HR)
                        // reconciliation runs: HR can read its own runs, only operators decide
                        .requestMatchers(HttpMethod.POST, "/api/v1/reconciliations/*/approve",
                                "/api/v1/reconciliations/*/reject").hasRole(OPERATOR)
                        .requestMatchers(HttpMethod.GET, "/api/v1/reconciliations/**").hasAnyRole(OPERATOR, HR)
                        // employees
                        .requestMatchers(HttpMethod.GET, "/api/v1/me").hasRole(EMPLOYEE)
                        .requestMatchers(HttpMethod.PUT, "/api/v1/employees/*/transport").hasRole(TRANSPORT)
                        .requestMatchers(HttpMethod.GET, "/api/v1/employees/**").hasAnyRole(TRANSPORT, OPERATOR)
                        // bookings and offices
                        .requestMatchers("/api/v1/bookings/**").hasRole(TRANSPORT)
                        .requestMatchers(HttpMethod.GET, "/api/v1/offices").hasAnyRole(TRANSPORT, OPERATOR, HR)
                        .requestMatchers(HttpMethod.POST, "/api/v1/offices").hasAnyRole(TRANSPORT, OPERATOR)
                        // operations
                        .requestMatchers("/api/v1/ops/**").hasRole(OPERATOR)
                        .anyRequest().denyAll());
        return http.build();
    }

    private static void writeProblem(HttpServletResponse res, ObjectMapper mapper, HttpStatus status,
                                     String code, String detail) throws IOException {
        ProblemDetail pd = GlobalExceptionHandler.problem(status, code, detail);
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(res.getOutputStream(), pd);
    }
}
