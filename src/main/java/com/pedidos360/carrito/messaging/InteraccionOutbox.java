package com.pedidos360.carrito.messaging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Fila persistente de telemetría: sobrevive a caídas del broker y del proceso. */
@Entity
@Table(name = "interacciones_outbox", indexes = {
    @Index(name = "idx_interaccion_publicado", columnList = "publicadoEn,ocurrioEn")})
class InteraccionOutbox {
  @Id private UUID eventoId;
  @Column(nullable = false, length = 150) private String usuarioId;
  @Column(nullable = false) private String tipo;
  private UUID productoId;
  @Column(nullable = false) private Instant ocurrioEn;
  private Instant publicadoEn;

  protected InteraccionOutbox() {}

  InteraccionOutbox(InteraccionEvent evento) {
    this.eventoId = evento.eventoId();
    this.usuarioId = evento.usuarioId();
    this.tipo = evento.tipo().name();
    this.productoId = evento.productoId();
    this.ocurrioEn = evento.ocurrioEn();
  }

  UUID getEventoId() { return eventoId; }
  Instant getPublicadoEn() { return publicadoEn; }

  InteraccionEvent evento() {
    return new InteraccionEvent(eventoId, usuarioId, InteraccionEvent.Tipo.valueOf(tipo), productoId, ocurrioEn);
  }
}
