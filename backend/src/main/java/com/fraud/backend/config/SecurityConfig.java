package com.fraud.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.fraud.backend.config.security.CookieBearerTokenResolver;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

        private static final String[] PUBLIC_ENDPOINTS = {
                "/swagger-ui/**",
                "/swagger-ui.html",
                "/v3/api-docs/**",
                "/api-docs/**",
                "/actuator/**",
                "/auth/**",
                "/public/**",
                "/error",
                "/ws/**",
                "/ws*/**"
        };

        private final CookieBearerTokenResolver cookieBearerTokenResolver;

        // Constructor injection
        public SecurityConfig(CookieBearerTokenResolver cookieBearerTokenResolver) {
                this.cookieBearerTokenResolver = cookieBearerTokenResolver;
        }

        @Bean
        public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
                http
                                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                                .csrf(csrf -> csrf.disable())
                                .sessionManagement(session -> session
                                                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                                .authorizeHttpRequests(auth -> auth
                                                .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                                                .anyRequest().authenticated())
                                .oauth2ResourceServer(oauth2 -> oauth2
                                                .bearerTokenResolver(cookieBearerTokenResolver) // Use the injected
                                                                                                // field
                                                .jwt(jwt -> jwt.jwtAuthenticationConverter(
                                                                jwtAuthenticationConverter())));

                return http.build();
        }

        // NO BEAN METHOD FOR cookieBearerTokenResolver HERE!

        @Bean
        public CorsConfigurationSource corsConfigurationSource() {
                CorsConfiguration configuration = new CorsConfiguration();
                configuration.setAllowedOrigins(Arrays.asList("http://localhost:4200"));
                configuration.setAllowedMethods(
                                Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD"));
                configuration.setAllowedHeaders(Arrays.asList("authorization", "content-type", "x-auth-token",
                                "x-xsrf-token", "x-requested-with", "accept", "origin"));
                configuration.setExposedHeaders(Arrays.asList("Set-Cookie", "Authorization", "X-XSRF-TOKEN"));
                configuration.setAllowCredentials(true);
                configuration.setMaxAge(3600L);

                UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
                source.registerCorsConfiguration("/**", configuration);
                return source;
        }

        @Bean
        public JwtAuthenticationConverter jwtAuthenticationConverter() {
                JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
                converter.setJwtGrantedAuthoritiesConverter(jwt -> {
                        var realmAccess = jwt.getClaimAsMap("realm_access");
                        if (realmAccess == null || realmAccess.isEmpty()) {
                                return List.of();
                        }

                        @SuppressWarnings("unchecked")
                        var roles = (List<String>) realmAccess.get("roles");
                        if (roles == null || roles.isEmpty()) {
                                return List.of();
                        }

                        return roles.stream()
                                        .map(role -> new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                                        "ROLE_" + role))
                                        .collect(Collectors.toList());
                });
                return converter;
        }

        @Bean
        public BCryptPasswordEncoder passwordEncoder() {
                return new BCryptPasswordEncoder();
        }
}