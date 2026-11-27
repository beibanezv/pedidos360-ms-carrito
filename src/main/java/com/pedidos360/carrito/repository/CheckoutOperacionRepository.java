package com.pedidos360.carrito.repository;

import com.pedidos360.carrito.checkout.CheckoutOperacion;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CheckoutOperacionRepository extends JpaRepository<CheckoutOperacion, UUID> {
  Optional<CheckoutOperacion> findByUsuarioIdAndIdempotencyKey(String usuarioId, String idempotencyKey);
}
