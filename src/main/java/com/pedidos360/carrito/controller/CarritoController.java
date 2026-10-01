package com.pedidos360.carrito.controller;

import com.pedidos360.carrito.messaging.OrdenCreadaEvent;
import com.pedidos360.carrito.messaging.OrdenEventPublisher;
import com.pedidos360.carrito.model.Carrito;
import com.pedidos360.carrito.model.CarritoItem;
import com.pedidos360.carrito.repository.CarritoItemRepository;
import com.pedidos360.carrito.repository.CarritoRepository;
import jakarta.validation.Valid;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/carrito")
public class CarritoController {

  private final CarritoRepository carritoRepository;
  private final CarritoItemRepository itemRepository;
  private final OrdenEventPublisher ordenEventPublisher;

  public CarritoController(CarritoRepository carritoRepository, CarritoItemRepository itemRepository,
      OrdenEventPublisher ordenEventPublisher) {
    this.carritoRepository = carritoRepository;
    this.itemRepository = itemRepository;
    this.ordenEventPublisher = ordenEventPublisher;
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

  private String emailFrom(Jwt jwt) {
    if (jwt == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token requerido");
    // Misma resolución que UsuarioDto (ms-login): claim "email", fallback "preferred_username".
    // El email es obligatorio para notificar la compra: sin él, 400 (no se publica el evento).
    String email = jwt.getClaimAsString("email");
    if (email == null || email.isBlank()) email = jwt.getClaimAsString("preferred_username");
    if (email == null || email.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Token sin email: no se puede notificar la compra");
    }
    return email;
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
    // Si el producto ya está en el carrito, suma la cantidad en vez de duplicar la línea.
    Optional<CarritoItem> existente = (body.getProductoId() != null)
        ? itemRepository.findByCarritoIdAndProductoId(carrito.getId(), body.getProductoId())
        : Optional.empty();
    if (existente.isPresent()) {
      CarritoItem item = existente.get();
      item.setCantidad(item.getCantidad() + body.getCantidad());
      return ResponseEntity.ok(itemRepository.save(item));
    }
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
  public Map<String, Object> checkout(@AuthenticationPrincipal Jwt jwt,
      @RequestParam(required = false) String codigoCupon) {
    String usuarioId = userIdFrom(jwt);
    String emailComprador = emailFrom(jwt);
    Carrito carrito = getOrCreateCarrito(usuarioId);
    List<CarritoItem> items = itemRepository.findByCarritoId(carrito.getId());
    if (items.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Carrito vacío");
    long total = items.stream()
        .mapToLong(i -> (i.getPrecioUnitarioClp() != null ? i.getPrecioUnitarioClp() : 0L) * i.getCantidad())
        .sum();
    // Publica ANTES de vaciar: si el broker falla (503), el carrito sigue
    // intacto y el cliente puede reintentar sin perder su compra.
    // SIMPLIFICADO: sin tabla de outbox; si el proceso muere entre el
    // publish y el deleteAll, un reintento del cliente duplicaría el evento.
    // Upgrade: outbox transaccional o clave de idempotencia por ordenId.
    UUID ordenId = UUID.randomUUID();
    List<OrdenCreadaEvent.Item> itemsEvento = items.stream()
        .map(i -> new OrdenCreadaEvent.Item(i.getProductoId(), i.getCantidad(),
            i.getPrecioUnitarioClp() != null ? i.getPrecioUnitarioClp() : 0L))
        .toList();
    ordenEventPublisher.publicarOrdenCreada(new OrdenCreadaEvent(ordenId, usuarioId, emailComprador,
        itemsEvento, total, codigoCupon));
    itemRepository.deleteAll(items);
    Map<String, Object> resp = new HashMap<>();
    resp.put("ordenId", ordenId);
    resp.put("codigoCupon", codigoCupon);
    resp.put("carritoId", carrito.getId());
    resp.put("usuarioId", usuarioId);
    resp.put("emailComprador", emailComprador);
    resp.put("itemsComprados", items.size());
    resp.put("totalClp", total);
    resp.put("estado", "checkout_ok");
    return resp;
  }
}
