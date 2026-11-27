package com.pedidos360.carrito.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.pedidos360.carrito.messaging.OrdenCreadaEvent;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.MessageConverter;

/** M05b: el contrato viaja por nombre simple y el mapper es explicito. */
class RabbitMQConfigDtoTest {

  private static final String TYPE_ID = DefaultJackson2JavaTypeMapper.DEFAULT_CLASSID_FIELD_NAME;
  private final MessageConverter converter = new RabbitMQConfig().jsonMessageConverter();

  @Test
  void mapperUsaTypeIdYNoComodines() {
    DefaultJackson2JavaTypeMapper mapper = RabbitMQConfig.jsonTypeMapper();

    assertThat(mapper.getTypePrecedence()).isEqualTo(Jackson2JavaTypeMapper.TypePrecedence.TYPE_ID);
    assertThat(mapper.getIdClassMapping())
        .containsEntry("OrdenCreadaEvent", OrdenCreadaEvent.class)
        .containsEntry("com.pedidos360.orders.messaging.OrdenCreadaEvent", OrdenCreadaEvent.class)
        .doesNotContainKey("*");
  }

  @Test
  void producirYConsumirConservaTipoYCampos() {
    OrdenCreadaEvent evento = evento();

    Message mensaje = converter.toMessage(evento, new MessageProperties());

    assertThat((Object) mensaje.getMessageProperties().getHeader(TYPE_ID)).isEqualTo("OrdenCreadaEvent");
    Object resultado = converter.fromMessage(mensaje);
    assertThat(resultado)
        .isExactlyInstanceOf(OrdenCreadaEvent.class)
        .isEqualTo(evento);
  }

  @Test
  void aliasFqcnHistoricoResuelveLaClaseLocal() {
    OrdenCreadaEvent evento = evento();
    byte[] cuerpo = converter.toMessage(evento, new MessageProperties()).getBody();
    MessageProperties props = new MessageProperties();
    props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    props.setHeader(TYPE_ID, "com.pedidos360.orders.messaging.OrdenCreadaEvent");

    Object resultado = converter.fromMessage(new Message(cuerpo, props));
    assertThat(resultado)
        .isExactlyInstanceOf(OrdenCreadaEvent.class)
        .isEqualTo(evento);
  }

  private static OrdenCreadaEvent evento() {
    return new OrdenCreadaEvent(
        UUID.fromString("11111111-1111-1111-1111-111111111111"),
        "u-1", "comprador@example.cl",
        List.of(new OrdenCreadaEvent.Item(
            UUID.fromString("22222222-2222-2222-2222-222222222222"), 2, 1990L)),
        3980L, "CUPON10");
  }
}
