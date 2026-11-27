package com.pedidos360.carrito.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pedidos360.carrito.config.RabbitMQConfig;
import com.pedidos360.carrito.repository.OrdenOutboxRepository;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.connection.CorrelationData.Confirm;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reenvía la salida persistida después del commit. Sin ConfirmCallback global: cada mensaje
 * lleva su CorrelationData y se espera la confirmación antes de marcar ENVIADO.
 */
@Component
public class OrdenOutboxRelay {

  private final RabbitTemplate rabbitTemplate;
  private final OrdenOutboxRepository outboxRepository;
  private final ObjectMapper mapper;

  public OrdenOutboxRelay(RabbitTemplate rabbitTemplate, OrdenOutboxRepository outboxRepository,
      ObjectMapper mapper) {
    this.rabbitTemplate = rabbitTemplate;
    this.outboxRepository = outboxRepository;
    this.mapper = mapper;
  }

  @Scheduled(fixedDelayString = "${p360.checkout.outbox.delay-ms:2000}",
      initialDelayString = "${p360.checkout.outbox.delay-ms:2000}")
  public void reenviarPendientes() {
    List<OrdenOutbox> pendientes = outboxRepository.findByEstadoOrderByCreadaEnAsc(
        "PENDIENTE", PageRequest.of(0, 20));
    for (OrdenOutbox outbox : pendientes) {
      enviar(outbox);
    }
  }

  private void enviar(OrdenOutbox outbox) {
    try {
      OrdenCreadaEvent evento = mapper.readValue(outbox.getPayloadJson(), OrdenCreadaEvent.class);
      // eventoId = ordenId: estable en cada reenvío; B08 deduplica aguas abajo.
      CorrelationData correlacion = new CorrelationData(evento.ordenId().toString());
      rabbitTemplate.convertAndSend(RabbitMQConfig.ORDENES_EXCHANGE,
          RabbitMQConfig.ORDENES_ROUTING_KEY, evento, correlacion);
      Confirm confirm = correlacion.getFuture().get(5, TimeUnit.SECONDS);
      if (confirm != null && confirm.isAck()) {
        outboxRepository.marcarEnviado(outbox.getId(), Instant.now());
      } else {
        outboxRepository.incrementarIntentos(outbox.getId());
      }
    } catch (AmqpException | JsonProcessingException | InterruptedException
             | ExecutionException | TimeoutException ex) {
      if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
      outboxRepository.incrementarIntentos(outbox.getId());
    }
  }
}
