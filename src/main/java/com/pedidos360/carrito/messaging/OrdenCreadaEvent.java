package com.pedidos360.carrito.messaging;

import java.util.List;
import java.util.UUID;

/** Evento que publica ms-carrito al confirmar una compra. Lo consume ms-orders.
 * codigoCupon es opcional (null = compra sin cupón); viaja hasta ms-cupones.
 * emailComprador es OBLIGATORIO: el checkout rechaza con 400 si el JWT no
 * trae email (claim "email", fallback "preferred_username"); ms-notificaciones
 * lo usa como destinatario de la confirmación de compra. */
public record OrdenCreadaEvent(
    UUID ordenId,
    String usuarioId,
    String emailComprador,
    List<Item> items,
    long totalClp,
    String codigoCupon) {

  public record Item(UUID productoId, int cantidad, long precioUnitarioClp) {}
}
