package com.pedidos360.carrito.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Disparo periódico del outbox. Se separa del publisher para que las pruebas de
 * durabilidad puedan ejecutar {@link InteraccionOutboxPublisher#enviarPendientes()}
 * de forma determinista (p360.kafka.relay.auto=false).
 */
@Component
@ConditionalOnProperty(name = "p360.kafka.relay.auto", havingValue = "true", matchIfMissing = true)
class InteraccionOutboxScheduler {
  private final InteraccionOutboxPublisher publisher;

  InteraccionOutboxScheduler(InteraccionOutboxPublisher publisher) {
    this.publisher = publisher;
  }

  @Scheduled(fixedDelay = 1500)
  void disparar() {
    publisher.enviarPendientes();
  }
}
