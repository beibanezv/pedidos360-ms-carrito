package com.pedidos360.carrito.checkout;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pedidos360.carrito.controller.CatalogoPrecios;
import com.pedidos360.carrito.messaging.OrdenCreadaEvent;
import com.pedidos360.carrito.messaging.OrdenOutbox;
import com.pedidos360.carrito.model.Carrito;
import com.pedidos360.carrito.model.CarritoItem;
import com.pedidos360.carrito.repository.CarritoItemRepository;
import com.pedidos360.carrito.repository.CarritoRepository;
import com.pedidos360.carrito.repository.CheckoutOperacionRepository;
import com.pedidos360.carrito.repository.OrdenOutboxRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

/**
 * Checkout idempotente: operación, snapshot, respuesta y salida orden.creada se escriben
 * en una sola transacción. Sin publicar Rabbit aquí: lo hace OrdenOutboxRelay después.
 */
@Service
public class CheckoutService {

  private static final Pattern UUID_RE = Pattern.compile(
      "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

  private final CarritoRepository carritoRepository;
  private final CarritoItemRepository itemRepository;
  private final CheckoutOperacionRepository operacionRepository;
  private final OrdenOutboxRepository outboxRepository;
  private final CatalogoPrecios catalogoPrecios;
  private final ObjectMapper mapper;

  public CheckoutService(CarritoRepository carritoRepository, CarritoItemRepository itemRepository,
      CheckoutOperacionRepository operacionRepository, OrdenOutboxRepository outboxRepository,
      CatalogoPrecios catalogoPrecios, ObjectMapper mapper) {
    this.carritoRepository = carritoRepository;
    this.itemRepository = itemRepository;
    this.operacionRepository = operacionRepository;
    this.outboxRepository = outboxRepository;
    this.catalogoPrecios = catalogoPrecios;
    this.mapper = mapper;
  }

  /** nueva=false significa reintento: el controlador no debe emitir eventos de aplicación. */
  public record Result(Map<String, Object> respuesta, boolean nueva) {}

  public static void validarClave(String clave) {
    if (clave != null && !UUID_RE.matcher(clave).matches()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key debe ser un UUID");
    }
  }

  @Transactional
  public Result checkout(String usuarioId, String email, String codigoCupon, String idempotencyKey) {
    validarClave(idempotencyKey);
    String clave = idempotencyKey == null ? null : idempotencyKey.toLowerCase();
    String emailComprador = email == null ? null : email.trim();
    String cupon = codigoCupon == null || codigoCupon.isBlank() ? null : codigoCupon.trim();

    // Orden de locks fijo: primero el carrito (serializa también claves distintas),
    // después la fila de operación. Si la clave falta, el servidor genera una pero
    // ese request no es recuperable por el cliente.
    Carrito carrito = carritoRepository.findByUsuarioIdForUpdate(usuarioId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Carrito vacío"));

    if (clave != null) {
      Optional<CheckoutOperacion> previa = operacionRepository.findByUsuarioIdAndIdempotencyKey(usuarioId, clave);
      if (previa.isPresent()) return replay(previa.get(), emailComprador, cupon);
    }

    List<CarritoItem> items = itemRepository.findByCarritoId(carrito.getId());
    if (items.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Carrito vacío");

    Map<UUID, Integer> cantidades = new HashMap<>();
    for (CarritoItem item : items) {
      if (item.getCantidad() == null || item.getCantidad() <= 0) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cantidad inválida");
      }
      try {
        cantidades.merge(item.getProductoId(), item.getCantidad(), Math::addExact);
      } catch (ArithmeticException ex) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cantidad fuera de rango");
      }
    }
    Map<UUID, Long> precios = new HashMap<>();
    cantidades.forEach((productoId, cantidad) ->
        precios.put(productoId, validarCantidad(productoId, cantidad).precioClp()));

    List<OrdenCreadaEvent.Item> itemsEvento = new ArrayList<>();
    long total = 0;
    try {
      for (CarritoItem item : items) {
        long precio = precios.get(item.getProductoId());
        itemsEvento.add(new OrdenCreadaEvent.Item(item.getProductoId(), item.getCantidad(), precio));
        total = Math.addExact(total, Math.multiplyExact(precio, item.getCantidad()));
      }
    } catch (ArithmeticException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Total de carrito fuera de rango");
    }

    String snapshotJson = serializar(snapshot(carrito.getId(), itemsEvento, total, emailComprador, cupon));
    String hash = sha256(snapshotJson);
    UUID ordenId = UUID.randomUUID();
    Map<String, Object> respuesta = respuesta(ordenId, carrito.getId(), usuarioId, emailComprador,
        items.size(), total, cupon);
    String respuestaJson = serializar(respuesta);
    String payloadJson = serializar(new OrdenCreadaEvent(
        ordenId, usuarioId, emailComprador, itemsEvento, total, cupon));

    boolean enTransaccion = TransactionSynchronizationManager.isActualTransactionActive();
    Object savepoint = enTransaccion ? TransactionAspectSupport.currentTransactionStatus().createSavepoint() : null;
    try {
      CheckoutOperacion operacion = new CheckoutOperacion();
      operacion.setId(UUID.randomUUID());
      operacion.setUsuarioId(usuarioId);
      operacion.setIdempotencyKey(clave == null ? UUID.randomUUID().toString() : clave);
      operacion.setCarritoId(carrito.getId());
      operacion.setSnapshotJson(snapshotJson);
      operacion.setSnapshotHash(hash);
      operacion.setRespuestaJson(respuestaJson);
      operacionRepository.save(operacion);
      outboxRepository.save(new OrdenOutbox(ordenId, payloadJson));
    } catch (DataIntegrityViolationException ex) {
      // Carrera con la misma clave: el lock del carrito la hace improbable, pero si
      // ocurre se relee la operación ganadora en vez de propagar un 500.
      if (enTransaccion) TransactionAspectSupport.currentTransactionStatus().rollbackToSavepoint(savepoint);
      if (clave == null) throw ex;
      Optional<CheckoutOperacion> ganadora = operacionRepository.findByUsuarioIdAndIdempotencyKey(usuarioId, clave);
      if (ganadora.isEmpty()) throw ex;
      return replay(ganadora.get(), emailComprador, cupon);
    }

    // Solo los IDs capturados: nunca deleteAll del carrito actual (puede tener items nuevos).
    itemRepository.deleteAllById(items.stream().map(CarritoItem::getId).toList());
    return new Result(respuesta, true);
  }

  private CatalogoPrecios.Producto validarCantidad(UUID productoId, int cantidad) {
    if (cantidad <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cantidad inválida");
    CatalogoPrecios.Producto producto = catalogoPrecios.consultar(productoId);
    if (cantidad > producto.stock()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Stock insuficiente");
    }
    return producto;
  }

  private Result replay(CheckoutOperacion previa, String email, String cupon) {
    if (!previa.getSnapshotHash().equals(hashConSolicitud(previa.getSnapshotJson(), email, cupon))) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "La clave ya se usó para otro checkout");
    }
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> respuesta = mapper.readValue(previa.getRespuestaJson(), LinkedHashMap.class);
      return new Result(respuesta, false);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Respuesta guardada ilegible", ex);
    }
  }

  /** S = carrito + ítems del snapshot + total + email/cupón del request; sin ordenId ni telemetría. */
  private Map<String, Object> snapshot(UUID carritoId, List<OrdenCreadaEvent.Item> items,
      long total, String email, String cupon) {
    List<Map<String, Object>> snapshotItems = items.stream().map(item -> {
      Map<String, Object> fila = new LinkedHashMap<>();
      fila.put("productoId", item.productoId().toString());
      fila.put("cantidad", item.cantidad());
      fila.put("precioUnitarioClp", item.precioUnitarioClp());
      return fila;
    }).toList();
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("carritoId", carritoId.toString());
    snapshot.put("items", snapshotItems);
    snapshot.put("totalClp", total);
    snapshot.put("emailComprador", email);
    snapshot.put("codigoCupon", cupon);
    return snapshot;
  }

  private String hashConSolicitud(String snapshotJson, String email, String cupon) {
    try {
      JsonNode arbol = mapper.readTree(snapshotJson);
      ObjectNode objeto = (ObjectNode) arbol;
      objeto.put("emailComprador", email);
      if (cupon == null) objeto.putNull("codigoCupon"); else objeto.put("codigoCupon", cupon);
      return sha256(objeto.toString());
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Snapshot guardado ilegible", ex);
    }
  }

  private static String sha256(String valor) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(valor.getBytes(StandardCharsets.UTF_8));
      StringBuilder hexadecimal = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        hexadecimal.append(Character.forDigit((b >> 4) & 0xf, 16));
        hexadecimal.append(Character.forDigit(b & 0xf, 16));
      }
      return hexadecimal.toString();
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private Map<String, Object> respuesta(UUID ordenId, UUID carritoId, String usuarioId,
      String email, int itemsComprados, long total, String cupon) {
    Map<String, Object> respuesta = new LinkedHashMap<>();
    respuesta.put("ordenId", ordenId);
    respuesta.put("carritoId", carritoId);
    respuesta.put("usuarioId", usuarioId);
    respuesta.put("emailComprador", email);
    respuesta.put("itemsComprados", itemsComprados);
    respuesta.put("totalClp", total);
    respuesta.put("codigoCupon", cupon);
    respuesta.put("estado", "checkout_ok");
    return respuesta;
  }

  private String serializar(Object valor) {
    try {
      return mapper.writeValueAsString(valor);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("No se pudo serializar el checkout", ex);
    }
  }
}
