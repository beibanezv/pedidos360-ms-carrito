package com.pedidos360.carrito.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
public class SecurityConfig {

  @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}")
  private String issuerUri;

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http
        .csrf(csrf -> csrf.disable())
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/actuator/**").permitAll()
            // carrito es privado: requiere JWT + scope o rol (ejemplo AGENTS.md: Cliente/Admin, Carrito.ReadWrite)
            // SIMPLIFICADO: si tu tenant usa otros nombres, cambia hasAnyAuthority aquí
            .requestMatchers("/carrito/**").hasAnyAuthority("SCOPE_Carrito.ReadWrite", "ROLE_Cliente", "ROLE_Admin")
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
    String issuer = (issuerUri != null && !issuerUri.isBlank()) ? issuerUri.trim() : "";
    boolean esPlaceholder = issuer.isEmpty()
        || issuer.contains("{")
        || issuer.contains("tenantid")
        || issuer.contains("__")
        || issuer.contains("common")
        || issuer.contains("REEMPLAZA")
        || issuer.contains("TENANT_ID");
    boolean pareceUuid = issuer.matches(".*[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}.*");

    if (esPlaceholder || !pareceUuid) {
      // local/test sin tenant real: no hacer discovery al arrancar (ver ms-productos)
      String jwkSetUri = "https://login.microsoftonline.com/common/discovery/v2.0/keys";
      return NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
    }
    return NimbusJwtDecoder.withIssuerLocation(issuer).build();
  }
}
