package com.pedidos360.carrito;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pedidos360.carrito.config.SecurityConfig;
import com.pedidos360.carrito.controller.CatalogoPrecios;
import com.pedidos360.carrito.messaging.InteraccionEvent;
import com.pedidos360.carrito.messaging.OrdenCreadaEvent;
import com.pedidos360.carrito.messaging.OrdenEventPublisher;
import com.pedidos360.carrito.model.Carrito;
import com.pedidos360.carrito.model.CarritoItem;
import com.pedidos360.carrito.repository.CarritoItemRepository;
import com.pedidos360.carrito.repository.CarritoRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(com.pedidos360.carrito.controller.CarritoController.class)
@Import(SecurityConfig.class)
@RecordApplicationEvents
class CarritoControllerTest {

  @Autowired MockMvc mockMvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired ApplicationEvents events;
  @MockBean CarritoRepository carritoRepository;
  @MockBean CarritoItemRepository itemRepository;
  @MockBean JwtDecoder jwtDecoder;
  @MockBean OrdenEventPublisher ordenEventPublisher;
  @MockBean CatalogoPrecios catalogoPrecios;

  private Carrito carrito(String usuarioId) {
    Carrito c = new Carrito(usuarioId);
    c.setId(UUID.randomUUID());
    return c;
  }

  private CarritoItem item(UUID carritoId) {
    CarritoItem ci = new CarritoItem();
    ci.setId(UUID.randomUUID());
    ci.setCarritoId(carritoId);
    ci.setProductoId(UUID.randomUUID());
    ci.setCantidad(2);
    ci.setPrecioUnitarioClp(19990L);
    return ci;
  }

  private void catalogo(UUID id, long precio, int stock) {
    when(catalogoPrecios.consultar(id)).thenReturn(new CatalogoPrecios.Producto(id, precio, stock));
  }

  @Test
  void getCarrito_sinJwt_retorna401() throws Exception {
    mockMvc.perform(get("/carrito"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void getCarrito_conJwt_retorna200() throws Exception {
    Carrito carrito = carrito("oid-123");
    when(carritoRepository.findByUsuarioId("oid-123")).thenReturn(Optional.of(carrito));
    when(itemRepository.findByCarritoId(carrito.getId())).thenReturn(List.of(item(carrito.getId())));

    mockMvc.perform(get("/carrito")
            .with(jwt().jwt(j -> j.claim("oid", "oid-123"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.usuarioId").value("oid-123"))
        .andExpect(jsonPath("$.items[0].precioUnitarioClp").value(19990));
  }

  @Test
  void postItem_sinJwt_retorna401() throws Exception {
    CarritoItem payload = new CarritoItem();
    payload.setProductoId(UUID.randomUUID());
    payload.setCantidad(1);
    payload.setPrecioUnitarioClp(9990L);

    mockMvc.perform(post("/carrito/items")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(payload)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void postItem_conJwtPeroSinScope_retorna403() throws Exception {
    CarritoItem payload = new CarritoItem();
    payload.setProductoId(UUID.randomUUID());
    payload.setCantidad(1);
    payload.setPrecioUnitarioClp(9990L);

    // autenticado pero sin SCOPE_Carrito.ReadWrite -> 403 por @PreAuthorize
    mockMvc.perform(post("/carrito/items")
            .with(jwt().jwt(j -> j.claim("oid", "oid-123")))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(payload)))
        .andExpect(status().isForbidden());
  }

  @Test
  void postItem_conScope_retorna201() throws Exception {
    Carrito carrito = carrito("oid-123");
    when(carritoRepository.findByUsuarioId("oid-123")).thenReturn(Optional.of(carrito));
    CarritoItem guardado = item(carrito.getId());
    when(itemRepository.save(any(CarritoItem.class))).thenReturn(guardado);

    CarritoItem payload = new CarritoItem();
    payload.setProductoId(UUID.randomUUID());
    payload.setCantidad(1);
    payload.setPrecioUnitarioClp(9990L);
    catalogo(payload.getProductoId(), 19990L, 5);

    mockMvc.perform(post("/carrito/items")
            .with(jwt().jwt(j -> j.claim("oid", "oid-123")).authorities(new SimpleGrantedAuthority("SCOPE_Carrito.ReadWrite")))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(payload)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.carritoId").value(carrito.getId().toString()));
    org.assertj.core.api.Assertions.assertThat(events.stream(InteraccionEvent.class))
        .singleElement().satisfies(evento -> {
          org.assertj.core.api.Assertions.assertThat(evento.usuarioId()).isEqualTo("oid-123");
          org.assertj.core.api.Assertions.assertThat(evento.tipo()).isEqualTo(InteraccionEvent.Tipo.AGREGAR_CARRITO);
          org.assertj.core.api.Assertions.assertThat(evento.productoId()).isEqualTo(payload.getProductoId());
        });
  }

  @Test
  void postItem_productoDuplicado_sumaCantidad() throws Exception {
    Carrito carrito = carrito("oid-123");
    when(carritoRepository.findByUsuarioId("oid-123")).thenReturn(Optional.of(carrito));
    CarritoItem existente = item(carrito.getId()); // cantidad 2
    when(itemRepository.findByCarritoIdAndProductoId(carrito.getId(), existente.getProductoId()))
        .thenReturn(Optional.of(existente));
    when(itemRepository.save(any(CarritoItem.class))).thenAnswer(inv -> inv.getArgument(0));
    catalogo(existente.getProductoId(), 19990L, 5);

    CarritoItem payload = new CarritoItem();
    payload.setProductoId(existente.getProductoId());
    payload.setCantidad(1);
    payload.setPrecioUnitarioClp(19990L);

    mockMvc.perform(post("/carrito/items")
            .with(jwt().jwt(j -> j.claim("oid", "oid-123")).authorities(new SimpleGrantedAuthority("SCOPE_Carrito.ReadWrite")))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(payload)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cantidad").value(3));
  }

  @Test
  void putItem_conScopeInsuficiente_retorna403() throws Exception {
    CarritoItem payload = new CarritoItem();
    payload.setProductoId(UUID.randomUUID());
    payload.setCantidad(5);
    payload.setPrecioUnitarioClp(1000L);

    mockMvc.perform(put("/carrito/items/{id}", UUID.randomUUID())
            .with(jwt().jwt(j -> j.claim("oid", "oid-123")))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(payload)))
        .andExpect(status().isForbidden());
  }

  @Test
  void checkout_conScope_retorna200() throws Exception {
    Carrito carrito = carrito("oid-123");
    when(carritoRepository.findByUsuarioId("oid-123")).thenReturn(Optional.of(carrito));
    // also used by getOrCreate
    when(carritoRepository.save(any(Carrito.class))).thenReturn(carrito);
    CarritoItem ci = item(carrito.getId());
    ci.setPrecioUnitarioClp(100L);
    catalogo(ci.getProductoId(), 19990L, 3);
    when(itemRepository.findByCarritoId(carrito.getId())).thenReturn(List.of(ci));

    mockMvc.perform(post("/carrito/checkout")
            .param("codigoCupon", "TEST10")
            .with(jwt().jwt(j -> j.claim("oid", "oid-123").claim("email", "comprador@mail.com")).authorities(new SimpleGrantedAuthority("SCOPE_Carrito.ReadWrite"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.estado").value("checkout_ok"))
        .andExpect(jsonPath("$.totalClp").value(39980))
        .andExpect(jsonPath("$.emailComprador").value("comprador@mail.com"));

    ArgumentCaptor<OrdenCreadaEvent> captor = ArgumentCaptor.forClass(OrdenCreadaEvent.class);
    verify(ordenEventPublisher).publicarOrdenCreada(captor.capture());
    org.junit.jupiter.api.Assertions.assertEquals("comprador@mail.com", captor.getValue().emailComprador());
    org.junit.jupiter.api.Assertions.assertEquals("oid-123", captor.getValue().usuarioId());
    org.junit.jupiter.api.Assertions.assertEquals(39980L, captor.getValue().totalClp());
    org.junit.jupiter.api.Assertions.assertEquals(19990L, captor.getValue().items().get(0).precioUnitarioClp());
    org.assertj.core.api.Assertions.assertThat(events.stream(InteraccionEvent.class).map(InteraccionEvent::tipo))
        .containsExactly(InteraccionEvent.Tipo.CUPON_INGRESADO, InteraccionEvent.Tipo.ENVIO_CONFIRMADO);
  }

  @Test
  void checkout_conPreferredUsername_usaEseEmail() throws Exception {
    Carrito carrito = carrito("oid-123");
    when(carritoRepository.findByUsuarioId("oid-123")).thenReturn(Optional.of(carrito));
    when(carritoRepository.save(any(Carrito.class))).thenReturn(carrito);
    CarritoItem ci = item(carrito.getId());
    catalogo(ci.getProductoId(), 19990L, 3);
    when(itemRepository.findByCarritoId(carrito.getId())).thenReturn(List.of(ci));

    mockMvc.perform(post("/carrito/checkout")
            .with(jwt().jwt(j -> j.claim("oid", "oid-123").claim("preferred_username", "upn@mail.com")).authorities(new SimpleGrantedAuthority("SCOPE_Carrito.ReadWrite"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.emailComprador").value("upn@mail.com"));
  }

  @Test
  void checkout_sinEmail_retorna400() throws Exception {
    // JWT válido con scope pero sin email ni preferred_username: la compra se rechaza.
    mockMvc.perform(post("/carrito/checkout")
            .with(jwt().jwt(j -> j.claim("oid", "oid-123")).authorities(new SimpleGrantedAuthority("SCOPE_Carrito.ReadWrite"))))
        .andExpect(status().isBadRequest());

    verify(ordenEventPublisher, org.mockito.Mockito.never()).publicarOrdenCreada(any());
  }

  @Test
  void checkout_sinJwt_retorna401() throws Exception {
    mockMvc.perform(post("/carrito/checkout"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void checkout_conEmailParam_sinEmailEnToken_retorna200() throws Exception {
    // JWT sin email (Azure corporativo) + email declarado en el request.
    Carrito carrito = carrito("oid-123");
    when(carritoRepository.findByUsuarioId("oid-123")).thenReturn(Optional.of(carrito));
    when(carritoRepository.save(any(Carrito.class))).thenReturn(carrito);
    CarritoItem ci = item(carrito.getId());
    catalogo(ci.getProductoId(), 19990L, 3);
    when(itemRepository.findByCarritoId(carrito.getId())).thenReturn(List.of(ci));

    mockMvc.perform(post("/carrito/checkout")
            .param("email", "comprador@mail.com")
            .with(jwt().jwt(j -> j.claim("oid", "oid-123")).authorities(new SimpleGrantedAuthority("SCOPE_Carrito.ReadWrite"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.emailComprador").value("comprador@mail.com"));

    ArgumentCaptor<OrdenCreadaEvent> captor = ArgumentCaptor.forClass(OrdenCreadaEvent.class);
    verify(ordenEventPublisher).publicarOrdenCreada(captor.capture());
    org.junit.jupiter.api.Assertions.assertEquals("comprador@mail.com", captor.getValue().emailComprador());
  }

  @Test
  void checkout_conEmailParamInvalido_retorna400() throws Exception {
    mockMvc.perform(post("/carrito/checkout")
            .param("email", "no-es-email")
            .with(jwt().jwt(j -> j.claim("oid", "oid-123")).authorities(new SimpleGrantedAuthority("SCOPE_Carrito.ReadWrite"))))
        .andExpect(status().isBadRequest());

    verify(ordenEventPublisher, org.mockito.Mockito.never()).publicarOrdenCreada(any());
  }

  @Test
  void checkout_sinCatalogoNoRegistraNiVaciaElCarrito() throws Exception {
    Carrito carrito = carrito("oid-123");
    CarritoItem ci = item(carrito.getId());
    when(carritoRepository.findByUsuarioId("oid-123")).thenReturn(Optional.of(carrito));
    when(itemRepository.findByCarritoId(carrito.getId())).thenReturn(List.of(ci));
    when(catalogoPrecios.consultar(ci.getProductoId())).thenThrow(new org.springframework.web.server.ResponseStatusException(
        org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "No pudimos consultar los precios"));
    mockMvc.perform(post("/carrito/checkout").param("email", "comprador@mail.com")
            .with(jwt().jwt(j -> j.claim("oid", "oid-123"))
                .authorities(new SimpleGrantedAuthority("SCOPE_Carrito.ReadWrite"))))
        .andExpect(status().isServiceUnavailable());
    verify(ordenEventPublisher, org.mockito.Mockito.never()).publicarOrdenCreada(any());
    verify(itemRepository, org.mockito.Mockito.never()).deleteAll(any());
  }

  @Test
  void agregarItemRechazaDesbordeSinGuardar() throws Exception {
    Carrito carrito = carrito("oid-123");
    when(carritoRepository.findByUsuarioId("oid-123")).thenReturn(Optional.of(carrito));
    CarritoItem existente = item(carrito.getId());
    existente.setCantidad(Integer.MAX_VALUE);
    when(itemRepository.findByCarritoIdAndProductoId(carrito.getId(), existente.getProductoId()))
        .thenReturn(Optional.of(existente));

    CarritoItem payload = new CarritoItem();
    payload.setProductoId(existente.getProductoId());
    payload.setCantidad(1);
    payload.setPrecioUnitarioClp(19990L);

    mockMvc.perform(post("/carrito/items")
            .with(jwt().jwt(j -> j.claim("oid", "oid-123")).authorities(new SimpleGrantedAuthority("SCOPE_Carrito.ReadWrite")))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(payload)))
        .andExpect(status().isBadRequest());

    verify(itemRepository, never()).save(any(CarritoItem.class));
    assertThat(events.stream(InteraccionEvent.class)).isEmpty();
  }

  @Test
  void agregarItemIgnoraPrecioManipulado() throws Exception {
    Carrito carrito = carrito("oid-123");
    when(carritoRepository.findByUsuarioId("oid-123")).thenReturn(Optional.of(carrito));
    when(itemRepository.save(any(CarritoItem.class))).thenAnswer(inv -> inv.getArgument(0));
    UUID productoId = UUID.randomUUID();
    catalogo(productoId, 19990L, 5);

    CarritoItem payload = new CarritoItem();
    payload.setProductoId(productoId);
    payload.setCantidad(1);
    payload.setPrecioUnitarioClp(1L);

    mockMvc.perform(post("/carrito/items")
            .with(jwt().jwt(j -> j.claim("oid", "oid-123")).authorities(new SimpleGrantedAuthority("SCOPE_Carrito.ReadWrite")))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(payload)))
        .andExpect(status().isCreated());

    ArgumentCaptor<CarritoItem> captor = ArgumentCaptor.forClass(CarritoItem.class);
    verify(itemRepository).save(captor.capture());
    assertThat(captor.getValue().getPrecioUnitarioClp()).isEqualTo(19990L);
  }

  @Test
  void agregarItemValidaCantidadAcumuladaContraStock() throws Exception {
    Carrito carrito = carrito("oid-123");
    when(carritoRepository.findByUsuarioId("oid-123")).thenReturn(Optional.of(carrito));
    CarritoItem existente = item(carrito.getId()); // cantidad 2
    when(itemRepository.findByCarritoIdAndProductoId(carrito.getId(), existente.getProductoId()))
        .thenReturn(Optional.of(existente));
    catalogo(existente.getProductoId(), 19990L, 3);

    CarritoItem payload = new CarritoItem();
    payload.setProductoId(existente.getProductoId());
    payload.setCantidad(2);
    payload.setPrecioUnitarioClp(19990L);

    mockMvc.perform(post("/carrito/items")
            .with(jwt().jwt(j -> j.claim("oid", "oid-123")).authorities(new SimpleGrantedAuthority("SCOPE_Carrito.ReadWrite")))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(payload)))
        .andExpect(status().isConflict());

    verify(itemRepository, never()).save(any(CarritoItem.class));
    assertThat(events.stream(InteraccionEvent.class)).isEmpty();
  }

  @Test
  void agregarItemRechazaCantidadInvalida() throws Exception {
    Carrito carrito = carrito("oid-123");
    when(carritoRepository.findByUsuarioId("oid-123")).thenReturn(Optional.of(carrito));

    for (int cantidad : new int[] {0, -1}) {
      CarritoItem payload = new CarritoItem();
      payload.setProductoId(UUID.randomUUID());
      payload.setCantidad(cantidad);
      payload.setPrecioUnitarioClp(19990L);

      mockMvc.perform(post("/carrito/items")
              .with(jwt().jwt(j -> j.claim("oid", "oid-123")).authorities(new SimpleGrantedAuthority("SCOPE_Carrito.ReadWrite")))
              .contentType(MediaType.APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(payload)))
          .andExpect(status().isBadRequest());
    }

    verify(itemRepository, never()).save(any(CarritoItem.class));
    assertThat(events.stream(InteraccionEvent.class)).isEmpty();
  }

  @Test
  void actualizarItemNoPermiteCambiarProducto() throws Exception {
    Carrito carrito = carrito("oid-123");
    when(carritoRepository.findByUsuarioId("oid-123")).thenReturn(Optional.of(carrito));
    CarritoItem existente = item(carrito.getId());
    UUID productoOriginal = existente.getProductoId();
    when(itemRepository.findByIdAndCarritoId(existente.getId(), carrito.getId()))
        .thenReturn(Optional.of(existente));

    CarritoItem payload = new CarritoItem();
    payload.setProductoId(UUID.randomUUID());
    payload.setCantidad(1);
    payload.setPrecioUnitarioClp(1L);

    mockMvc.perform(put("/carrito/items/{id}", existente.getId())
            .with(jwt().jwt(j -> j.claim("oid", "oid-123")).authorities(new SimpleGrantedAuthority("SCOPE_Carrito.ReadWrite")))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(payload)))
        .andExpect(status().isBadRequest());

    verify(itemRepository, never()).save(any(CarritoItem.class));
    assertThat(existente.getProductoId()).isEqualTo(productoOriginal);
    assertThat(existente.getCantidad()).isEqualTo(2);
  }

  @Test
  void actualizarItemUsaPrecioCanonicoYValidaStock() throws Exception {
    Carrito carrito = carrito("oid-123");
    when(carritoRepository.findByUsuarioId("oid-123")).thenReturn(Optional.of(carrito));
    CarritoItem existente = item(carrito.getId());
    when(itemRepository.findByIdAndCarritoId(existente.getId(), carrito.getId()))
        .thenReturn(Optional.of(existente));
    when(itemRepository.save(any(CarritoItem.class))).thenAnswer(inv -> inv.getArgument(0));
    catalogo(existente.getProductoId(), 19990L, 3);

    CarritoItem payload = new CarritoItem();
    payload.setProductoId(existente.getProductoId());
    payload.setCantidad(1);
    payload.setPrecioUnitarioClp(1L);

    mockMvc.perform(put("/carrito/items/{id}", existente.getId())
            .with(jwt().jwt(j -> j.claim("oid", "oid-123")).authorities(new SimpleGrantedAuthority("SCOPE_Carrito.ReadWrite")))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(payload)))
        .andExpect(status().isOk());

    ArgumentCaptor<CarritoItem> captor = ArgumentCaptor.forClass(CarritoItem.class);
    verify(itemRepository).save(captor.capture());
    assertThat(captor.getValue().getPrecioUnitarioClp()).isEqualTo(19990L);
    assertThat(captor.getValue().getProductoId()).isEqualTo(existente.getProductoId());

    payload.setCantidad(4);
    mockMvc.perform(put("/carrito/items/{id}", existente.getId())
            .with(jwt().jwt(j -> j.claim("oid", "oid-123")).authorities(new SimpleGrantedAuthority("SCOPE_Carrito.ReadWrite")))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(payload)))
        .andExpect(status().isConflict());

    verify(itemRepository, org.mockito.Mockito.times(1)).save(any(CarritoItem.class));
  }

  private void prepararCheckout(CarritoItem... items) {
    Carrito carrito = carrito("oid-123");
    when(carritoRepository.findByUsuarioId("oid-123")).thenReturn(Optional.of(carrito));
    when(itemRepository.findByCarritoId(carrito.getId())).thenReturn(List.of(items));
  }

  @Test
  void checkoutRechazaProductoSinStockSinVaciar() throws Exception {
    CarritoItem ci = item(UUID.randomUUID());
    prepararCheckout(ci);
    catalogo(ci.getProductoId(), 19990L, 0);

    mockMvc.perform(post("/carrito/checkout").param("email", "comprador@mail.com")
            .with(jwt().jwt(j -> j.claim("oid", "oid-123"))
                .authorities(new SimpleGrantedAuthority("SCOPE_Carrito.ReadWrite"))))
        .andExpect(status().isConflict());

    verify(ordenEventPublisher, never()).publicarOrdenCreada(any());
    verify(itemRepository, never()).deleteAll(any());
    assertThat(events.stream(InteraccionEvent.class)).isEmpty();
  }

  @Test
  void checkoutRechazaCantidadMayorAlStockSinVaciar() throws Exception {
    CarritoItem ci = item(UUID.randomUUID());
    ci.setCantidad(4);
    prepararCheckout(ci);
    catalogo(ci.getProductoId(), 19990L, 3);

    mockMvc.perform(post("/carrito/checkout").param("email", "comprador@mail.com")
            .with(jwt().jwt(j -> j.claim("oid", "oid-123"))
                .authorities(new SimpleGrantedAuthority("SCOPE_Carrito.ReadWrite"))))
        .andExpect(status().isConflict());

    verify(ordenEventPublisher, never()).publicarOrdenCreada(any());
    verify(itemRepository, never()).deleteAll(any());
    assertThat(events.stream(InteraccionEvent.class)).isEmpty();
  }

  @Test
  void checkoutValidaStockAgregadoDeLineasDuplicadas() throws Exception {
    CarritoItem primera = item(UUID.randomUUID());
    primera.setCantidad(2);
    CarritoItem segunda = item(primera.getCarritoId());
    segunda.setProductoId(primera.getProductoId());
    segunda.setCantidad(2);
    prepararCheckout(primera, segunda);
    catalogo(primera.getProductoId(), 19990L, 3);

    mockMvc.perform(post("/carrito/checkout").param("email", "comprador@mail.com")
            .with(jwt().jwt(j -> j.claim("oid", "oid-123"))
                .authorities(new SimpleGrantedAuthority("SCOPE_Carrito.ReadWrite"))))
        .andExpect(status().isConflict());

    verify(ordenEventPublisher, never()).publicarOrdenCreada(any());
    verify(itemRepository, never()).deleteAll(any());
    assertThat(events.stream(InteraccionEvent.class)).isEmpty();
  }

  @Test
  void checkoutConsultaUnaVezCadaProducto() throws Exception {
    CarritoItem primera = item(UUID.randomUUID());
    primera.setCantidad(1);
    CarritoItem segunda = item(primera.getCarritoId());
    segunda.setProductoId(primera.getProductoId());
    segunda.setCantidad(1);
    prepararCheckout(primera, segunda);
    catalogo(primera.getProductoId(), 19990L, 3);

    mockMvc.perform(post("/carrito/checkout").param("email", "comprador@mail.com")
            .with(jwt().jwt(j -> j.claim("oid", "oid-123"))
                .authorities(new SimpleGrantedAuthority("SCOPE_Carrito.ReadWrite"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalClp").value(39980));

    verify(catalogoPrecios, org.mockito.Mockito.times(1)).consultar(primera.getProductoId());
  }

  @Test
  void checkoutDetectaDesbordeMonetarioSinVaciar() throws Exception {
    CarritoItem ci = item(UUID.randomUUID());
    ci.setCantidad(2);
    prepararCheckout(ci);
    catalogo(ci.getProductoId(), Long.MAX_VALUE, 3);

    mockMvc.perform(post("/carrito/checkout").param("email", "comprador@mail.com")
            .with(jwt().jwt(j -> j.claim("oid", "oid-123"))
                .authorities(new SimpleGrantedAuthority("SCOPE_Carrito.ReadWrite"))))
        .andExpect(status().isBadRequest());

    verify(ordenEventPublisher, never()).publicarOrdenCreada(any());
    verify(itemRepository, never()).deleteAll(any());
    assertThat(events.stream(InteraccionEvent.class)).isEmpty();
  }
}
