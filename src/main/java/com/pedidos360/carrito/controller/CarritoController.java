package com.pedidos360.carrito.controller;

import com.pedidos360.carrito.model.Carrito;
import com.pedidos360.carrito.model.CarritoItem;
import com.pedidos360.carrito.repository.CarritoItemRepository;
import com.pedidos360.carrito.repository.CarritoRepository;
import jakarta.validation.Valid;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/carrito")
public class CarritoController {

  private final CarritoRepository carritoRepository;
  private final CarritoItemRepository itemRepository;

  public CarritoController(CarritoRepository carritoRepository, CarritoItemRepository itemRepository) {
    this.carritoRepository = carritoRepository;
    this.itemRepository = itemRepository;
  }

  private String userIdFrom(Jwt jwt) {
    if (jwt == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token requerido");
    String oid = jwt.getClaimAsString("oid");
    if (oid != null && !oid.isBlank()) return oid;
    String sub = jwt.getClaimAsString("sub");
    if (sub != null && !sub.isBlank()) return sub;
    String subject = jwt.getSubject();
    if (subject != null && !subject.isBlank()) return subject;
    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token sin sub/oid");
  }

  private Carrito getOrCreateCarrito(String usuarioId) {
    return carritoRepository.findByUsuarioId(usuarioId)
        .orElseGet(() -> carritoRepository.save(new Carrito(usuarioId)));
  }

  // Todos requieren JWT (ver SecurityConfig). Escritura además requiere scope.
  @GetMapping
  public Map<String, Object> obtenerCarrito(@AuthenticationPrincipal Jwt jwt) {
    String usuarioId = userIdFrom(jwt);
    Carrito carrito = getOrCreateCarrito(usuarioId);
    List<CarritoItem> items = itemRepository.findByCarritoId(carrito.getId());
    Map<String, Object> resp = new HashMap<>();
    resp.put("id", carrito.getId());
    resp.put("usuarioId", carrito.getUsuarioId());
    resp.put("createdAt", carrito.getCreatedAt());
    resp.put("items", items);
    return resp;
  }

  @PostMapping("/items")
  @PreAuthorize("hasAuthority('SCOPE_Carrito.ReadWrite') or hasRole('Cliente') or hasRole('Admin')")
  public ResponseEntity<CarritoItem> agregarItem(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CarritoItem body) {
    // SIMPLIFICADO: scope validado via @PreAuthorize (ej. Carrito.ReadWrite). Alternativa: hasAuthority en filterChain.
    String usuarioId = userIdFrom(jwt);
    Carrito carrito = getOrCreateCarrito(usuarioId);
    body.setId(null);
    body.setCarritoId(carrito.getId());
    CarritoItem guardado = itemRepository.save(body);
    return ResponseEntity.status(HttpStatus.CREATED).body(guardado);
  }

  @PutMapping("/items/{id}")
  @PreAuthorize("hasAuthority('SCOPE_Carrito.ReadWrite') or hasRole('Cliente') or hasRole('Admin')")
  public CarritoItem actualizarItem(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody CarritoItem body) {
    String usuarioId = userIdFrom(jwt);
    Carrito carrito = getOrCreateCarrito(usuarioId);
    CarritoItem existente = itemRepository.findByIdAndCarritoId(id, carrito.getId())
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Item no encontrado"));
    existente.setCantidad(body.getCantidad());
    if (body.getPrecioUnitarioClp() != null) existente.setPrecioUnitarioClp(body.getPrecioUnitarioClp());
    if (body.getProductoId() != null) existente.setProductoId(body.getProductoId());
    return itemRepository.save(existente);
  }

  @DeleteMapping("/items/{id}")
  @PreAuthorize("hasAuthority('SCOPE_Carrito.ReadWrite') or hasRole('Cliente') or hasRole('Admin')")
  public ResponseEntity<Void> eliminarItem(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    String usuarioId = userIdFrom(jwt);
    Carrito carrito = carritoRepository.findByUsuarioId(usuarioId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Carrito no encontrado"));
    CarritoItem existente = itemRepository.findByIdAndCarritoId(id, carrito.getId())
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Item no encontrado"));
    itemRepository.delete(existente);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/checkout")
  @PreAuthorize("hasAuthority('SCOPE_Carrito.ReadWrite') or hasRole('Cliente') or hasRole('Admin')")
  public Map<String, Object> checkout(@AuthenticationPrincipal Jwt jwt) {
    String usuarioId = userIdFrom(jwt);
    Carrito carrito = getOrCreateCarrito(usuarioId);
    List<CarritoItem> items = itemRepository.findByCarritoId(carrito.getId());
    if (items.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Carrito vacío");
    long total = items.stream()
        .mapToLong(i -> (i.getPrecioUnitarioClp() != null ? i.getPrecioUnitarioClp() : 0L) * i.getCantidad())
        .sum();
    // SIMPLIFICADO: checkout no persiste orden ni descuenta stock; solo vacía el carrito.
    // Upgrade: crear entidad Orden, descontar stock en ms-productos via REST.
    itemRepository.deleteAll(items);
    Map<String, Object> resp = new HashMap<>();
    resp.put("carritoId", carrito.getId());
    resp.put("usuarioId", usuarioId);
    resp.put("itemsComprados", items.size());
    resp.put("totalClp", total);
    resp.put("estado", "checkout_ok");
    return resp;
  }
}
