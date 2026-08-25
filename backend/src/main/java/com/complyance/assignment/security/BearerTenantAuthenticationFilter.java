package com.complyance.assignment.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Maps assignment-only bearer tokens to trusted server-side tenant principals. */
@Component
public class BearerTenantAuthenticationFilter extends OncePerRequestFilter {

    private final TenantTokenProperties properties;

    public BearerTenantAuthenticationFilter(TenantTokenProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        var authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.startsWith("Bearer ")) {
            var tenantId = properties.tokens().get(authorization.substring("Bearer ".length()));
            if (tenantId != null) {
                var principal = new TenantPrincipal(tenantId);
                SecurityContextHolder.getContext().setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
            }
        }
        filterChain.doFilter(request, response);
    }
}
