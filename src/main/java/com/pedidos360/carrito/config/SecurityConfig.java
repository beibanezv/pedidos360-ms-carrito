package com.pedidos360.carrito.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

  @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:https://login.microsoftonline.com/}")
  private String issuerUri;

  @Value("${azure.tenant-id:common}")
  private String tenantId;

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http
        .csrf(csrf -> csrf.disable())
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/actuator/**").permitAll()
            // Todos los endpoints requieren JWT; el scope se valida con @PreAuthorize en escritura
            .requestMatchers("/carrito/**").authenticated()
            .anyRequest().authenticated()
        )
        .oauth2ResourceServer(oauth2 -> oauth2
            .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));

    return http.build();
  }

  @Bean
  public JwtAuthenticationConverter jwtAuthenticationConverter() {
    JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();
    scopes.setAuthorityPrefix("SCOPE_");
    // por defecto lee "scope" y "scp" (Azure usa "scp")

    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(jwt -> {
      Collection<GrantedAuthority> authorities = new ArrayList<>(scopes.convert(jwt));
      // Azure AD roles vienen en claim "roles": ["Cliente","Admin"]
      List<String> roles = jwt.getClaimAsStringList("roles");
      if (roles != null) {
        for (String r : roles) {
          authorities.add(new SimpleGrantedAuthority("ROLE_" + r));
        }
      }
      return authorities;
    });
    return converter;
  }

  @Bean
  public JwtDecoder jwtDecoder() {
    // SIMPLIFICADO: estilo visto en clase — jwks = issuerUri + tenantId + "/discovery/v2.0/keys".
    // Sin tenant real ('common') no hay fetch en startup: sin token -> 401 sin tocar red.
    // Con tenant real valida firma contra JWKS de Azure.
    String base = (issuerUri != null && !issuerUri.isBlank())
        ? issuerUri.trim()
        : "https://login.microsoftonline.com/";
    if (!base.endsWith("/")) {
      base += "/";
    }
    String tid = (tenantId != null && !tenantId.isBlank()) ? tenantId.trim() : "common";
    return NimbusJwtDecoder.withJwkSetUri(base + tid + "/discovery/v2.0/keys").build();
  }
}
