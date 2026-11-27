package com.pedidos360.carrito;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Guard de la URL de PostgreSQL de test sin base de datos ni variables de entorno: la validación ocurre
 * antes de registrar el datasource con create-drop.
 */
class CheckoutPostgresUrlGuardTest {

  private static final String APROBADA = "jdbc:postgresql://host.docker.internal:55432/p360_test_carrito";

  @Test
  void aceptaUrlAprobadaDelRunner() {
    assertDoesNotThrow(() -> CheckoutPostgresTest.validarUrlBaseDePrueba(APROBADA));
  }

  @Test
  void aceptaLoopbacksConMismoPuertoYBase() {
    assertDoesNotThrow(() ->
        CheckoutPostgresTest.validarUrlBaseDePrueba("jdbc:postgresql://localhost:55432/p360_test_carrito"));
    assertDoesNotThrow(() ->
        CheckoutPostgresTest.validarUrlBaseDePrueba("jdbc:postgresql://127.0.0.1:55432/p360_test_carrito"));
    assertDoesNotThrow(() ->
        CheckoutPostgresTest.validarUrlBaseDePrueba("jdbc:postgresql://[::1]:55432/p360_test_carrito"));
  }

  @Test
  void rechazaHostDeRds() {
    assertThrows(IllegalStateException.class, () -> CheckoutPostgresTest.validarUrlBaseDePrueba(
        "jdbc:postgresql://p360-postgres.abc123.us-east-1.rds.amazonaws.com:55432/p360_test_carrito"));
  }

  @Test
  void rechazaPuertoComercial5432() {
    assertThrows(IllegalStateException.class, () -> CheckoutPostgresTest.validarUrlBaseDePrueba(
        "jdbc:postgresql://host.docker.internal:5432/p360_test_carrito"));
  }

  @Test
  void rechazaOtraBase() {
    assertThrows(IllegalStateException.class, () -> CheckoutPostgresTest.validarUrlBaseDePrueba(
        "jdbc:postgresql://host.docker.internal:55432/p360_carrito"));
    assertThrows(IllegalStateException.class, () -> CheckoutPostgresTest.validarUrlBaseDePrueba(
        "jdbc:postgresql://host.docker.internal:55432/postgres"));
  }

  @Test
  void rechazaCredencialesEnQueryYOEnAutoridad() {
    assertThrows(IllegalStateException.class, () -> CheckoutPostgresTest.validarUrlBaseDePrueba(
        APROBADA + "?user=postgres&password=secreto"));
    assertThrows(IllegalStateException.class, () -> CheckoutPostgresTest.validarUrlBaseDePrueba(
        "jdbc:postgresql://usuario:clave@host.docker.internal:55432/p360_test_carrito"));
  }

  @Test
  void rechazaUrlNulaOMalFormada() {
    assertThrows(IllegalStateException.class, () -> CheckoutPostgresTest.validarUrlBaseDePrueba(null));
    assertThrows(IllegalStateException.class, () -> CheckoutPostgresTest.validarUrlBaseDePrueba(
        "jdbc:mysql://host.docker.internal:55432/p360_test_carrito"));
  }
}
