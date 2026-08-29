package com.pedidos360.carrito.repository;

import com.pedidos360.carrito.model.Carrito;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CarritoRepository extends JpaRepository<Carrito, UUID> {
  Optional<Carrito> findByUsuarioId(String usuarioId);
}
