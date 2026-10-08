package com.bhumi.commander.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // Stateless API with HTTP Basic and no cookies, so CSRF protection is not needed.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(Customizer.withDefaults())
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/error", "/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/alerts").hasRole("WEBHOOK")
                        .requestMatchers(HttpMethod.GET, "/api/**").hasAnyRole("VIEWER", "OPERATOR")
                        .requestMatchers("/api/**", "/debug/**").hasRole("OPERATOR")
                        .anyRequest().denyAll());
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService users(
            PasswordEncoder encoder,
            @Value("${COMMANDER_VIEWER_PASSWORD:}") String viewerPassword,
            @Value("${COMMANDER_OPERATOR_PASSWORD:}") String operatorPassword,
            @Value("${COMMANDER_WEBHOOK_PASSWORD:}") String webhookPassword) {

        require("COMMANDER_VIEWER_PASSWORD", viewerPassword);
        require("COMMANDER_OPERATOR_PASSWORD", operatorPassword);
        require("COMMANDER_WEBHOOK_PASSWORD", webhookPassword);

        return new InMemoryUserDetailsManager(
                User.withUsername("viewer").password(encoder.encode(viewerPassword)).roles("VIEWER").build(),
                User.withUsername("operator").password(encoder.encode(operatorPassword)).roles("OPERATOR").build(),
                User.withUsername("alertmanager").password(encoder.encode(webhookPassword)).roles("WEBHOOK").build());
    }

    private static void require(String name, String value) {
        if (value == null || value.length() < 12) {
            throw new IllegalStateException(
                    name + " must be set to at least 12 characters. In PowerShell, run: . .\\.env.ps1");
        }
    }
}