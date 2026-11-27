package com.pedidos360.carrito.repository;

import com.pedidos360.carrito.messaging.OrdenOutbox;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface OrdenOutboxRepository extends JpaRepository<OrdenOutbox, UUID> {

  List<OrdenOutbox> findByEstadoOrderByCreadaEnAsc(String estado, Pageable pageable);

  @Modifying
  @Transactional
  @Query("update OrdenOutbox o set o.estado = 'ENVIADO', o.enviadaEn = :enviadaEn where o.id = :id")
  void marcarEnviado(@Param("id") UUID id, @Param("enviadaEn") Instant enviadaEn);

  @Modifying
  @Transactional
  @Query("update OrdenOutbox o set o.intentos = o.intentos + 1 where o.id = :id")
  void incrementarIntentos(@Param("id") UUID id);
}
