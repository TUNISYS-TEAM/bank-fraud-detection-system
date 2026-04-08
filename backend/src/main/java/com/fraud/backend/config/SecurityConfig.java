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
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
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
                "/api/auth/login", 
                "/api/v1/**",
                "/api/auth/csrf",   
                "/api/auth/request-otp",     
                "/api/auth/verify-otp",       
                "/api/auth/resend-verification", 
                "/api/messages/**",
                "/public/**",
                "/error",
                "/ws/**",
                "/ws*/**"
        };

        private final CookieBearerTokenResolver cookieBearerTokenResolver;

        public SecurityConfig(CookieBearerTokenResolver cookieBearerTokenResolver) {
                this.cookieBearerTokenResolver = cookieBearerTokenResolver;
        }

        @Bean
        public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
                // Configure CSRF with proper cookie settings
                CookieCsrfTokenRepository csrfTokenRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
                csrfTokenRepository.setCookiePath("/");
                
                csrfTokenRepository.setCookieCustomizer(cookie -> {
                    cookie.httpOnly(false);
                    cookie.secure(true);
                    cookie.sameSite("Strict");
                    cookie.path("/");
                });

                CsrfTokenRequestAttributeHandler requestHandler = new CsrfTokenRequestAttributeHandler();
                requestHandler.setCsrfRequestAttributeName(null);

                http
                                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                                .csrf(csrf -> csrf
                                        .csrfTokenRepository(csrfTokenRepository)
                                        .csrfTokenRequestHandler(requestHandler)
                                        .ignoringRequestMatchers(
                                                "/ws/**", 
                                                "/api/ws/**", 
                                                "/api/auth/login",
                                                "/api/auth/request-otp",
                                                "/api/auth/verify-otp",
                                                "/api/auth/resend-verification"
                                        )
                                )
                                .sessionManagement(session -> session
                                                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                                .authorizeHttpRequests(auth -> auth
                                                .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                                                .anyRequest().authenticated())
                                .oauth2ResourceServer(oauth2 -> oauth2
                                                .bearerTokenResolver(cookieBearerTokenResolver)
                                                .jwt(jwt -> jwt.jwtAuthenticationConverter(
                                                                jwtAuthenticationConverter())));

                return http.build();
        }

        @Bean
        public CorsConfigurationSource corsConfigurationSource() {
                CorsConfiguration configuration = new CorsConfiguration();
                configuration.setAllowedOrigins(Arrays.asList("http://localhost:4200"));
                configuration.setAllowedMethods(
                                Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD"));
                configuration.setAllowedHeaders(Arrays.asList(
                        "authorization", 
                        "content-type", 
                        "x-auth-token",
                        "x-xsrf-token", 
                        "x-requested-with", 
                        "accept", 
                        "origin", 
                        "x-csrf-token",
                        "X-XSRF-TOKEN"
                ));
                configuration.setExposedHeaders(Arrays.asList(
                        "Set-Cookie", 
                        "Authorization", 
                        "X-XSRF-TOKEN", 
                        "X-CSRF-TOKEN"
                ));
                configuration.setAllowCredentials(true);
                configuration.setMaxAge(3600L);

                UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
                source.registerCorsConfiguration("/**", configuration);
                return source;
        }

        @Bean
        public JwtAuthenticationConverter jwtAuthenticationConverter() {
                JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
                converter.setPrincipalClaimName("preferred_username");
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