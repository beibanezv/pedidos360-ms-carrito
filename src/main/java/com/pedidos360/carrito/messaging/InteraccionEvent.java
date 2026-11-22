package com.pedidos360.carrito.messaging;

import java.time.Instant;
import java.util.UUID;

public record InteraccionEvent(UUID eventoId, String usuarioId, Tipo tipo, UUID productoId, Instant ocurrioEn) {
  public enum Tipo { AGREGAR_CARRITO, CUPON_INGRESADO, ENVIO_CONFIRMADO }
}
