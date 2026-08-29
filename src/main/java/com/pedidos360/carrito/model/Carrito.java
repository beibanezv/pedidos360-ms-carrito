package com.pedidos360.carrito.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "carritos")
public class Carrito {

  @Id
  @GeneratedValue(strategy = GenerationType.AUTO)
  private UUID id;

  @Column(nullable = false, unique = true)
  private String usuarioId;

  @Column(nullable = false, updatable = false)
  private Instant createdAt = Instant.now();

  public Carrito() {}

  public Carrito(String usuarioId) {
    this.usuarioId = usuarioId;
  }

  public UUID getId() { return id; }
  public void setId(UUID id) { this.id = id; }

  public String getUsuarioId() { return usuarioId; }
  public void setUsuarioId(String usuarioId) { this.usuarioId = usuarioId; }

  public Instant getCreatedAt() { return createdAt; }
  public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
