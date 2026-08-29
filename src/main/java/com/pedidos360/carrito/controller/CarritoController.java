package com.pedidos360.carrito.controller;

import com.pedidos360.carrito.model.CarritoItem;
import com.pedidos360.carrito.repository.CarritoItemRepository;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

  private final CarritoItemRepository repository;

  public CarritoController(CarritoItemRepository repository) {
    this.repository = repository;
  }

  private String userIdFrom(Jwt jwt) {
    if (jwt == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token requerido");
    // Azure AD: oid es estable, sub es fallback
    String oid = jwt.getClaimAsString("oid");
    if (oid != null && !oid.isBlank()) return oid;
    String sub = jwt.getClaimAsString("sub");
    if (sub != null && !sub.isBlank()) return sub;
    // fallback para tests con jwt() mock sin oid/sub
    String subject = jwt.getSubject();
    if (subject != null && !subject.isBlank()) return subject;
    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token sin sub/oid");
  }

  @GetMapping
  public List<CarritoItem> listar(@AuthenticationPrincipal Jwt jwt) {
    String userId = userIdFrom(jwt);
    return repository.findByUserId(userId);
  }

  @PostMapping
  public ResponseEntity<CarritoItem> agregar(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CarritoItem item) {
    String userId = userIdFrom(jwt);
    item.setId(null);
    item.setUserId(userId);
    CarritoItem guardado = repository.save(item);
    return ResponseEntity.status(HttpStatus.CREATED).body(guardado);
  }

  @PutMapping("/{id}")
  public CarritoItem actualizar(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody CarritoItem body) {
    String userId = userIdFrom(jwt);
    CarritoItem existente = repository.findByIdAndUserId(id, userId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Item no encontrado"));
    existente.setCantidad(body.getCantidad());
    if (body.getPrecioClp() != null) existente.setPrecioClp(body.getPrecioClp());
    if (body.getProductoId() != null) existente.setProductoId(body.getProductoId());
    return repository.save(existente);
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> eliminar(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    String userId = userIdFrom(jwt);
    CarritoItem existente = repository.findByIdAndUserId(id, userId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Item no encontrado"));
    repository.delete(existente);
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping
  public ResponseEntity<Void> vaciar(@AuthenticationPrincipal Jwt jwt) {
    String userId = userIdFrom(jwt);
    List<CarritoItem> items = repository.findByUserId(userId);
    repository.deleteAll(items);
    return ResponseEntity.noContent().build();
  }
}
