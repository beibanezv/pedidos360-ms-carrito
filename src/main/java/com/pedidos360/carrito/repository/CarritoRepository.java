package com.pedidos360.carrito.repository;

import com.pedidos360.carrito.model.Carrito;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CarritoRepository extends JpaRepository<Carrito, UUID> {
  Optional<Carrito> findByUsuarioId(String usuarioId);

  /** SELECT ... FOR UPDATE: serializa los checkouts de un mismo usuario. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from Carrito c where c.usuarioId = :usuarioId")
  Optional<Carrito> findByUsuarioIdForUpdate(@Param("usuarioId") String usuarioId);
}
