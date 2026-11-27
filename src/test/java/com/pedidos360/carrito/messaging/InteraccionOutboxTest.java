package com.pedidos360.carrito.messaging;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

class InteraccionOutboxTest {
  private final InteraccionOutboxRepository repository = mock(InteraccionOutboxRepository.class);
  @SuppressWarnings("unchecked") private final KafkaTemplate<String, InteraccionEvent> kafka = mock(KafkaTemplate.class);
  private final Clock clock = Clock.fixed(Instant.parse("2026-11-26T12:00:00Z"), Clock.systemUTC().getZone());
  private final InteraccionOutboxPublisher publisher = new InteraccionOutboxPublisher(repository, kafka, clock);

  private InteraccionOutbox pendiente(UUID id) {
    return new InteraccionOutbox(new InteraccionEvent(id, "cliente", InteraccionEvent.Tipo.AGREGAR_CARRITO,
        UUID.randomUUID(), clock.instant()));
  }

  @Test
  void noMarcaPublicadoSiKafkaNoConfirma() {
    UUID id = UUID.randomUUID();
    when(repository.findTop20ByPublicadoEnIsNullOrderByOcurrioEnAscEventoIdAsc()).thenReturn(List.of(pendiente(id)));
    when(kafka.send(eq("p360.interacciones.v1"), eq("cliente"), any(InteraccionEvent.class)))
        .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker caído")));

    publisher.enviarPendientes();

    verify(repository, never()).marcarPublicado(any(), any());
  }

  @Test
  void marcaPublicadoDespuesDelAck() {
    UUID id = UUID.randomUUID();
    when(repository.findTop20ByPublicadoEnIsNullOrderByOcurrioEnAscEventoIdAsc()).thenReturn(List.of(pendiente(id)));
    when(kafka.send(eq("p360.interacciones.v1"), eq("cliente"), any(InteraccionEvent.class)))
        .thenReturn(CompletableFuture.completedFuture(null));

    publisher.enviarPendientes();

    verify(repository).marcarPublicado(eq(id), any(Instant.class));
  }

  @Test
  void elEventListenerGuardaLaFila() {
    var evento = new InteraccionEvent(UUID.randomUUID(), "cliente", InteraccionEvent.Tipo.AGREGAR_CARRITO,
        UUID.randomUUID(), clock.instant());
    new InteraccionKafkaPublisher(repository).publicar(evento);

    verify(repository).save(any(InteraccionOutbox.class));
  }
}
