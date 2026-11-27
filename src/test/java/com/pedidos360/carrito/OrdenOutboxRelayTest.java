package com.pedidos360.carrito;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pedidos360.carrito.messaging.OrdenCreadaEvent;
import com.pedidos360.carrito.messaging.OrdenOutbox;
import com.pedidos360.carrito.messaging.OrdenOutboxRelay;
import com.pedidos360.carrito.repository.OrdenOutboxRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.Pageable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Confirmación correlacionada del relay: sin broker, sin DB y sin ConfirmCallback global. */
@ExtendWith(MockitoExtension.class)
class OrdenOutboxRelayTest {

  @Mock RabbitTemplate rabbitTemplate;
  @Mock OrdenOutboxRepository outboxRepository;

  private final ObjectMapper mapper = new ObjectMapper();
  private OrdenOutboxRelay relay;
  private UUID ordenId;
  private String payload;

  @BeforeEach
  void setUp() throws Exception {
    relay = new OrdenOutboxRelay(rabbitTemplate, outboxRepository, mapper);
    ordenId = UUID.randomUUID();
    payload = mapper.writeValueAsString(new OrdenCreadaEvent(ordenId, "oid-123", "comprador@mail.com",
        List.of(new OrdenCreadaEvent.Item(UUID.randomUUID(), 2, 19990L)), 39980L, null));
  }

  private OrdenOutbox pendiente() {
    OrdenOutbox outbox = new OrdenOutbox(ordenId, payload);
    outbox.setEstado("PENDIENTE");
    outbox.setIntentos(0);
    outbox.setCreadaEn(Instant.now());
    return outbox;
  }

  private void confirmar(boolean ack) {
    doAnswer(inv -> {
      CorrelationData correlacion = inv.getArgument(3);
      correlacion.getFuture().complete(new CorrelationData.Confirm(ack, null));
      return null;
    }).when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class), any(CorrelationData.class));
  }

  private void hayPendientes() {
    when(outboxRepository.findByEstadoOrderByCreadaEnAsc(eq("PENDIENTE"), any(Pageable.class)))
        .thenReturn(List.of(pendiente()));
  }

  @Test
  void ackMarcaEnviadoConElMismoOrdenId() throws Exception {
    hayPendientes();
    confirmar(true);

    relay.reenviarPendientes();

    ArgumentCaptor<CorrelationData> correlacion = ArgumentCaptor.forClass(CorrelationData.class);
    verify(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class), correlacion.capture());
    assertEquals(ordenId.toString(), correlacion.getValue().getId());
    ArgumentCaptor<Object> enviado = ArgumentCaptor.forClass(Object.class);
    verify(rabbitTemplate).convertAndSend(anyString(), anyString(), enviado.capture(), any(CorrelationData.class));
    OrdenCreadaEvent evento = mapper.readValue(mapper.writeValueAsBytes(enviado.getValue()), OrdenCreadaEvent.class);
    assertEquals(ordenId, evento.ordenId());
    verify(outboxRepository).marcarEnviado(eq(ordenId), any(Instant.class));
    verify(outboxRepository, never()).incrementarIntentos(any());
  }

  @Test
  void nackDejaPendienteEIncrementaIntentos() {
    hayPendientes();
    confirmar(false);

    relay.reenviarPendientes();

    verify(outboxRepository).incrementarIntentos(ordenId);
    verify(outboxRepository, never()).marcarEnviado(any(), any());
  }

  @Test
  void brokerCaidoDejaPendienteEIncrementaIntentos() {
    hayPendientes();
    doThrow(new AmqpException("broker caido")).when(rabbitTemplate)
        .convertAndSend(anyString(), anyString(), any(Object.class), any(CorrelationData.class));

    relay.reenviarPendientes();

    verify(outboxRepository).incrementarIntentos(ordenId);
    verify(outboxRepository, never()).marcarEnviado(any(), any());
  }

  @Test
  void despuesDelAckNoSeReenvia() {
    AtomicBoolean marcado = new AtomicBoolean(false);
    when(outboxRepository.findByEstadoOrderByCreadaEnAsc(eq("PENDIENTE"), any(Pageable.class)))
        .thenAnswer(inv -> marcado.get() ? List.of() : List.of(pendiente()));
    doAnswer(inv -> {
      marcado.set(true);
      return null;
    }).when(outboxRepository).marcarEnviado(any(), any());
    confirmar(true);

    relay.reenviarPendientes();
    relay.reenviarPendientes();
    relay.reenviarPendientes();

    verify(rabbitTemplate, times(1)).convertAndSend(anyString(), anyString(), any(Object.class), any(CorrelationData.class));
    verify(outboxRepository, times(1)).marcarEnviado(eq(ordenId), any(Instant.class));
  }

  @Test
  void caidaAntesDeMarcarReenviaElMismoOrdenId() throws Exception {
    when(outboxRepository.findByEstadoOrderByCreadaEnAsc(eq("PENDIENTE"), any(Pageable.class)))
        .thenReturn(List.of(pendiente()));
    doThrow(new RuntimeException("fallo al marcar"))
        .doNothing()
        .when(outboxRepository).marcarEnviado(any(), any());
    confirmar(true);

    org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> relay.reenviarPendientes());
    relay.reenviarPendientes();

    ArgumentCaptor<Object> enviados = ArgumentCaptor.forClass(Object.class);
    verify(rabbitTemplate, times(2)).convertAndSend(anyString(), anyString(), enviados.capture(), any(CorrelationData.class));
    assertEquals(2, enviados.getAllValues().size());
    for (Object enviado : enviados.getAllValues()) {
      OrdenCreadaEvent evento = mapper.readValue(mapper.writeValueAsBytes(enviado), OrdenCreadaEvent.class);
      assertEquals(ordenId, evento.ordenId());
      assertEquals(payload, mapper.writeValueAsString(evento));
    }
    verify(outboxRepository, times(2)).marcarEnviado(eq(ordenId), any(Instant.class));
  }
}
