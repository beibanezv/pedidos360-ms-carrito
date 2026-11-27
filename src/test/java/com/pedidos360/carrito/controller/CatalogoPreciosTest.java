package com.pedidos360.carrito.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class CatalogoPreciosTest {

  private HttpServer server;

  @AfterEach
  void detenerServidor() {
    if (server != null) server.stop(0);
  }

  private CatalogoPrecios cliente(AtomicReference<String> cuerpo, int status, long retardoMs) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/productos", exchange -> {
      try {
        if (retardoMs > 0) {
          try {
            Thread.sleep(retardoMs);
          } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
          }
        }
        byte[] bytes = cuerpo.get().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
      } finally {
        exchange.close();
      }
    });
    server.start();
    return new CatalogoPrecios("http://127.0.0.1:" + server.getAddress().getPort());
  }

  private static String productoJson(UUID id, long precio, int stock) {
    return "{\"id\":\"" + id + "\",\"precioClp\":" + precio + ",\"stock\":" + stock
        + ",\"nombre\":\"Vinilo de prueba\"}";
  }

  @Test
  void consultaPrecioYStockDelProducto() throws Exception {
    UUID id = UUID.randomUUID();
    CatalogoPrecios catalogo = cliente(new AtomicReference<>(productoJson(id, 19990L, 3)), 200, 0);

    CatalogoPrecios.Producto producto = catalogo.consultar(id);

    assertEquals(id, producto.id());
    assertEquals(19990L, producto.precioClp());
    assertEquals(3, producto.stock());
  }

  @Test
  void productoBorradoDevuelve409() throws Exception {
    UUID id = UUID.randomUUID();
    CatalogoPrecios catalogo = cliente(new AtomicReference<>(productoJson(id, 19990L, 3)), 404, 0);

    ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> catalogo.consultar(id));

    assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
  }

  @Test
  void respuestaInvalidaDevuelve503() throws Exception {
    UUID id = UUID.randomUUID();
    AtomicReference<String> cuerpo = new AtomicReference<>();
    CatalogoPrecios catalogo = cliente(cuerpo, 200, 0);
    List<String> respuestas = List.of(
        productoJson(UUID.randomUUID(), 19990L, 3),
        "{\"id\":\"" + id + "\",\"precioClp\":null,\"stock\":3}",
        productoJson(id, 0L, 3),
        productoJson(id, -1L, 3),
        "{\"id\":\"" + id + "\",\"precioClp\":19990,\"stock\":null}",
        productoJson(id, 19990L, -1),
        "{\"id\":\"" + id + "\",\"precioClp\":");

    for (String respuesta : respuestas) {
      cuerpo.set(respuesta);
      ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> catalogo.consultar(id));
      assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatusCode());
    }
  }

  @Test
  void falloServidorDevuelve503() throws Exception {
    UUID id = UUID.randomUUID();
    CatalogoPrecios catalogo = cliente(new AtomicReference<>(productoJson(id, 19990L, 3)), 500, 0);

    ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> catalogo.consultar(id));

    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatusCode());
  }

  @Test
  void timeoutDevuelve503() throws Exception {
    UUID id = UUID.randomUUID();
    CatalogoPrecios catalogo = cliente(new AtomicReference<>(productoJson(id, 19990L, 3)), 200, 3300);

    ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> catalogo.consultar(id));

    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatusCode());
  }
}
