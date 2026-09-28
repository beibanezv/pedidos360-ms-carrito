package com.pedidos360.carrito.messaging;

import com.pedidos360.carrito.config.RabbitMQConfig;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Productor del caso de uso "crear orden". El controlador no conoce
 * detalles de RabbitMQ: solo llama a publicarOrdenCreada (rúbrica
 * EP3/EP4 indicador 3, config separada de la lógica de negocio).
 */
@Component
public class OrdenEventPublisher {

  private final RabbitTemplate rabbitTemplate;

  public OrdenEventPublisher(RabbitTemplate rabbitTemplate) {
    this.rabbitTemplate = rabbitTemplate;
  }

  public void publicarOrdenCreada(OrdenCreadaEvent evento) {
    try {
      rabbitTemplate.convertAndSend(
          RabbitMQConfig.ORDENES_EXCHANGE, RabbitMQConfig.ORDENES_ROUTING_KEY, evento);
    } catch (AmqpException e) {
      // Si el evento no sale, el checkout falla con 503 y el carrito NO se
      // vacía: el cliente puede reintentar sin perder su compra.
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Broker no disponible", e);
    }
  }
}
