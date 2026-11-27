package com.pedidos360.carrito;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pedidos360.carrito.repository.CheckoutOperacionRepository;
import com.pedidos360.carrito.checkout.CheckoutService;
import com.pedidos360.carrito.controller.CarritoController;
import com.pedidos360.carrito.controller.CatalogoPrecios;
import com.pedidos360.carrito.messaging.InteraccionEvent;
import com.pedidos360.carrito.messaging.OrdenCreadaEvent;
import com.pedidos360.carrito.repository.OrdenOutboxRepository;
import com.pedidos360.carrito.messaging.OrdenOutboxRelay;
import com.pedidos360.carrito.model.Carrito;
import com.pedidos360.carrito.model.CarritoItem;
import com.pedidos360.carrito.repository.CarritoItemRepository;
import com.pedidos360.carrito.repository.CarritoRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Contratos con PostgreSQL. Requiere una BD efímera ajena al RDS de negocio, declarada en
 * P360_TEST_POSTGRES_URL; sin esa variable las pruebas se omiten (decisión B06 del coordinador).
 */
@EnabledIfEnvironmentVariable(named = "P360_TEST_POSTGRES_URL", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"p360.checkout.outbox.delay-ms=3600000"})
@RecordApplicationEvents
class CheckoutPostgresTest {

  private static final String USUARIO = "oid-123";
  private static final String EMAIL = "comprador@mail.com";

  @Autowired CheckoutService service;
  @Autowired OrdenOutboxRelay relay;
  @Autowired CarritoController controller;
  @Autowired CarritoRepository carritoRepository;
  @Autowired CarritoItemRepository itemRepository;
  @Autowired CheckoutOperacionRepository operacionRepository;
  @Autowired PlatformTransactionManager transactionManager;
  @SpyBean OrdenOutboxRepository outboxRepository;
  @Autowired ObjectMapper mapper;
  @Autowired ApplicationEvents events;

  @MockBean CatalogoPrecios catalogoPrecios;
  @MockBean RabbitTemplate rabbitTemplate;

  @DynamicPropertySource
  static void baseDePrueba(DynamicPropertyRegistry registry) {
    String url = System.getenv("P360_TEST_POSTGRES_URL");
    if (url == null) return;
    validarUrlBaseDePrueba(url);
    registry.add("spring.datasource.url", () -> url);
    String usuario = System.getenv("P360_TEST_POSTGRES_USER");
    if (usuario != null) registry.add("spring.datasource.username", () -> usuario);
    String clave = System.getenv("P360_TEST_POSTGRES_PASSWORD");
    if (clave != null) registry.add("spring.datasource.password", () -> clave);
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
  }

  @BeforeEach
  void limpiar() {
    itemRepository.deleteAll();
    outboxRepository.deleteAll();
    operacionRepository.deleteAll();
    carritoRepository.deleteAll();
  }

  @AfterEach
  void limpiarSeguridad() {
    SecurityContextHolder.clearContext();
  }

  private Carrito nuevoCarrito() {
    return carritoRepository.save(new Carrito(USUARIO));
  }

  private CarritoItem nuevoItem(Carrito carrito, long precio, int stock) {
    UUID productoId = UUID.randomUUID();
    when(catalogoPrecios.consultar(productoId))
        .thenReturn(new CatalogoPrecios.Producto(productoId, precio, stock));
    CarritoItem item = new CarritoItem();
    item.setCarritoId(carrito.getId());
    item.setProductoId(productoId);
    item.setCantidad(2);
    item.setPrecioUnitarioClp(precio);
    return itemRepository.save(item);
  }

  private void confirmar(boolean ack) {
    doAnswer(inv -> {
      CorrelationData correlacion = inv.getArgument(3);
      correlacion.getFuture().complete(new CorrelationData.Confirm(ack, null));
      return null;
    }).when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class), any(CorrelationData.class));
  }

  private Jwt jwt() {
    return Jwt.withTokenValue("token-de-prueba").header("alg", "none")
        .claim("oid", USUARIO).claim("email", EMAIL).build();
  }

  @Test
  void mismaClaveConcurrenteProduceUnSoloOrdenId() throws Exception {
    Carrito carrito = nuevoCarrito();
    nuevoItem(carrito, 19990L, 10);
    String clave = UUID.randomUUID().toString();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch salida = new CountDownLatch(1);
    Callable<Map<String, Object>> tarea = () -> {
      salida.await();
      return service.checkout(USUARIO, EMAIL, null, clave).respuesta();
    };
    try {
      Future<Map<String, Object>> uno = pool.submit(tarea);
      Future<Map<String, Object>> dos = pool.submit(tarea);
      salida.countDown();
      Map<String, Object> respuestaUno = uno.get(30, TimeUnit.SECONDS);
      Map<String, Object> respuestaDos = dos.get(30, TimeUnit.SECONDS);
      assertEquals(respuestaUno.get("ordenId").toString(), respuestaDos.get("ordenId").toString());
    } finally {
      pool.shutdownNow();
    }
    assertEquals(1, operacionRepository.count());
    assertEquals(1, outboxRepository.count());
    assertTrue(itemRepository.findByCarritoId(carrito.getId()).isEmpty());
  }

  @Test
  void reintentoTrasTimeoutDevuelveMismaRespuestaGuardada() throws Exception {
    Carrito carrito = nuevoCarrito();
    nuevoItem(carrito, 19990L, 10);
    String clave = UUID.randomUUID().toString();
    String primera = mapper.writeValueAsString(service.checkout(USUARIO, EMAIL, null, clave).respuesta());

    nuevoItem(carrito, 9990L, 10);
    String segunda = mapper.writeValueAsString(service.checkout(USUARIO, EMAIL, null, clave).respuesta());

    assertEquals(primera, segunda);
    assertEquals(1, operacionRepository.count());
    assertEquals(1, outboxRepository.count());
    assertEquals(1, itemRepository.findByCarritoId(carrito.getId()).size());
  }

  @Test
  void mismaClaveOtraCompraDevuelve409() {
    Carrito carrito = nuevoCarrito();
    nuevoItem(carrito, 19990L, 10);
    String clave = UUID.randomUUID().toString();
    service.checkout(USUARIO, EMAIL, "TEST10", clave);

    nuevoItem(carrito, 9990L, 10);
    ResponseStatusException ex = assertThrows(ResponseStatusException.class,
        () -> service.checkout(USUARIO, "otro@mail.com", "TEST20", clave));

    assertEquals(409, ex.getStatusCode().value());
    // El replay de la misma clave no toca el carrito: queda el ítem agregado después del checkout.
    assertEquals(1, itemRepository.findByCarritoId(carrito.getId()).size());
    assertEquals(1, outboxRepository.count());
  }

  @Test
  void claveDistintaSobreMismoCarritoSeSerializa() throws Exception {
    Carrito carrito = nuevoCarrito();
    nuevoItem(carrito, 19990L, 10);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch salida = new CountDownLatch(1);
    Callable<String> tarea = () -> {
      salida.await();
      try {
        return "OK:" + service.checkout(USUARIO, EMAIL, null, UUID.randomUUID().toString())
            .respuesta().get("ordenId");
      } catch (ResponseStatusException ex) {
        return "ERROR:" + ex.getStatusCode().value() + ":" + ex.getReason();
      }
    };
    List<String> resultados;
    try {
      Future<String> uno = pool.submit(tarea);
      Future<String> dos = pool.submit(tarea);
      salida.countDown();
      resultados = List.of(uno.get(30, TimeUnit.SECONDS), dos.get(30, TimeUnit.SECONDS));
    } finally {
      pool.shutdownNow();
    }
    assertEquals(1, resultados.stream().filter(r -> r.startsWith("OK:")).count());
    assertTrue(resultados.stream().anyMatch(r -> r.equals("ERROR:400:Carrito vacío")));
    assertEquals(1, operacionRepository.count());
    assertEquals(1, outboxRepository.count());
  }

  @Test
  void falloDeCommitDejaCarritoIntacto() {
    Carrito carrito = nuevoCarrito();
    CarritoItem item = nuevoItem(carrito, 19990L, 10);

    new TransactionTemplate(transactionManager).execute(status -> {
      service.checkout(USUARIO, EMAIL, null, null);
      status.setRollbackOnly();
      return null;
    });

    assertEquals(0, operacionRepository.count());
    assertEquals(0, outboxRepository.count());
    assertEquals(1, itemRepository.findByCarritoId(carrito.getId()).size());
    assertEquals(item.getId(), itemRepository.findByCarritoId(carrito.getId()).get(0).getId());
  }

  @Test
  void itemAgregadoDespuesDelCheckoutNoSeBorra() {
    Carrito carrito = nuevoCarrito();
    nuevoItem(carrito, 19990L, 10);
    String clave = UUID.randomUUID().toString();
    service.checkout(USUARIO, EMAIL, null, clave);

    CarritoItem nuevo = nuevoItem(carrito, 9990L, 10);
    service.checkout(USUARIO, EMAIL, null, clave);

    assertEquals(1, itemRepository.findByCarritoId(carrito.getId()).size());
    assertEquals(nuevo.getId(), itemRepository.findByCarritoId(carrito.getId()).get(0).getId());
  }

  @Test
  void brokerCaidoDejaCheckoutAceptadoYOutboxPendiente() throws Exception {
    Carrito carrito = nuevoCarrito();
    nuevoItem(carrito, 19990L, 10);
    doThrow(new AmqpException("broker caido")).when(rabbitTemplate)
        .convertAndSend(anyString(), anyString(), any(Object.class), any(CorrelationData.class));

    Map<String, Object> respuesta = service.checkout(USUARIO, EMAIL, null, null).respuesta();

    assertEquals("checkout_ok", respuesta.get("estado"));
    relay.reenviarPendientes();
    var outbox = outboxRepository.findById((UUID) respuesta.get("ordenId")).orElseThrow();
    assertEquals("PENDIENTE", outbox.getEstado());
    assertEquals(1, outbox.getIntentos());
  }

  @Test
  void reenvioTrasReinicioUsaMismoOrdenIdYSoloMarcaTrasAck() {
    Carrito carrito = nuevoCarrito();
    nuevoItem(carrito, 19990L, 10);
    UUID ordenId = (UUID) service.checkout(USUARIO, EMAIL, null, null).respuesta().get("ordenId");

    confirmar(false);
    relay.reenviarPendientes();
    assertEquals("PENDIENTE", outboxRepository.findById(ordenId).orElseThrow().getEstado());

    confirmar(true);
    relay.reenviarPendientes();
    assertEquals("ENVIADO", outboxRepository.findById(ordenId).orElseThrow().getEstado());

    relay.reenviarPendientes();
    ArgumentCaptor<CorrelationData> correlaciones = ArgumentCaptor.forClass(CorrelationData.class);
    verify(rabbitTemplate, times(2)).convertAndSend(anyString(), anyString(), any(Object.class), correlaciones.capture());
    correlaciones.getAllValues().forEach(c -> assertEquals(ordenId.toString(), c.getId()));
  }

  @Test
  void caidaDespuesDePublicarAntesDeMarcarReenviaMismoOrdenId() {
    Carrito carrito = nuevoCarrito();
    nuevoItem(carrito, 19990L, 10);
    UUID ordenId = (UUID) service.checkout(USUARIO, EMAIL, null, null).respuesta().get("ordenId");
    confirmar(true);
    doThrow(new RuntimeException("fallo al marcar"))
        .doNothing()
        .when(outboxRepository).marcarEnviado(any(), any());

    assertThrows(RuntimeException.class, () -> relay.reenviarPendientes());
    relay.reenviarPendientes();

    ArgumentCaptor<CorrelationData> correlaciones = ArgumentCaptor.forClass(CorrelationData.class);
    verify(rabbitTemplate, times(2)).convertAndSend(anyString(), anyString(), any(Object.class), correlaciones.capture());
    correlaciones.getAllValues().forEach(c -> assertEquals(ordenId.toString(), c.getId()));
  }

  @Test
  void eventosDeAplicacionSoloEnOperacionNueva() {
    Carrito carrito = nuevoCarrito();
    nuevoItem(carrito, 19990L, 10);
    String clave = UUID.randomUUID().toString();
    SecurityContextHolder.getContext().setAuthentication(
        new JwtAuthenticationToken(jwt(), java.util.List.of(new SimpleGrantedAuthority("ROLE_Cliente"))));

    controller.checkout(jwt(), "TEST10", EMAIL, clave);
    assertEquals(2, events.stream(InteraccionEvent.class).count());

    controller.checkout(jwt(), "TEST10", EMAIL, clave);
    assertEquals(2, events.stream(InteraccionEvent.class).count());
  }

  @Test
  void baseDePruebaNoEsRdsDeNegocio() {
    String url = System.getenv("P360_TEST_POSTGRES_URL");
    assertDoesNotThrow(() -> validarUrlBaseDePrueba(url),
        "P360_TEST_POSTGRES_URL no es una base de prueba aprobada");
  }

  // SIMPLIFICADO: lista exacta aceptada por el runner efímero; ampliar sólo si el runner cambia de URL.
  static void validarUrlBaseDePrueba(String url) {
    if ("jdbc:postgresql://host.docker.internal:55432/p360_test_carrito".equals(url)
        || "jdbc:postgresql://localhost:55432/p360_test_carrito".equals(url)
        || "jdbc:postgresql://127.0.0.1:55432/p360_test_carrito".equals(url)
        || "jdbc:postgresql://[::1]:55432/p360_test_carrito".equals(url)) {
      return;
    }
    throw new IllegalStateException("P360_TEST_POSTGRES_URL rechazada antes de crear el datasource: " + url);
  }
}
