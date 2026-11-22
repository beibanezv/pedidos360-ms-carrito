package com.pedidos360.carrito.controller;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

@Component
public class CatalogoPrecios {
  private final RestClient catalogo;

  CatalogoPrecios(@Value("${p360.productos.url}") String url) {
    var timeouts = new SimpleClientHttpRequestFactory();
    timeouts.setConnectTimeout(2000);
    timeouts.setReadTimeout(3000);
    catalogo = RestClient.builder().baseUrl(url).requestFactory(timeouts).build();
  }

  public long precio(UUID id) {
    try {
      var producto = catalogo.get().uri("/productos/{id}", id).retrieve().body(Producto.class);
      if (producto == null || !id.equals(producto.id()) || producto.precioClp() == null || producto.precioClp() <= 0) {
        throw noDisponible();
      }
      return producto.precioClp();
    } catch (RestClientResponseException ex) {
      if (ex.getStatusCode() == HttpStatus.NOT_FOUND) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Un producto ya no está disponible");
      }
      throw noDisponible();
    } catch (RestClientException ex) {
      throw noDisponible();
    }
  }

  private ResponseStatusException noDisponible() {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "No pudimos consultar los precios");
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Producto(UUID id, Long precioClp) {}
}
