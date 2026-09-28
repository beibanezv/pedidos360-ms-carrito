package com.pedidos360.carrito.messaging;

import java.util.List;
import java.util.UUID;

/** Evento que publica ms-carrito al confirmar una compra. Lo consume ms-orders.
 * codigoCupon es opcional (null = compra sin cupón); viaja hasta ms-cupones. */
public record OrdenCreadaEvent(
    UUID ordenId,
    String usuarioId,
    List<Item> items,
    long totalClp,
    String codigoCupon) {

  public record Item(UUID productoId, int cantidad, long precioUnitarioClp) {}
}
