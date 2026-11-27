package com.pedidos360.carrito.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.JacksonUtils;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Durabilidad real del outbox M05: broker Kafka de 3 nodos, PostgreSQL efímero y
 * el publisher compilado de este repositorio. Se omite sin las variables que
 * exporta {@code scripts/probar-m05-kafka-caida.ps1}; nunca usa servicios de negocio.
 */
@EnabledIfEnvironmentVariable(named = "P360_M05_BOOTSTRAP", matches = ".+")
@EnabledIfEnvironmentVariable(named = "P360_M05_TOPIC", matches = ".+")
@EnabledIfEnvironmentVariable(named = "P360_M05_COMPOSE_FILE", matches = ".+")
@EnabledIfEnvironmentVariable(named = "P360_TEST_POSTGRES_URL", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "p360.kafka.enabled=true",
        "p360.kafka.relay.auto=false",
        "spring.task.scheduling.enabled=false",
        "logging.level.org.apache.kafka=WARN",
        "logging.level.org.springframework.kafka=WARN"
    })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class M05KafkaCaidaIT {

  private static final String BOOTSTRAP = System.getenv("P360_M05_BOOTSTRAP");
  private static final String TOPIC = System.getenv("P360_M05_TOPIC");
  private static final String COMPOSE_FILE = System.getenv("P360_M05_COMPOSE_FILE");
  private static final String BROKER = "broker3";
  private static final String BOOTSTRAP_INTERNO = "broker1:29092,broker2:29092,broker3:29092";
  private static final Pattern ISR = Pattern.compile("Isr:\\s*([0-9,]+)");

  @Autowired InteraccionOutboxPublisher publisher;
  @Autowired InteraccionOutboxRepository repository;
  @Autowired ApplicationEventPublisher eventos;
  @Autowired JdbcTemplate jdbc;

  /**
   * Bean de Spring al que llama el publisher real. Solo delega en un productor
   * Kafka real; en el caso 4 inyecta el RejectedExecution que produciría un
   * executor saturado. Ningún caso usa un CompletableFuture simulado.
   */
  @MockBean(name = "kafkaTemplate")
  KafkaTemplate<String, InteraccionEvent> kafkaTemplate;

  private DefaultKafkaProducerFactory<String, InteraccionEvent> fabrica;
  private KafkaTemplate<String, InteraccionEvent> plantillaReal;
  private InteraccionEvent eventoCaida;

  @DynamicPropertySource
  static void serviciosAislados(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> System.getenv("P360_TEST_POSTGRES_URL"));
    registry.add("spring.datasource.username", () -> System.getenv("P360_TEST_POSTGRES_USER"));
    registry.add("spring.datasource.password", () -> System.getenv("P360_TEST_POSTGRES_PASSWORD"));
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    registry.add("spring.kafka.bootstrap-servers", () -> BOOTSTRAP);
    registry.add("p360.kafka.topic.interacciones", () -> TOPIC);
  }

  @BeforeEach
  void proyeccionYProductorReal() {
    jdbc.execute("create table if not exists m05_proyeccion_test ("
        + "evento_id uuid primary key, usuario_id varchar(150) not null, "
        + "visto_en timestamptz not null default now())");

    Map<String, Object> propiedades = new HashMap<>();
    propiedades.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
    propiedades.put(ProducerConfig.CLIENT_ID_CONFIG, "m05-it-" + UUID.randomUUID());
    propiedades.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    propiedades.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class.getName());
    propiedades.put(ProducerConfig.ACKS_CONFIG, "all");
    propiedades.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
    propiedades.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 2000);
    propiedades.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 3000);
    propiedades.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10000);
    propiedades.put("spring.json.add.type.headers", false);
    fabrica = new DefaultKafkaProducerFactory<>(propiedades);
    plantillaReal = new KafkaTemplate<>(fabrica);
    doAnswer(inv -> plantillaReal.send(inv.getArgument(0, String.class),
        inv.getArgument(1, String.class), inv.getArgument(2, InteraccionEvent.class)))
        .when(kafkaTemplate).send(anyString(), anyString(), any(InteraccionEvent.class));
  }

  @AfterEach
  void cerrarProductor() {
    if (fabrica != null) fabrica.destroy();
  }

  @AfterAll
  void brokerArribaParaLimpieza() throws Exception {
    dockerCompose("start", BROKER);
    System.out.println("Limpieza prepare: broker3 arriba para borrar el tópico efímero");
  }

  private String dockerCompose(String... argumentos) throws Exception {
    List<String> comando = new ArrayList<>(List.of("docker", "compose", "-f", COMPOSE_FILE));
    comando.addAll(Arrays.asList(argumentos));
    Process proceso = new ProcessBuilder(comando).redirectErrorStream(true).start();
    String salida = new String(proceso.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    int codigo = proceso.waitFor();
    if (codigo != 0) {
      throw new IllegalStateException("docker compose " + String.join(" ", argumentos)
          + " terminó con " + codigo + ": " + salida);
    }
    return salida;
  }

  private boolean esperarIsr(int esperados, Duration limite) throws Exception {
    long fin = System.nanoTime() + limite.toNanos();
    do {
      String descripcion = dockerCompose("exec", "-T", "broker1", "kafka-topics",
          "--bootstrap-server", BOOTSTRAP_INTERNO, "--describe", "--topic", TOPIC);
      long particiones = Arrays.stream(descripcion.split("\\R"))
          .filter(linea -> linea.contains("Partition:"))
          .filter(linea -> {
            Matcher matcher = ISR.matcher(linea);
            return matcher.find() && matcher.group(1).split(",").length == esperados;
          })
          .count();
      if (particiones == 3) return true;
      Thread.sleep(1000);
    } while (System.nanoTime() < fin);
    return false;
  }

  private InteraccionEvent guardar(String usuario, InteraccionEvent.Tipo tipo) {
    InteraccionEvent evento = new InteraccionEvent(UUID.randomUUID(), usuario, tipo,
        UUID.randomUUID(), Instant.now());
    eventos.publishEvent(evento);
    assertNull(repository.findById(evento.eventoId()).orElseThrow().getPublicadoEn(),
        "La fila recién guardada debe nacer pendiente");
    return evento;
  }

  private List<InteraccionEvent> consumir(int max, Duration limite, boolean esperarVacio) {
    Map<String, Object> propiedades = new HashMap<>();
    propiedades.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
    propiedades.put(ConsumerConfig.GROUP_ID_CONFIG, "m05-it-" + UUID.randomUUID());
    propiedades.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    propiedades.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    propiedades.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    propiedades.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    propiedades.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000);
    propiedades.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 10000);
    propiedades.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 10000);

    List<InteraccionEvent> eventos = new ArrayList<>();
    long fin = System.nanoTime() + limite.toNanos();
    try (KafkaConsumer<String, String> consumidor = new KafkaConsumer<>(propiedades)) {
      consumidor.subscribe(List.of(TOPIC));
      while (eventos.size() < max && System.nanoTime() < fin) {
        var registros = consumidor.poll(Duration.ofMillis(500));
        if (registros.isEmpty()) {
          if (!esperarVacio && !eventos.isEmpty()) break;
          continue;
        }
        for (ConsumerRecord<String, String> registro : registros) {
          eventos.add(JacksonUtils.enhancedObjectMapper().readValue(registro.value(), InteraccionEvent.class));
          if (eventos.size() >= max) break;
        }
      }
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("No se pudo leer el evento publicado", error);
    }
    return eventos;
  }

  private void proyectar(InteraccionEvent evento) {
    jdbc.update("insert into m05_proyeccion_test (evento_id, usuario_id) values (?, ?) "
        + "on conflict (evento_id) do nothing", evento.eventoId(), evento.usuarioId());
  }

  private int vecesEnProyeccion(UUID id) {
    return jdbc.queryForObject("select count(*) from m05_proyeccion_test where evento_id = ?",
        Integer.class, id);
  }

  @Test
  @Order(1)
  void caso1_brokerCaidoDejaFilaPendienteYNoPublica() throws Exception {
    dockerCompose("stop", BROKER);
    assertTrue(esperarIsr(2, Duration.ofSeconds(90)), "ISR no bajó a 2 al caer broker3");

    eventoCaida = guardar("usuario-m05-caida", InteraccionEvent.Tipo.AGREGAR_CARRITO);
    publisher.enviarPendientes();

    assertNull(repository.findById(eventoCaida.eventoId()).orElseThrow().getPublicadoEn(),
        "Sin ACK no debe marcarse publicadoEn");
    List<InteraccionEvent> publicados = consumir(1, Duration.ofSeconds(12), true);
    assertTrue(publicados.isEmpty(), "No debió publicarse nada con el broker caído: " + publicados);
    System.out.println("CASO 1 OK - broker3 caído (ISR 2): fila publicadoEn=null, 0 eventos en el tópico");
  }

  @Test
  @Order(2)
  void caso2_brokerRestauradoPublicaMismoIdYMarca() throws Exception {
    dockerCompose("start", BROKER);
    assertTrue(esperarIsr(3, Duration.ofSeconds(120)), "ISR no volvió a 3 al restaurar broker3");

    publisher.enviarPendientes();

    assertNotNull(repository.findById(eventoCaida.eventoId()).orElseThrow().getPublicadoEn(),
        "Tras el ACK debe marcarse publicadoEn");
    List<InteraccionEvent> recibidos = consumir(1, Duration.ofSeconds(30), true);
    assertEquals(1, recibidos.size(), "Debe publicarse un solo evento pendiente");
    assertEquals(eventoCaida.eventoId(), recibidos.get(0).eventoId(), "El eventoId no debe cambiar");
    recibidos.forEach(this::proyectar);
    assertEquals(1, vecesEnProyeccion(eventoCaida.eventoId()));
    System.out.println("CASO 2 OK - broker3 restaurado (ISR 3): mismo eventoId, publicadoEn marcado, proyección sin duplicado");
  }

  @Test
  @Order(3)
  void caso3_ackSinMarkReenviaMismoIdSinDuplicar() throws Exception {
    // Estado exacto tras publicar y perder la marca: mensaje en Kafka + fila pendiente.
    jdbc.update("update interacciones_outbox set publicado_en = null where evento_id = ?",
        eventoCaida.eventoId());
    assertNull(repository.findById(eventoCaida.eventoId()).orElseThrow().getPublicadoEn());

    publisher.enviarPendientes();

    assertNotNull(repository.findById(eventoCaida.eventoId()).orElseThrow().getPublicadoEn());
    List<InteraccionEvent> recibidos = consumir(10, Duration.ofSeconds(20), false);
    long coincidencias = recibidos.stream()
        .filter(evento -> evento.eventoId().equals(eventoCaida.eventoId())).count();
    assertEquals(2, coincidencias, "El reenvío debe usar el mismo eventoId");
    recibidos.stream().filter(evento -> evento.eventoId().equals(eventoCaida.eventoId()))
        .forEach(this::proyectar);
    assertEquals(1, vecesEnProyeccion(eventoCaida.eventoId()), "La proyección deduplica por eventoId");
    System.out.println("CASO 3 OK - ACK sin mark: reenvío con mismo eventoId, 2 mensajes, 1 fila en proyección");
  }

  @Test
  @Order(4)
  void caso4_rejectedExecutionNoPierdeLaFila() throws Exception {
    InteraccionEvent evento = guardar("usuario-m05-lleno", InteraccionEvent.Tipo.CUPON_INGRESADO);
    AtomicBoolean brokerLleno = new AtomicBoolean(true);
    doAnswer(invocation -> {
      if (brokerLleno.get()) throw new RejectedExecutionException("broker lleno simulado");
      return plantillaReal.send(invocation.getArgument(0, String.class),
          invocation.getArgument(1, String.class), invocation.getArgument(2, InteraccionEvent.class));
    }).when(kafkaTemplate).send(anyString(), anyString(), any(InteraccionEvent.class));

    publisher.enviarPendientes();

    assertNull(repository.findById(evento.eventoId()).orElseThrow().getPublicadoEn(),
        "Con RejectedExecution la fila debe sobrevivir pendiente");
    List<InteraccionEvent> sinPublicar = consumir(10, Duration.ofSeconds(15), false);
    assertTrue(sinPublicar.stream().noneMatch(item -> item.eventoId().equals(evento.eventoId())),
        "No debió publicarse con el broker lleno");

    brokerLleno.set(false);
    publisher.enviarPendientes();

    assertNotNull(repository.findById(evento.eventoId()).orElseThrow().getPublicadoEn());
    List<InteraccionEvent> recuperados = consumir(10, Duration.ofSeconds(20), false);
    List<InteraccionEvent> delCaso = recuperados.stream()
        .filter(item -> item.eventoId().equals(evento.eventoId())).toList();
    assertEquals(1, delCaso.size(), "Tras recuperarse debe publicarse una sola vez");
    delCaso.forEach(this::proyectar);
    assertEquals(1, vecesEnProyeccion(evento.eventoId()));
    assertTrue(repository.count() >= 2, "Ninguna fila del outbox debe perderse");
    System.out.println("CASO 4 OK - RejectedExecution: fila conservada, reenvío posterior con mismo eventoId y sin duplicado");
  }
}
