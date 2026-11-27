package com.pedidos360.carrito.messaging;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

interface InteraccionOutboxRepository extends JpaRepository<InteraccionOutbox, UUID> {
  List<InteraccionOutbox> findTop20ByPublicadoEnIsNullOrderByOcurrioEnAscEventoIdAsc();

  @Modifying @Transactional
  @Query("update InteraccionOutbox o set o.publicadoEn = :cuando "
      + "where o.eventoId = :id and o.publicadoEn is null")
  void marcarPublicado(UUID id, Instant cuando);
}
