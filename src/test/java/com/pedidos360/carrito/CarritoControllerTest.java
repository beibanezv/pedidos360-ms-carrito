package com.pedidos360.carrito;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pedidos360.carrito.config.SecurityConfig;
import com.pedidos360.carrito.model.CarritoItem;
import com.pedidos360.carrito.repository.CarritoItemRepository;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(com.pedidos360.carrito.controller.CarritoController.class)
@Import(SecurityConfig.class)
class CarritoControllerTest {

  @Autowired MockMvc mockMvc;
  @Autowired ObjectMapper objectMapper;
  @MockBean CarritoItemRepository repository;
  @MockBean JwtDecoder jwtDecoder;

  private CarritoItem item(String userId) {
    CarritoItem c = new CarritoItem();
    c.setId(UUID.randomUUID());
    c.setUserId(userId);
    c.setProductoId(UUID.randomUUID());
    c.setCantidad(2);
    c.setPrecioClp(19990L);
    return c;
  }

  @Test
  void getCarrito_sinJwt_retorna401() throws Exception {
    mockMvc.perform(get("/carrito"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void getCarrito_conJwtPeroSinScope_retorna403() throws Exception {
    // autenticado pero sin SCOPE_Carrito.ReadWrite ni ROLE_Cliente -> 403 por SecurityConfig
    mockMvc.perform(get("/carrito").with(jwt()))
        .andExpect(status().isForbidden());
  }

  @Test
  void getCarrito_conScope_retorna200FiltraPorUserId() throws Exception {
    CarritoItem c = item("oid-123");
    when(repository.findByUserId("oid-123")).thenReturn(List.of(c));

    mockMvc.perform(get("/carrito")
            .with(jwt().jwt(j -> j.claim("oid", "oid-123")).authorities(new SimpleGrantedAuthority("SCOPE_Carrito.ReadWrite"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].userId").value("oid-123"));
  }

  @Test
  void postCarrito_sinJwt_retorna401() throws Exception {
    CarritoItem payload = item(null);
    payload.setId(null);
    payload.setUserId(null);
    mockMvc.perform(post("/carrito")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(payload)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void postCarrito_conScope_retorna201() throws Exception {
    CarritoItem guardado = item("oid-123");
    when(repository.save(any(CarritoItem.class))).thenReturn(guardado);

    CarritoItem payload = new CarritoItem();
    payload.setProductoId(UUID.randomUUID());
    payload.setCantidad(1);
    payload.setPrecioClp(9990L);

    mockMvc.perform(post("/carrito")
            .with(jwt().jwt(j -> j.claim("oid", "oid-123")).authorities(new SimpleGrantedAuthority("SCOPE_Carrito.ReadWrite")))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(payload)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.userId").value("oid-123"));
  }

  @Test
  void deleteCarrito_inexistente_retorna404() throws Exception {
    UUID id = UUID.randomUUID();
    when(repository.findByIdAndUserId(any(), any())).thenReturn(Optional.empty());

    mockMvc.perform(delete("/carrito/{id}", id)
            .with(jwt().jwt(j -> j.claim("oid", "oid-123")).authorities(new SimpleGrantedAuthority("SCOPE_Carrito.ReadWrite"))))
        .andExpect(status().isNotFound());
  }
}
