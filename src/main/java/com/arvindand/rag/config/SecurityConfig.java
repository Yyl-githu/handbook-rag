package com.arvindand.rag.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Authentication for the handbook assistant.
 *
 * <p>The knowledge base is writable through the API (upload and delete), so an unauthenticated
 * deployment lets anyone burn API quota or wipe documents. This configuration gates every endpoint
 * behind a single account:
 *
 * <ul>
 *   <li>browser — form login at {@code /login.html}, session cookie afterwards
 *   <li>scripts — HTTP Basic on the same endpoints
 *   <li>health probe — {@code /actuator/health} stays open for container checks
 * </ul>
 *
 * <p>The password is read from {@code APP_PASSWORD}. When it is left at the development default the
 * application logs a warning on every start, because that default must never reach a shared host.
 *
 * @author Arvind Menon
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

  private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

  static final String DEFAULT_USERNAME = "admin";
  static final String DEFAULT_PASSWORD = "changeme";

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http.authorizeHttpRequests(
            auth ->
                auth
                    // Static shell of the login page and the SPA assets must load before login.
                    .requestMatchers(
                        "/login.html", "/assets/**", "/favicon.ico", "/error", "/actuator/health")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .formLogin(
            form ->
                form.loginPage("/login.html")
                    .loginProcessingUrl("/login")
                    .defaultSuccessUrl("/", true)
                    .failureUrl("/login.html?error")
                    .permitAll())
        .httpBasic(Customizer.withDefaults())
        .logout(logout -> logout.logoutUrl("/logout").logoutSuccessUrl("/login.html"))
        // Session cookies are SameSite=Lax (see application.yaml), so a cross-site POST does not
        // carry credentials; disabling CSRF keeps the JSON API usable for scripts.
        .csrf(csrf -> csrf.disable());
    return http.build();
  }

  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  UserDetailsService userDetailsService(
      PasswordEncoder encoder,
      @Value("${app.security.username:" + DEFAULT_USERNAME + "}") String username,
      @Value("${app.security.password:" + DEFAULT_PASSWORD + "}") String password) {

    if (DEFAULT_PASSWORD.equals(password)) {
      log.warn(
          "Using the default password for user '{}'; set APP_PASSWORD before exposing this service.",
          username);
    }
    return new InMemoryUserDetailsManager(
        User.withUsername(username).password(encoder.encode(password)).roles("USER").build());
  }
}