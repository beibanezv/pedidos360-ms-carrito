package com.pedidos360.carrito.checkout;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

/** Operación de checkout ya aceptada: identidad (usuarioId, idempotencyKey) y respuesta guardada. */
@Entity
@Table(name = "checkout_operaciones", uniqueConstraints = @UniqueConstraint(
    name = "uk_checkout_usuario_clave", columnNames = {"usuario_id", "idempotency_key"}))
public class CheckoutOperacion {

  @Id
  private UUID id;

  @Column(nullable = false)
  private String usuarioId;

  @Column(nullable = false)
  private String idempotencyKey;

  @Column(nullable = false, columnDefinition = "text")
  private String snapshotJson;

  @Column(nullable = false)
  private String snapshotHash;

  @Column(nullable = false, columnDefinition = "text")
  private String respuestaJson;

  @Column(nullable = false)
  private UUID carritoId;

  @Column(nullable = false, updatable = false)
  private Instant creadaEn = Instant.now();

  public CheckoutOperacion() {}

  public UUID getId() { return id; }
  public void setId(UUID id) { this.id = id; }

  public String getUsuarioId() { return usuarioId; }
  public void setUsuarioId(String usuarioId) { this.usuarioId = usuarioId; }

  public String getIdempotencyKey() { return idempotencyKey; }
  public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

  public String getSnapshotJson() { return snapshotJson; }
  public void setSnapshotJson(String snapshotJson) { this.snapshotJson = snapshotJson; }

  public String getSnapshotHash() { return snapshotHash; }
  public void setSnapshotHash(String snapshotHash) { this.snapshotHash = snapshotHash; }

  public String getRespuestaJson() { return respuestaJson; }
  public void setRespuestaJson(String respuestaJson) { this.respuestaJson = respuestaJson; }

  public UUID getCarritoId() { return carritoId; }
  public void setCarritoId(UUID carritoId) { this.carritoId = carritoId; }

  public Instant getCreadaEn() { return creadaEn; }
  public void setCreadaEn(Instant creadaEn) { this.creadaEn = creadaEn; }
}
