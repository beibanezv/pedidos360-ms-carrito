package com.pedidos360.carrito;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pedidos360.carrito.checkout.CheckoutOperacion;
import com.pedidos360.carrito.repository.CheckoutOperacionRepository;
import com.pedidos360.carrito.checkout.CheckoutService;
import com.pedidos360.carrito.controller.CatalogoPrecios;
import com.pedidos360.carrito.messaging.OrdenCreadaEvent;
import com.pedidos360.carrito.messaging.OrdenOutbox;
import com.pedidos360.carrito.repository.OrdenOutboxRepository;
import com.pedidos360.carrito.model.Carrito;
import com.pedidos360.carrito.model.CarritoItem;
import com.pedidos360.carrito.repository.CarritoItemRepository;
import com.pedidos360.carrito.repository.CarritoRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Lógica de checkout sin base de datos: repositorios y catálogo se sustituyen por mocks. */
@ExtendWith(MockitoExtension.class)
class CheckoutServiceTest {

  private static final String USUARIO = "oid-123";
  private static final String EMAIL = "comprador@mail.com";

  @Mock CarritoRepository carritoRepository;
  @Mock CarritoItemRepository itemRepository;
  @Mock CheckoutOperacionRepository operacionRepository;
  @Mock OrdenOutboxRepository outboxRepository;
  @Mock CatalogoPrecios catalogoPrecios;

  private final ObjectMapper mapper = new ObjectMapper();
  private CheckoutService service;

  @BeforeEach
  void setUp() {
    service = new CheckoutService(carritoRepository, itemRepository, operacionRepository,
        outboxRepository, catalogoPrecios, mapper);
  }

  private Carrito carrito() {
    Carrito c = new Carrito(USUARIO);
    c.setId(UUID.randomUUID());
    return c;
  }

  private CarritoItem item(UUID carritoId, UUID productoId, int cantidad, long precio) {
    CarritoItem i = new CarritoItem();
    i.setId(UUID.randomUUID());
    i.setCarritoId(carritoId);
    i.setProductoId(productoId);
    i.setCantidad(cantidad);
    i.setPrecioUnitarioClp(precio);
    return i;
  }

  private void conCarrito(Carrito c, CarritoItem... items) {
    when(carritoRepository.findByUsuarioIdForUpdate(USUARIO)).thenReturn(Optional.of(c));
    when(itemRepository.findByCarritoId(c.getId())).thenReturn(List.of(items));
  }

  private void catalogo(UUID id, long precio, int stock) {
    when(catalogoPrecios.consultar(id)).thenReturn(new CatalogoPrecios.Producto(id, precio, stock));
  }

  private void guardaRepositorios() {
    when(operacionRepository.save(any(CheckoutOperacion.class))).thenAnswer(i -> i.getArgument(0));
    when(outboxRepository.save(any(OrdenOutbox.class))).thenAnswer(i -> i.getArgument(0));
  }

  private static ResponseStatusException status(Throwable ex) {
    assertTrue(ex instanceof ResponseStatusException, "Se esperaba ResponseStatusException: " + ex);
    return (ResponseStatusException) ex;
  }

  @Test
  void claveNoUuidRetorna400() {
    ResponseStatusException ex = assertThrows(ResponseStatusException.class,
        () -> service.checkout(USUARIO, EMAIL, null, "clave-estable-123"));

    assertEquals(HttpStatus.BAD_REQUEST, status(ex).getStatusCode());
    verifyNoInteractions(carritoRepository, itemRepository, operacionRepository, outboxRepository, catalogoPrecios);
  }

  @Test
  void sinClaveGeneraUuidYPersisteOperacionYOutbox() throws Exception {
    Carrito c = carrito();
    UUID productoId = UUID.randomUUID();
    CarritoItem ci = item(c.getId(), productoId, 2, 19990L);
    conCarrito(c, ci);
    catalogo(productoId, 19990L, 5);
    guardaRepositorios();

    CheckoutService.Result resultado = service.checkout(USUARIO, EMAIL, "TEST10", null);

    assertTrue(resultado.nueva());
    ArgumentCaptor<CheckoutOperacion> operacion = ArgumentCaptor.forClass(CheckoutOperacion.class);
    verify(operacionRepository).save(operacion.capture());
    assertThat(operacion.getValue().getIdempotencyKey()).matches(
        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    assertEquals(USUARIO, operacion.getValue().getUsuarioId());
    assertThat(operacion.getValue().getSnapshotHash()).hasSize(64);

    ArgumentCaptor<OrdenOutbox> outbox = ArgumentCaptor.forClass(OrdenOutbox.class);
    verify(outboxRepository).save(outbox.capture());
    OrdenCreadaEvent evento = mapper.readValue(outbox.getValue().getPayloadJson(), OrdenCreadaEvent.class);
    assertEquals(evento.ordenId(), outbox.getValue().getId());
    assertEquals(outbox.getValue().getId(), resultado.respuesta().get("ordenId"));
    assertEquals("PENDIENTE", outbox.getValue().getEstado());
    assertEquals(19990L * 2, resultado.respuesta().get("totalClp"));
    assertEquals(8, resultado.respuesta().size());
    verify(itemRepository).deleteAllById(List.of(ci.getId()));
  }

  @Test
  void mismaClaveDevuelveRespuestaGuardadaSinEfectos() throws Exception {
    Carrito c = carrito();
    UUID productoId = UUID.randomUUID();
    conCarrito(c, item(c.getId(), productoId, 2, 19990L));
    catalogo(productoId, 19990L, 5);
    guardaRepositorios();
    UUID clave = UUID.randomUUID();

    CheckoutService.Result primera = service.checkout(USUARIO, EMAIL, null, clave.toString());
    ArgumentCaptor<CheckoutOperacion> operacion = ArgumentCaptor.forClass(CheckoutOperacion.class);
    verify(operacionRepository).save(operacion.capture());
    when(operacionRepository.findByUsuarioIdAndIdempotencyKey(USUARIO, clave.toString()))
        .thenReturn(Optional.of(operacion.getValue()));

    CheckoutService.Result segunda = service.checkout(USUARIO, EMAIL, null, clave.toString());

    assertFalse(segunda.nueva());
    assertEquals(mapper.writeValueAsString(primera.respuesta()), mapper.writeValueAsString(segunda.respuesta()));
    verify(operacionRepository, times(1)).save(any());
    verify(outboxRepository, times(1)).save(any());
    verify(itemRepository, times(1)).deleteAllById(any());
    verify(catalogoPrecios, times(1)).consultar(productoId);
  }

  @Test
  void mismaClaveConOtroEmailDevuelve409SinEfectos() {
    Carrito c = carrito();
    UUID productoId = UUID.randomUUID();
    conCarrito(c, item(c.getId(), productoId, 2, 19990L));
    catalogo(productoId, 19990L, 5);
    guardaRepositorios();
    UUID clave = UUID.randomUUID();

    service.checkout(USUARIO, EMAIL, null, clave.toString());
    ArgumentCaptor<CheckoutOperacion> operacion = ArgumentCaptor.forClass(CheckoutOperacion.class);
    verify(operacionRepository).save(operacion.capture());
    when(operacionRepository.findByUsuarioIdAndIdempotencyKey(USUARIO, clave.toString()))
        .thenReturn(Optional.of(operacion.getValue()));

    ResponseStatusException ex = assertThrows(ResponseStatusException.class,
        () -> service.checkout(USUARIO, "otro@mail.com", null, clave.toString()));

    assertEquals(HttpStatus.CONFLICT, status(ex).getStatusCode());
    verify(operacionRepository, times(1)).save(any());
    verify(outboxRepository, times(1)).save(any());
    verify(itemRepository, times(1)).deleteAllById(any());
  }

  @Test
  void carritoVacioRetorna400() {
    Carrito c = carrito();
    conCarrito(c);

    ResponseStatusException ex = assertThrows(ResponseStatusException.class,
        () -> service.checkout(USUARIO, EMAIL, null, null));

    assertEquals(HttpStatus.BAD_REQUEST, status(ex).getStatusCode());
    assertEquals("Carrito vacío", status(ex).getReason());
    verifyNoInteractions(operacionRepository, outboxRepository);
  }

  @Test
  void productoSinStockDevuelve409SinPersistir() {
    Carrito c = carrito();
    UUID productoId = UUID.randomUUID();
    conCarrito(c, item(c.getId(), productoId, 2, 19990L));
    catalogo(productoId, 19990L, 0);

    ResponseStatusException ex = assertThrows(ResponseStatusException.class,
        () -> service.checkout(USUARIO, EMAIL, null, null));

    assertEquals(HttpStatus.CONFLICT, status(ex).getStatusCode());
    verify(operacionRepository, never()).save(any());
    verify(outboxRepository, never()).save(any());
    verify(itemRepository, never()).deleteAllById(any());
  }

  @Test
  void catalogoCaidoDevuelve503SinPersistir() {
    Carrito c = carrito();
    UUID productoId = UUID.randomUUID();
    conCarrito(c, item(c.getId(), productoId, 1, 19990L));
    when(catalogoPrecios.consultar(productoId)).thenThrow(
        new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "No pudimos consultar los precios"));

    ResponseStatusException ex = assertThrows(ResponseStatusException.class,
        () -> service.checkout(USUARIO, EMAIL, null, null));

    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, status(ex).getStatusCode());
    verify(operacionRepository, never()).save(any());
    verify(outboxRepository, never()).save(any());
    verify(itemRepository, never()).deleteAllById(any());
  }

  @Test
  void lineasDuplicadasConsultanCatalogoUnaSolaVez() {
    Carrito c = carrito();
    UUID productoId = UUID.randomUUID();
    conCarrito(c,
        item(c.getId(), productoId, 2, 1L),
        item(c.getId(), productoId, 2, 1L));
    catalogo(productoId, 19990L, 3);

    ResponseStatusException ex = assertThrows(ResponseStatusException.class,
        () -> service.checkout(USUARIO, EMAIL, null, null));

    assertEquals(HttpStatus.CONFLICT, status(ex).getStatusCode());
    verify(catalogoPrecios, times(1)).consultar(productoId);
    verify(outboxRepository, never()).save(any());
  }

  @Test
  void desbordeMonetarioDevuelve400SinPersistir() {
    Carrito c = carrito();
    UUID productoId = UUID.randomUUID();
    conCarrito(c, item(c.getId(), productoId, 2, 1L));
    catalogo(productoId, Long.MAX_VALUE, 3);

    ResponseStatusException ex = assertThrows(ResponseStatusException.class,
        () -> service.checkout(USUARIO, EMAIL, null, null));

    assertEquals(HttpStatus.BAD_REQUEST, status(ex).getStatusCode());
    verify(operacionRepository, never()).save(any());
    verify(outboxRepository, never()).save(any());
    verify(itemRepository, never()).deleteAllById(any());
  }

  @Test
  void snapshotExcluyeOrdenIdYTelemetria() throws Exception {
    Carrito c = carrito();
    UUID productoId = UUID.randomUUID();
    conCarrito(c, item(c.getId(), productoId, 1, 19990L));
    catalogo(productoId, 19990L, 5);
    guardaRepositorios();

    service.checkout(USUARIO, EMAIL, null, null);

    ArgumentCaptor<CheckoutOperacion> operacion = ArgumentCaptor.forClass(CheckoutOperacion.class);
    verify(operacionRepository).save(operacion.capture());
    Map<?, ?> snapshot = mapper.readValue(operacion.getValue().getSnapshotJson(), Map.class);
    assertEquals(5, snapshot.size());
    assertTrue(snapshot.keySet().containsAll(List.of(
        "carritoId", "items", "totalClp", "emailComprador", "codigoCupon")));
    assertThat(snapshot.toString()).doesNotContain("ordenId", "eventoId", "token");
  }
}
