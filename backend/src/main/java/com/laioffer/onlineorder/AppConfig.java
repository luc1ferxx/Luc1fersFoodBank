package com.laioffer.onlineorder;


import com.laioffer.onlineorder.error.ApiErrorResponseWriter;
import com.laioffer.onlineorder.security.ManagementServerRequestMatcher;
import com.laioffer.onlineorder.security.RateLimitingFilter;
import com.laioffer.onlineorder.service.CustomerService;
import org.springframework.boot.security.autoconfigure.web.servlet.PathRequest;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.annotation.web.configurers.RequestCacheConfigurer;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.JdbcUserDetailsManager;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;


import javax.sql.DataSource;


@Configuration
public class AppConfig {


    @Bean
    UserDetailsManager users(DataSource dataSource) {
        JdbcUserDetailsManager userDetailsManager = new JdbcUserDetailsManager(dataSource);
        userDetailsManager.setCreateUserSql("INSERT INTO customers (email, password, enabled) VALUES (?,?,?)");
        userDetailsManager.setCreateAuthoritySql("INSERT INTO authorities (email, authority) values (?,?)");
        userDetailsManager.setUsersByUsernameQuery("""
                SELECT email,
                       password,
                       (enabled
                           AND account_status = 'ACTIVE'
                           AND (locked_until IS NULL OR locked_until <= CURRENT_TIMESTAMP)) AS enabled
                FROM customers
                WHERE LOWER(email) = LOWER(?)
                """);
        userDetailsManager.setAuthoritiesByUsernameQuery("SELECT email, authority FROM authorities WHERE LOWER(email) = LOWER(?)");
        return userDetailsManager;
    }


    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }


    @Bean
    public SecurityFilterChain filterChain(
            HttpSecurity http,
            RateLimitingFilter rateLimitingFilter,
            CustomerService customerService,
            ApiErrorResponseWriter apiErrorResponseWriter
    ) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .addFilterBefore(rateLimitingFilter, UsernamePasswordAuthenticationFilter.class)
                .headers(headers -> headers
                        .frameOptions(HeadersConfigurer.FrameOptionsConfig::sameOrigin)
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; " +
                                        "img-src 'self' data: https:; " +
                                        "style-src 'self' 'unsafe-inline'; " +
                                        "script-src 'self'; " +
                                        "connect-src 'self'; " +
                                        "frame-ancestors 'self'"
                        ))
                        .referrerPolicy(referrer -> referrer.policy(
                                ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN
                        ))
                        .addHeaderWriter(new StaticHeadersWriter("Permissions-Policy", "geolocation=(), microphone=(), camera=()"))
                )
                .authorizeHttpRequests(auth ->
                        auth
                                .requestMatchers(PathRequest.toStaticResources().atCommonLocations()).permitAll()
                                .requestMatchers(EndpointRequest.to("health", "info")).permitAll()
                                // Separate management port (in-cluster only): Prometheus scrapes and probes.
                                .requestMatchers(new ManagementServerRequestMatcher()).permitAll()
                                .requestMatchers(EndpointRequest.toAnyEndpoint()).hasRole("ADMIN")
                                .requestMatchers("/h2-console/**").permitAll()
                                .requestMatchers(HttpMethod.GET, "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**").permitAll()
                                .requestMatchers(HttpMethod.GET, "/", "/index.html", "/*.json", "/*.png", "/static/**").permitAll()
                                .requestMatchers(HttpMethod.POST, "/login", "/logout", "/signup").permitAll()
                                .requestMatchers(HttpMethod.GET, "/restaurants/**", "/restaurant/**").permitAll()
                                .requestMatchers(HttpMethod.GET, "/admin/outbox/events/failed").hasRole("ADMIN")
                                .requestMatchers(HttpMethod.POST, "/admin/outbox/events/*/retry").hasRole("ADMIN")
                                .requestMatchers(HttpMethod.PATCH, "/admin/menu-items/*", "/admin/restaurants/*").hasRole("ADMIN")
                                .requestMatchers(HttpMethod.PATCH, "/orders/*/status").hasRole("ADMIN")
                                .requestMatchers(HttpMethod.POST, "/dead-letters/*/replay").hasRole("ADMIN")
                                .requestMatchers("/admin/audit-log").hasRole("ADMIN")
                                // Fulfilment: merchant endpoints are scoped to the merchant's restaurant in the service.
                                .requestMatchers("/merchant/**").hasRole("MERCHANT")
                                .requestMatchers("/admin/deliveries/**", "/admin/deliveries").hasRole("ADMIN")
                                // Staff: provisioning is admin-only; riders reach only their own data (checked in the
                                // service, which also answers 403 for a deactivated merchant or rider).
                                .requestMatchers("/admin/merchants/**", "/admin/merchants").hasRole("ADMIN")
                                .requestMatchers("/admin/riders/**", "/admin/riders").hasRole("ADMIN")
                                .requestMatchers("/rider/**").hasRole("RIDER")
                                .anyRequest().authenticated()
                )
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint((request, response, ex) ->
                                apiErrorResponseWriter.write(
                                        request,
                                        response,
                                        HttpStatus.UNAUTHORIZED,
                                        "UNAUTHORIZED",
                                        "Authentication required"
                                ))
                        .accessDeniedHandler((request, response, ex) ->
                                apiErrorResponseWriter.write(
                                        request,
                                        response,
                                        HttpStatus.FORBIDDEN,
                                        "FORBIDDEN",
                                        "Access denied"
                                ))
                )
                // JSON API: nothing redirects to a saved request after login, so do not create a session per anonymous 401.
                .requestCache(RequestCacheConfigurer::disable)
                .sessionManagement(session -> session
                        .sessionFixation(sessionFixation -> sessionFixation.migrateSession())
                )
                .formLogin(form -> form
                        .successHandler((req, res, auth) -> {
                            customerService.recordSuccessfulLogin(auth.getName());
                            res.setStatus(HttpStatus.OK.value());
                        })
                        .failureHandler((req, res, ex) -> {
                            if (!(ex instanceof LockedException) && !(ex instanceof DisabledException)) {
                                customerService.recordFailedLoginAttempt(req.getParameter("username"));
                            }
                            apiErrorResponseWriter.write(
                                    req,
                                    res,
                                    HttpStatus.UNAUTHORIZED,
                                    "UNAUTHORIZED",
                                    "Invalid email or password"
                            );
                        })
                )
                .logout(logout -> logout
                        .deleteCookies("SESSION", "JSESSIONID")
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.OK))
                );
        return http.build();
    }
}
