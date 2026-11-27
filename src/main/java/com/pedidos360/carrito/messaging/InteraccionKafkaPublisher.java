package com.pedidos360.carrito.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Guarda la interacción antes de publicarla: el outbox la envía después con ACK.
 * Ya no hay cola en memoria ni telemetría best effort que se pierda al reiniciar.
 */
@Component
@ConditionalOnProperty(name = "p360.kafka.enabled", havingValue = "true")
class InteraccionKafkaPublisher {
  private final InteraccionOutboxRepository repository;

  InteraccionKafkaPublisher(InteraccionOutboxRepository repository) {
    this.repository = repository;
  }

  @EventListener
  void publicar(InteraccionEvent evento) {
    repository.save(new InteraccionOutbox(evento));
  }
}
