package com.pedidos360.carrito.messaging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Salida persistente de orden.creada: la PK es el ordenId estable en cada reenvío. */
@Entity
@Table(name = "orden_outbox")
public class OrdenOutbox {

  @Id
  private UUID id;

  @Column(nullable = false, columnDefinition = "text")
  private String payloadJson;

  @Column(nullable = false)
  private String estado = "PENDIENTE";

  @Column(nullable = false)
  private int intentos;

  @Column(nullable = false, updatable = false)
  private Instant creadaEn = Instant.now();

  private Instant enviadaEn;

  public OrdenOutbox() {}

  public OrdenOutbox(UUID id, String payloadJson) {
    this.id = id;
    this.payloadJson = payloadJson;
  }

  public UUID getId() { return id; }
  public void setId(UUID id) { this.id = id; }

  public String getPayloadJson() { return payloadJson; }
  public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }

  public String getEstado() { return estado; }
  public void setEstado(String estado) { this.estado = estado; }

  public int getIntentos() { return intentos; }
  public void setIntentos(int intentos) { this.intentos = intentos; }

  public Instant getCreadaEn() { return creadaEn; }
  public void setCreadaEn(Instant creadaEn) { this.creadaEn = creadaEn; }

  public Instant getEnviadaEn() { return enviadaEn; }
  public void setEnviadaEn(Instant enviadaEn) { this.enviadaEn = enviadaEn; }
}
