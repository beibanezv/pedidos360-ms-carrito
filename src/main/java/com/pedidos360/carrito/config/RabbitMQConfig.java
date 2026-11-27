package com.pedidos360.carrito.config;

import com.fasterxml.jackson.databind.JavaType;
import com.pedidos360.carrito.messaging.OrdenCreadaEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topología RabbitMQ que usa ms-carrito (lado productor).
 * Nombres centralizados aquí (rúbrica EP3/EP4 indicador 1): ningún otro
 * archivo del servicio repite estos literales.
 *
 * Ruta documentada:
 *   ms-carrito --direct--> p360.ordenes.exchange / orden.creada
 *                            └─> p360.ordenes.queue (la consume ms-orders)
 *   Si el mensaje muere (rechazo/NACK sin requeue) va a p360.dlx
 *   con routing key = nombre de la cola origen, y de ahí a su DLQ.
 */
@Configuration
public class RabbitMQConfig {

  // Exchange + cola + routing key del caso de uso "crear orden".
  public static final String ORDENES_EXCHANGE = "p360.ordenes.exchange";
  public static final String ORDENES_QUEUE = "p360.ordenes.queue";
  public static final String ORDENES_ROUTING_KEY = "orden.creada";

  // Dead-letter exchange compartido (direct: cada DLQ se enlaza con su propia key).
  public static final String DLX_EXCHANGE = "p360.dlx";
  public static final String ORDENES_DLQ = "p360.ordenes.dlq";

  @Bean
  DirectExchange ordenesExchange() {
    return new DirectExchange(ORDENES_EXCHANGE, true, false);
  }

  @Bean
  Queue ordenesQueue() {
    return QueueBuilder.durable(ORDENES_QUEUE)
        .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
        // Los mensajes muertos se re-enrutan con el nombre de la cola origen.
        .withArgument("x-dead-letter-routing-key", ORDENES_QUEUE)
        .build();
  }

  @Bean
  Binding ordenesBinding(Queue ordenesQueue, DirectExchange ordenesExchange) {
    return new Binding(ORDENES_QUEUE, Binding.DestinationType.QUEUE,
        ORDENES_EXCHANGE, ORDENES_ROUTING_KEY, null);
  }

  @Bean
  DirectExchange deadLetterExchange() {
    return new DirectExchange(DLX_EXCHANGE, true, false);
  }

  @Bean
  Queue ordenesDlq() {
    return QueueBuilder.durable(ORDENES_DLQ).build();
  }

  @Bean
  Binding ordenesDlqBinding(Queue ordenesDlq, DirectExchange deadLetterExchange) {
    return new Binding(ORDENES_DLQ, Binding.DestinationType.QUEUE,
        DLX_EXCHANGE, ORDENES_QUEUE, null);
  }

  /**
   * Mapeo explicito de tipos: el sobre lleva el nombre simple del DTO, nunca un
   * paquete de otro servicio. Los FQCN historicos se aceptan como alias hacia
   * la clase local, sin setTrustedPackages("*").
   *
   * El orden importa: DefaultJackson2JavaTypeMapper invierte el mapa y el ultimo
   * id escrito para una misma clase es el que se publica en __TypeId__. Por eso
   * los alias van primero y el nombre simple al final.
   */
  public static DefaultJackson2JavaTypeMapper jsonTypeMapper() {
    Map<String, Class<?>> ids = new LinkedHashMap<>();
    ids.put("com.pedidos360.orders.messaging.OrdenCreadaEvent", OrdenCreadaEvent.class);
    ids.put("OrdenCreadaEvent", OrdenCreadaEvent.class);

    // El mapper invierte el mapa en un HashMap: si solo dependieramos del orden
    // de insercion, un alias FQCN podria ganar al publicar. Fijamos el id simple.
    DefaultJackson2JavaTypeMapper mapper = new DefaultJackson2JavaTypeMapper() {
      @Override
      public void fromJavaType(JavaType javaType, MessageProperties properties) {
        super.fromJavaType(javaType, properties);
        if (javaType.getRawClass() == OrdenCreadaEvent.class) {
          properties.setHeader(DEFAULT_CLASSID_FIELD_NAME, "OrdenCreadaEvent");
        }
      }
    };
    mapper.setIdClassMapping(ids);
    mapper.setTypePrecedence(Jackson2JavaTypeMapper.TypePrecedence.TYPE_ID);
    return mapper;
  }

  @Bean
  MessageConverter jsonMessageConverter() {
    Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter();
    converter.setJavaTypeMapper(jsonTypeMapper());
    return converter;
  }
}
