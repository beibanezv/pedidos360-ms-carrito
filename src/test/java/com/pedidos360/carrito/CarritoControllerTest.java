package com.pedidos360.carrito;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pedidos360.carrito.config.SecurityConfig;
import com.pedidos360.carrito.model.Carrito;
import com.pedidos360.carrito.model.CarritoItem;
import com.pedidos360.carrito.repository.CarritoItemRepository;
import com.pedidos360.carrito.repository.CarritoRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
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
class CarritoControllerTest {

  @Autowired MockMvc mockMvc;
  @Autowired ObjectMapper objectMapper;
  @MockBean CarritoRepository carritoRepository;
  @MockBean CarritoItemRepository itemRepository;
  @MockBean JwtDecoder jwtDecoder;

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

    mockMvc.perform(post("/carrito/items")
            .with(jwt().jwt(j -> j.claim("oid", "oid-123")).authorities(new SimpleGrantedAuthority("SCOPE_Carrito.ReadWrite")))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(payload)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.carritoId").value(carrito.getId().toString()));
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
    when(itemRepository.findByCarritoId(carrito.getId())).thenReturn(List.of(ci));

    mockMvc.perform(post("/carrito/checkout")
            .with(jwt().jwt(j -> j.claim("oid", "oid-123")).authorities(new SimpleGrantedAuthority("SCOPE_Carrito.ReadWrite"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.estado").value("checkout_ok"))
        .andExpect(jsonPath("$.totalClp").value(39980));
  }

  @Test
  void checkout_sinJwt_retorna401() throws Exception {
    mockMvc.perform(post("/carrito/checkout"))
        .andExpect(status().isUnauthorized());
  }
}
