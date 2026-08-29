package com.pedidos360.carrito.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "carrito_items")
public class CarritoItem {

  @Id
  @GeneratedValue(strategy = GenerationType.AUTO)
  private UUID id;

  @Column(nullable = false)
  private UUID carritoId;

  @NotNull
  @Column(nullable = false)
  private UUID productoId;

  @NotNull
  @Min(1)
  @Column(nullable = false)
  private Integer cantidad;

  // snapshot del precio al agregar al carrito (evita inconsistencia si ms-productos cambia precio)
  @Min(0)
  private Long precioUnitarioClp;

  // compatibilidad con codigo previo (alias)
  public Long getPrecioClp() { return precioUnitarioClp; }
  public void setPrecioClp(Long v) { this.precioUnitarioClp = v; }

  @Column(nullable = false, updatable = false)
  private Instant agregadoEn = Instant.now();

  public CarritoItem() {}

  public UUID getId() { return id; }
  public void setId(UUID id) { this.id = id; }

  public UUID getCarritoId() { return carritoId; }
  public void setCarritoId(UUID carritoId) { this.carritoId = carritoId; }

  public UUID getProductoId() { return productoId; }
  public void setProductoId(UUID productoId) { this.productoId = productoId; }

  public Integer getCantidad() { return cantidad; }
  public void setCantidad(Integer cantidad) { this.cantidad = cantidad; }

  public Long getPrecioUnitarioClp() { return precioUnitarioClp; }
  public void setPrecioUnitarioClp(Long precioUnitarioClp) { this.precioUnitarioClp = precioUnitarioClp; }

  public Instant getAgregadoEn() { return agregadoEn; }
  public void setAgregadoEn(Instant agregadoEn) { this.agregadoEn = agregadoEn; }
}
