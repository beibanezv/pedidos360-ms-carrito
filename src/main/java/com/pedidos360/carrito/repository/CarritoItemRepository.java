package com.pedidos360.carrito.repository;

import com.pedidos360.carrito.model.CarritoItem;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CarritoItemRepository extends JpaRepository<CarritoItem, UUID> {
  List<CarritoItem> findByUserId(String userId);
  Optional<CarritoItem> findByIdAndUserId(UUID id, String userId);
}
