package com.cryptoautotrader.api.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
@Slf4j
public class ApiTokenAuthFilter extends OncePerRequestFilter {

    private final String expectedToken;

    public ApiTokenAuthFilter(@Value("${api.auth.token}") String expectedToken) {
        this.expectedToken = expectedToken;
        if (expectedToken.equals("dev-token-change-me-in-production")) {
            log.warn("====================================================");
            log.warn("⚠️  API 인증 토큰이 기본값입니다. 운영 환경에서는");
            log.warn("   API_AUTH_TOKEN 환경변수를 반드시 변경하세요.");
            log.warn("====================================================");
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            if (token.equals(expectedToken)) {
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                        "admin", null, List.of(new SimpleGrantedAuthority("ROLE_USER"))
                );
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }
        chain.doFilter(request, response);
    }
}
