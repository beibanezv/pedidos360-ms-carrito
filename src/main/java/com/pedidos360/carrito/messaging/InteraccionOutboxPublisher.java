package com.pedidos360.carrito.messaging;

import java.time.Clock;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Publica las interacciones guardadas; solo marca publicado después del ACK. */
@Component
@ConditionalOnProperty(name = "p360.kafka.enabled", havingValue = "true")
class InteraccionOutboxPublisher {
  private static final Logger log = LoggerFactory.getLogger(InteraccionOutboxPublisher.class);
  private static final String TOPIC = "p360.interacciones.v1";
  private final InteraccionOutboxRepository repository;
  private final KafkaTemplate<String, InteraccionEvent> kafka;
  private final Clock clock;

  InteraccionOutboxPublisher(InteraccionOutboxRepository repository,
      KafkaTemplate<String, InteraccionEvent> kafka, Clock clock) {
    this.repository = repository;
    this.kafka = kafka;
    this.clock = clock;
  }

  @Scheduled(fixedDelay = 1500)
  void enviarPendientes() {
    for (InteraccionOutbox fila : repository.findTop20ByPublicadoEnIsNullOrderByOcurrioEnAscEventoIdAsc()) {
      var evento = fila.evento();
      try {
        kafka.send(TOPIC, evento.usuarioId(), evento).get(13, TimeUnit.SECONDS);
        repository.marcarPublicado(evento.eventoId(), clock.instant());
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        return;
      } catch (Exception error) {
        // Sin ACK la fila sigue pendiente; el consumidor deduplica si se pierde el ACK.
        log.warn("Interacción {} pendiente de Kafka: {}", evento.eventoId(), error.getClass().getSimpleName());
        return;
      }
    }
  }
}
