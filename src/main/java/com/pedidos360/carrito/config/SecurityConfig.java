package com.pedidos360.carrito.config;

import com.nimbusds.jwt.JWTParser;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

  @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:https://login.microsoftonline.com/}")
  private String issuerUri;

  @Value("${azure.tenant-id:common}")
  private String tenantId;

  @Value("${azure.audience:api://446c57cb-aba2-4b7a-ab01-6f2e6af7d35c}")
  private String azureAudience;

  @Value("${cognito.jwks-uri:https://cognito-idp.us-east-1.amazonaws.com/us-east-1_A8lrPsDta/.well-known/jwks.json}")
  private String cognitoJwksUri;

  @Value("${cognito.issuer:}")
  private String cognitoIssuer;

  @Value("${cognito.client-id:2ba1fjeai3c2i77sb5dpd7j91m}")
  private String cognitoClientId;

  @Value("${cors.allowed-origins:http://localhost:4200}")
  private String allowedOrigins;

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http
        .csrf(csrf -> csrf.disable())
        .cors(cors -> cors.configurationSource(corsConfigurationSource()))
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
      // SIMPLIFICADO: Cognito no emite "scp" ni "roles": sin esto sus usuarios
      // pasarían la autenticación pero morirían con 403 en el @PreAuthorize del
      // carrito. Todo token válido de nuestro pool es comprador (ROLE_Cliente);
      // el catálogo sigue exigiendo ROLE_Admin, que solo existe en Azure.
      // Alternativa: mapear "cognito:groups" a roles cuando el pool tenga grupos.
      if (jwt.getClaimAsString("token_use") != null) {
        authorities.add(new SimpleGrantedAuthority("ROLE_Cliente"));
      }
      return authorities;
    });
    return converter;
  }

  // CORS: orígenes configurables por env (coma-separados). En local el default
  // basta; en EC2 se pasa CORS_ALLOWED_ORIGINS con la URL https del frontend.
  // En producción el CORS grueso vive en el API Gateway (ver docs/aws-setup.md
  // del frontend); esto evita el 403 "Invalid CORS request" de Spring cuando el
  // Gateway reenvía el Origin del navegador al backend.
  @Bean
  public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOrigins(List.of(allowedOrigins.split(",")));
    config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
    config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);
    return source;
  }

  @Bean
  public JwtDecoder jwtDecoder() {
    String tid = obligatorio(tenantId, "AZURE_TENANT_ID");
    String audienciaAzure = obligatorio(azureAudience, "AZURE_AUDIENCE");
    String jwksCognito = obligatorio(cognitoJwksUri, "COGNITO_JWKS_URI");
    String clienteCognito = obligatorio(cognitoClientId, "COGNITO_CLIENT_ID");
    String emisorCognito = (cognitoIssuer == null || cognitoIssuer.isBlank())
        ? issuerDesdeJwks(jwksCognito) : cognitoIssuer.trim();
    String emisorAzure = "https://sts.windows.net/" + tid + "/";

    String base = (issuerUri != null && !issuerUri.isBlank())
        ? issuerUri.trim() : "https://login.microsoftonline.com/";
    if (!base.endsWith("/")) {
      base += "/";
    }
    // Los JWKS se descargan solo al decodificar el primer token de cada emisor.
    Map<String, JwtDecoder> decoders = Map.of(
        emisorAzure, decoder(emisorAzure, base + tid + "/discovery/keys", audienciaAzure, false),
        emisorCognito, decoder(emisorCognito, jwksCognito, clienteCognito, true));

    return token -> {
      try {
        // El iss sin verificar solo elige entre dos decoders preconfigurados.
        String iss = JWTParser.parse(token).getJWTClaimsSet().getIssuer();
        JwtDecoder elegido = iss == null ? null : decoders.get(iss);
        if (elegido == null) throw new BadJwtException("Emisor no permitido");
        return elegido.decode(token);
      } catch (ParseException e) {
        throw new BadJwtException("JWT malformado", e);
      }
    };
  }

  private static String obligatorio(String valor, String nombre) {
    if (valor == null || valor.isBlank()) {
      throw new IllegalStateException("Configuración JWT obligatoria vacía: " + nombre);
    }
    return valor.trim();
  }

  private static String issuerDesdeJwks(String jwks) {
    String sufijo = "/.well-known/jwks.json";
    if (!jwks.endsWith(sufijo)) {
      throw new IllegalStateException("COGNITO_ISSUER requerido: COGNITO_JWKS_URI no termina en " + sufijo);
    }
    return jwks.substring(0, jwks.length() - sufijo.length());
  }

  private static JwtDecoder decoder(String issuer, String jwks, String audience, boolean cognito) {
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwks).build();
    decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
        JwtValidators.createDefaultWithIssuer(issuer),
        new JwtClaimValidator<List<String>>("aud", aud -> aud != null && aud.contains(audience)),
        new JwtClaimValidator<Object>("exp", exp -> exp != null),
        new JwtClaimValidator<String>("token_use", use -> !cognito || "id".equals(use))));
    return decoder;
  }
}
