package com.pedidos360.carrito.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.RejectedExecutionException;

@Component
@ConditionalOnProperty(name = "p360.kafka.enabled", havingValue = "true")
class InteraccionKafkaPublisher {
  private static final Logger log = LoggerFactory.getLogger(InteraccionKafkaPublisher.class);
  private static final String TOPIC = "p360.interacciones.v1";
  private final KafkaTemplate<String, InteraccionEvent> kafka;
  private final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

  InteraccionKafkaPublisher(KafkaTemplate<String, InteraccionEvent> kafka) {
    this.kafka = kafka;
    executor.setQueueCapacity(128);
    executor.setThreadNamePrefix("kafka-interacciones-");
    executor.initialize();
  }

  @EventListener
  void publicar(InteraccionEvent evento) {
    try {
      executor.execute(() -> {
        try {
          kafka.send(TOPIC, evento.usuarioId(), evento).whenComplete((ok, error) -> {
            if (error != null) log.warn("Kafka rechazó interacción {}", evento.eventoId(), error);
          });
        } catch (RuntimeException error) {
          log.warn("Kafka no recibió interacción {}", evento.eventoId(), error);
        }
      });
    } catch (RejectedExecutionException error) {
      // SIMPLIFICADO: telemetría best effort; persistir un outbox si perder visitas deja de ser aceptable.
      log.warn("Cola de telemetría llena: se omite interacción {}", evento.eventoId());
    }
  }

  @PreDestroy
  void cerrar() { executor.shutdown(); }
}
