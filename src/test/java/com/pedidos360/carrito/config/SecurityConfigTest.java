package com.pedidos360.carrito.config;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ejercita el JwtDecoder que construye produccion: claves RSA efimeras publicadas
 * en un HttpServer de 127.0.0.1, sin internet, DB ni broker. Los campos de config
 * se llenan igual que lo hace Spring con @Value.
 */
class SecurityConfigTest {

  private static final String TENANT = "tenant-de-prueba";
  private static final String AZURE_ISSUER = "https://sts.windows.net/" + TENANT + "/";
  private static final String AZURE_AUDIENCE = "api://audiencia-de-prueba";
  private static final String COGNITO_CLIENT = "cliente-de-prueba";
  private static final String OTRO_CLIENT = "otro-cliente";
  private static final String OTRO_POOL = "https://cognito-idp.us-east-1.amazonaws.com/us-east-1_otro";

  private SecurityConfig config;
  private HttpServer server;
  private AtomicInteger peticiones;
  private Clave azure;
  private Clave cognito;
  private Clave ajena;
  private String cognitoIssuer;

  @BeforeEach
  void setUp() throws Exception {
    azure = new Clave("clave-azure");
    cognito = new Clave("clave-cognito");
    ajena = new Clave("clave-ajena");
    peticiones = new AtomicInteger();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    String base = "http://127.0.0.1:" + server.getAddress().getPort();
    server.createContext("/" + TENANT + "/discovery/keys",
        exchange -> responder(exchange, azure.jwks(), peticiones));
    String jwksCognito = base + "/cognito/.well-known/jwks.json";
    server.createContext("/cognito/.well-known/jwks.json",
        exchange -> responder(exchange, cognito.jwks(), peticiones));
    server.start();
    cognitoIssuer = base + "/cognito";

    config = new SecurityConfig();
    configurar("issuerUri", base + "/");
    configurar("tenantId", TENANT);
    configurar("azureAudience", AZURE_AUDIENCE);
    configurar("cognitoJwksUri", jwksCognito);
    configurar("cognitoIssuer", "");
    configurar("cognitoClientId", COGNITO_CLIENT);
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  // En rojo los campos nuevos todavia no existen; en verde se aplican todos.
  private void configurar(String nombre, Object valor) {
    boolean existe = java.util.Arrays.stream(config.getClass().getDeclaredFields())
        .anyMatch(f -> f.getName().equals(nombre));
    if (existe) ReflectionTestUtils.setField(config, nombre, valor);
  }

  private static void responder(HttpExchange exchange, String jwks, AtomicInteger contador) {
    contador.incrementAndGet();
    byte[] cuerpo = jwks.getBytes(StandardCharsets.UTF_8);
    try {
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, cuerpo.length);
      try (OutputStream salida = exchange.getResponseBody()) {
        salida.write(cuerpo);
      }
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private String firmar(Clave clave, String issuer, String audience, Instant expira, Map<String, Object> claims) {
    JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
        .jwtID(UUID.randomUUID().toString())
        .issueTime(Date.from(Instant.now()));
    if (issuer != null) builder.issuer(issuer);
    if (audience != null) builder.audience(audience);
    if (expira != null) builder.expirationTime(Date.from(expira));
    if (claims != null) claims.forEach(builder::claim);
    SignedJWT jwt = new SignedJWT(
        new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(clave.publica.getKeyID()).build(),
        builder.build());
    try {
      jwt.sign(new RSASSASigner(clave.privada));
    } catch (JOSEException e) {
      throw new IllegalStateException("No se pudo firmar el token de prueba", e);
    }
    return jwt.serialize();
  }

  private String azure(Map<String, Object> claims) {
    return firmar(azure, AZURE_ISSUER, AZURE_AUDIENCE, Instant.now().plusSeconds(600), claims);
  }

  private String cognito(String tokenUse, String audience, Map<String, Object> claims) {
    Map<String, Object> todos = new LinkedHashMap<>();
    if (claims != null) todos.putAll(claims);
    if (tokenUse != null) todos.put("token_use", tokenUse);
    return firmar(cognito, cognitoIssuer, audience, Instant.now().plusSeconds(600), todos);
  }

  private List<String> autoridades(Jwt jwt) {
    return config.jwtAuthenticationConverter().convert(jwt)
        .getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toList());
  }

  private Jwt jwtCognito() {
    return Jwt.withTokenValue("id-token-cognito")
        .header("alg", "RS256")
        .subject("143884e8-a0e1-7060-ac41-708a2bb66381")
        .claim("iss", "https://cognito-idp.us-east-1.amazonaws.com/us-east-1_A8lrPsDta")
        .claim("token_use", "id")
        .claim("email", "cliente@pedidos360.cl")
        .build();
  }

  private Jwt jwtAzure() {
    return Jwt.withTokenValue("access-token-azure")
        .header("alg", "RS256")
        .subject("sub-123")
        .claim("oid", "oid-123")
        .claim("scp", "Productos.Read Carrito.ReadWrite")
        .claim("roles", List.of("Cliente", "Admin"))
        .build();
  }

  private static final class Clave {
    private final RSAPrivateKey privada;
    private final RSAKey publica;

    private Clave(String kid) {
      try {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair par = generator.generateKeyPair();
        this.privada = (RSAPrivateKey) par.getPrivate();
        this.publica = new RSAKey.Builder((RSAPublicKey) par.getPublic()).keyID(kid).build();
      } catch (Exception e) {
        throw new IllegalStateException("No se pudo generar la llave de prueba", e);
      }
    }

    private String jwks() {
      return new JWKSet(publica.toPublicJWK()).toString();
    }
  }

  @Test
  void converter_tokenCognito_mapeaRolCliente() {
    List<String> authorities = autoridades(jwtCognito());
    assertTrue(authorities.contains("ROLE_Cliente"));
  }

  @Test
  void converter_tokenAzure_mantieneScopesYRolesSinExtraCognito() {
    List<String> authorities = autoridades(jwtAzure());
    assertTrue(authorities.contains("SCOPE_Carrito.ReadWrite"));
    assertTrue(authorities.contains("ROLE_Admin"));
    assertEquals(4, authorities.size()); // 2 scopes + 2 roles, nada de Cognito
  }

  @Test
  void aceptaAzureV1ConAudienceCorrecta() {
    Jwt jwt = config.jwtDecoder().decode(azure(Map.of(
        "scp", "Productos.Read Carrito.ReadWrite",
        "roles", List.of("Cliente", "Admin"),
        "oid", "oid-123")));

    assertEquals(AZURE_ISSUER, jwt.getIssuer().toString());
    assertEquals("oid-123", jwt.getClaimAsString("oid"));
    List<String> authorities = autoridades(jwt);
    assertTrue(authorities.contains("SCOPE_Carrito.ReadWrite"));
    assertTrue(authorities.contains("ROLE_Admin"));
  }

  @Test
  void aceptaCognitoIdTokenDelPool() {
    Jwt jwt = config.jwtDecoder().decode(cognito("id", COGNITO_CLIENT, Map.of("email", "cliente@pedidos360.cl")));

    assertEquals(cognitoIssuer, jwt.getIssuer().toString());
    assertTrue(autoridades(jwt).contains("ROLE_Cliente"));
  }

  @Test
  void rechazaFirmaAjena() {
    String token = firmar(ajena, AZURE_ISSUER, AZURE_AUDIENCE, Instant.now().plusSeconds(600), Map.of());

    assertThrows(JwtException.class, () -> config.jwtDecoder().decode(token));
  }

  @Test
  void rechazaIssuerAjenoAunqueLaFirmaSeaValida() {
    String token = firmar(azure, "https://atacante.example/", AZURE_AUDIENCE,
        Instant.now().plusSeconds(600), Map.of());
    JwtDecoder decoder = config.jwtDecoder();

    assertThrows(JwtException.class, () -> decoder.decode(token));
    assertEquals(0, peticiones.get()); // el issuer del token nunca elige una URL
  }

  @Test
  void rechazaAudienceAjenaConFirmaValida() {
    String token = firmar(azure, AZURE_ISSUER, "otra-api", Instant.now().plusSeconds(600), Map.of());

    assertThrows(JwtException.class, () -> config.jwtDecoder().decode(token));
  }

  @Test
  void rechazaTokenExpirado() {
    String token = firmar(azure, AZURE_ISSUER, AZURE_AUDIENCE, Instant.now().minusSeconds(180), Map.of());

    assertThrows(JwtException.class, () -> config.jwtDecoder().decode(token));
  }

  @Test
  void rechazaTokenSinExpiracion() {
    String token = firmar(azure, AZURE_ISSUER, AZURE_AUDIENCE, null, Map.of());

    assertThrows(JwtException.class, () -> config.jwtDecoder().decode(token));
  }

  @Test
  void rechazaCognitoAccessToken() {
    String token = cognito("access", COGNITO_CLIENT, Map.of());

    assertThrows(JwtException.class, () -> config.jwtDecoder().decode(token));
  }

  @Test
  void rechazaCognitoSinTokenUse() {
    String token = cognito(null, COGNITO_CLIENT, Map.of());

    assertThrows(JwtException.class, () -> config.jwtDecoder().decode(token));
  }

  @Test
  void rechazaOtroPoolYOtroClient() {
    String otroPool = firmar(azure, OTRO_POOL, AZURE_AUDIENCE, Instant.now().plusSeconds(600), Map.of());
    String otroClient = cognito("id", OTRO_CLIENT, Map.of());

    assertThrows(JwtException.class, () -> config.jwtDecoder().decode(otroPool));
    assertThrows(JwtException.class, () -> config.jwtDecoder().decode(otroClient));
  }

  @Test
  void rechazaIssuerNoTextoYTokenMalformado() {
    String issLista = firmar(azure, null, AZURE_AUDIENCE, Instant.now().plusSeconds(600),
        Map.of("iss", List.of(AZURE_ISSUER)));
    JwtDecoder decoder = config.jwtDecoder();

    assertThrows(JwtException.class, () -> decoder.decode(issLista));
    assertThrows(JwtException.class, () -> decoder.decode("no.es.un.jwt"));
  }

  @Test
  void crearDecoderNoConsultaJwks() {
    config.jwtDecoder();

    assertEquals(0, peticiones.get()); // sin token no se descarga ningun JWKS
  }

  @Test
  void configuracionVaciaNoOmiteAudience() {
    configurar("azureAudience", "");
    IllegalStateException sinAudience = assertThrows(IllegalStateException.class, () -> config.jwtDecoder());
    assertTrue(sinAudience.getMessage().contains("AZURE_AUDIENCE"));

    configurar("azureAudience", AZURE_AUDIENCE);
    configurar("cognitoClientId", "");
    IllegalStateException sinCliente = assertThrows(IllegalStateException.class, () -> config.jwtDecoder());
    assertTrue(sinCliente.getMessage().contains("COGNITO_CLIENT_ID"));
  }

  @Test
  void produccionExigeAudienceClientTenantYOtrosValores() throws Exception {
    java.util.Properties prod = new java.util.Properties();
    try (var entrada = getClass().getResourceAsStream("/application-prod.properties")) {
      prod.load(entrada);
    }
    assertEquals("${AZURE_TENANT_ID}", prod.getProperty("azure.tenant-id"));
    assertEquals("${AZURE_AUDIENCE}", prod.getProperty("azure.audience"));
    assertEquals("${COGNITO_CLIENT_ID}", prod.getProperty("cognito.client-id"));
    assertEquals("${COGNITO_JWKS_URI}", prod.getProperty("cognito.jwks-uri"));
    assertEquals("${COGNITO_ISSUER}", prod.getProperty("cognito.issuer"));
  }
}
