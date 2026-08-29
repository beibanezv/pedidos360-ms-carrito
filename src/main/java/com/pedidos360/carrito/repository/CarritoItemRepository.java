package com.pedidos360.carrito.repository;

import com.pedidos360.carrito.model.CarritoItem;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CarritoItemRepository extends JpaRepository<CarritoItem, UUID> {
  List<CarritoItem> findByCarritoId(UUID carritoId);
  Optional<CarritoItem> findByIdAndCarritoId(UUID id, UUID carritoId);
}
