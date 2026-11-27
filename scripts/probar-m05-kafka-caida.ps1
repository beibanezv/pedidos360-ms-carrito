<#
  Durabilidad del outbox M05 con un broker Kafka REAL caído.

  Servicios 100% temporales y aislados: clúster de 3 ZooKeeper + 3 brokers
  inspirado en ms-admin/docker/kafka-cluster/compose.yml, más un PostgreSQL
  desechable. Nombres de proyecto/contenedor/volumen y puertos únicos por
  ejecución; la base es p360_test_* y el único tópico creado es efímero.
  No se usan AWS/Azure, credenciales de nube ni tópicos de negocio.

  Uso:
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts\probar-m05-kafka-caida.ps1
  -Depurar conserva el directorio temporal (compose y log) si algo falla.
#>

param([switch]$Depurar)

[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$ErrorActionPreference = 'Stop'
if (Get-Variable -Name PSNativeCommandUseErrorActionPreference -ErrorAction SilentlyContinue) {
  $PSNativeCommandUseErrorActionPreference = $false
}

$repo = Split-Path -Parent $PSScriptRoot
$sufijo = 'm05' + (New-Guid).ToString('N').Substring(0, 12)
$proyecto = "p360-$sufijo"
$prefijo = $proyecto
$prefijoVolumen = ($proyecto -replace '-', '_') + '_'
$topico = "p360.m05.test.$sufijo"
$baseDatos = "p360_test_$sufijo"
$temporal = Join-Path $env:TEMP "p360-m05-$sufijo"
$archivoCompose = Join-Path $temporal 'compose.yml'
$log = Join-Path $temporal 'mvn-m05.log'
New-Item -ItemType Directory -Path $temporal -Force | Out-Null

function Escribir([string]$texto, [string]$color = 'Cyan') {
  Write-Host $texto -ForegroundColor $color
}

function Puerto-Libre {
  do {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $listener.Start()
    $puerto = $listener.LocalEndpoint.Port
    $listener.Stop()
  } while ($puertos -contains $puerto)
  $script:puertos += $puerto
  return $puerto
}

$script:puertos = @()
$p1 = Puerto-Libre
$p2 = Puerto-Libre
$p3 = Puerto-Libre
$p4 = Puerto-Libre
$bootstrap = "127.0.0.1:$p1,127.0.0.1:$p2,127.0.0.1:$p3"
$bootstrapInterno = 'broker1:29092,broker2:29092,broker3:29092'

# Plantilla literal: los $$ de los scripts del contenedor no los expande PowerShell.
$plantilla = @'
name: __PROJECT__

x-zookeeper: &zookeeper
  image: confluentinc/cp-kafka:7.6.0
  restart: unless-stopped
  environment: &zk-env
    KAFKA_HEAP_OPTS: -Xms128m -Xmx192m
    JAVA_TOOL_OPTIONS: -XX:ActiveProcessorCount=2
  entrypoint: ["/bin/bash", "-ec"]
  command:
    - |
      mkdir -p /var/lib/zookeeper/data /var/lib/zookeeper/log
      printf '%s\n' "$$ZOOKEEPER_SERVER_ID" > /var/lib/zookeeper/data/myid
      cat > /tmp/zoo.properties <<'EOF'
      tickTime=2000
      initLimit=10
      syncLimit=5
      dataDir=/var/lib/zookeeper/data
      dataLogDir=/var/lib/zookeeper/log
      clientPort=2181
      server.1=__PREFIX__-zk1:2888:3888
      server.2=__PREFIX__-zk2:2888:3888
      server.3=__PREFIX__-zk3:2888:3888
      EOF
      exec zookeeper-server-start /tmp/zoo.properties

x-kafka: &kafka
  image: confluentinc/cp-kafka:7.6.0
  restart: unless-stopped
  depends_on: [zk1, zk2, zk3]
  environment: &kafka-env
    KAFKA_ZOOKEEPER_CONNECT: zk1:2181,zk2:2181,zk3:2181
    KAFKA_ZOOKEEPER_SESSION_TIMEOUT_MS: 30000
    KAFKA_ZOOKEEPER_CONNECTION_TIMEOUT_MS: 30000
    JAVA_TOOL_OPTIONS: -XX:ActiveProcessorCount=2
    KAFKA_LISTENERS: INTERNAL://0.0.0.0:29092,HOST://0.0.0.0:9092
    KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: INTERNAL:PLAINTEXT,HOST:PLAINTEXT
    KAFKA_INTER_BROKER_LISTENER_NAME: INTERNAL
    KAFKA_AUTO_CREATE_TOPICS_ENABLE: "false"
    KAFKA_NUM_PARTITIONS: 3
    KAFKA_DEFAULT_REPLICATION_FACTOR: 3
    KAFKA_MIN_INSYNC_REPLICAS: 2
    KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 3
    KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 3
    KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 2
    KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS: 0
    KAFKA_HEAP_OPTS: -Xms256m -Xmx384m
  healthcheck:
    test: ["CMD", "bash", "-ec", "exec 3<>/dev/tcp/127.0.0.1/29092"]
    interval: 10s
    timeout: 5s
    retries: 18

services:
  zk1:
    <<: *zookeeper
    hostname: __PREFIX__-zk1
    container_name: __PREFIX__-zk1
    environment: { <<: *zk-env, ZOOKEEPER_SERVER_ID: 1 }
    volumes: [zk1-data:/var/lib/zookeeper]
  zk2:
    <<: *zookeeper
    hostname: __PREFIX__-zk2
    container_name: __PREFIX__-zk2
    environment: { <<: *zk-env, ZOOKEEPER_SERVER_ID: 2 }
    volumes: [zk2-data:/var/lib/zookeeper]
  zk3:
    <<: *zookeeper
    hostname: __PREFIX__-zk3
    container_name: __PREFIX__-zk3
    environment: { <<: *zk-env, ZOOKEEPER_SERVER_ID: 3 }
    volumes: [zk3-data:/var/lib/zookeeper]

  broker1:
    <<: *kafka
    hostname: __PREFIX__-broker1
    container_name: __PREFIX__-broker1
    environment:
      <<: *kafka-env
      KAFKA_BROKER_ID: 1
      KAFKA_ADVERTISED_LISTENERS: INTERNAL://__PREFIX__-broker1:29092,HOST://127.0.0.1:__P1__
    ports: ["127.0.0.1:__P1__:9092"]
    volumes: [broker1-data:/var/lib/kafka/data]
  broker2:
    <<: *kafka
    hostname: __PREFIX__-broker2
    container_name: __PREFIX__-broker2
    environment:
      <<: *kafka-env
      KAFKA_BROKER_ID: 2
      KAFKA_ADVERTISED_LISTENERS: INTERNAL://__PREFIX__-broker2:29092,HOST://127.0.0.1:__P2__
    ports: ["127.0.0.1:__P2__:9092"]
    volumes: [broker2-data:/var/lib/kafka/data]
  broker3:
    <<: *kafka
    hostname: __PREFIX__-broker3
    container_name: __PREFIX__-broker3
    environment:
      <<: *kafka-env
      KAFKA_BROKER_ID: 3
      KAFKA_ADVERTISED_LISTENERS: INTERNAL://__PREFIX__-broker3:29092,HOST://127.0.0.1:__P3__
    ports: ["127.0.0.1:__P3__:9092"]
    volumes: [broker3-data:/var/lib/kafka/data]

  init-kafka:
    image: confluentinc/cp-kafka:7.6.0
    container_name: __PREFIX__-init-kafka
    depends_on:
      broker1: { condition: service_healthy }
      broker2: { condition: service_healthy }
      broker3: { condition: service_healthy }
    environment:
      JAVA_TOOL_OPTIONS: -XX:ActiveProcessorCount=2 -Dkafka.logs.dir=/tmp
    entrypoint: ["/bin/bash", "-ec"]
    command:
      - |
        cub kafka-ready 3 120 -b broker1:29092,broker2:29092,broker3:29092
        # Tópico efímero exclusivo de esta prueba: nunca p360.interacciones.v1 ni p360.pagos.v1.
        kafka-topics --bootstrap-server broker1:29092,broker2:29092,broker3:29092 \
          --create --topic __TOPIC__ --partitions 3 --replication-factor 3 \
          --config min.insync.replicas=3 --config cleanup.policy=delete --config retention.ms=3600000

  db:
    image: postgres:16-alpine
    container_name: __PREFIX__-db
    environment:
      POSTGRES_DB: __DB__
      POSTGRES_USER: p360_test
      POSTGRES_PASSWORD: p360_test
    ports: ["127.0.0.1:__P4__:5432"]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U p360_test -d __DB__"]
      interval: 5s
      timeout: 5s
      retries: 20

volumes:
  zk1-data:
    name: __VOL__zk1-data
  zk2-data:
    name: __VOL__zk2-data
  zk3-data:
    name: __VOL__zk3-data
  broker1-data:
    name: __VOL__broker1-data
  broker2-data:
    name: __VOL__broker2-data
  broker3-data:
    name: __VOL__broker3-data
'@

$contenido = $plantilla.Replace('__PROJECT__', $proyecto)
$contenido = $contenido.Replace('__PREFIX__', $prefijo)
$contenido = $contenido.Replace('__VOL__', $prefijoVolumen)
$contenido = $contenido.Replace('__P1__', "$p1")
$contenido = $contenido.Replace('__P2__', "$p2")
$contenido = $contenido.Replace('__P3__', "$p3")
$contenido = $contenido.Replace('__P4__', "$p4")
$contenido = $contenido.Replace('__TOPIC__', $topico)
$contenido = $contenido.Replace('__DB__', $baseDatos)
Set-Content -Path $archivoCompose -Value $contenido -Encoding UTF8

function Invoke-Compose {
  param([string[]]$Argumentos)
  # La stderr de un nativo se convierte en ErrorRecord con 2>&1; con 'Stop'
  # interrumpiría el pipeline antes de ver el código de salida real.
  $preferencia = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  try {
    $lineas = & docker compose -f $archivoCompose @Argumentos 2>&1 | ForEach-Object { $_.ToString() }
    $codigo = $LASTEXITCODE
  } finally {
    $ErrorActionPreference = $preferencia
  }
  $salida = (($lineas -join [Environment]::NewLine) -replace "`r", [Environment]::NewLine)
  if ($codigo -ne 0) {
    throw "docker compose $($Argumentos -join ' ') falló ($codigo): $salida"
  }
  return $salida
}

function Esperar-Servicios {
  $inicio = Get-Date
  do {
    $estados = (Invoke-Compose @('ps', '-a', '--format', '{{.Name}} {{.Status}}')) -join "`n"
    $brokers = @($estados -split "`n" | Where-Object { $_ -match "$prefijo-broker[123]\s+Up .*\(healthy\)" })
    $listo = ($estados -match "$prefijo-db\s+Up .*\(healthy\)") -and
             ($estados -match "$prefijo-init-kafka\s+Exited \(0\)") -and
             ($brokers.Count -eq 3)
    if ($listo) { return }
    Start-Sleep -Seconds 2
  } while (((Get-Date) - $inicio).TotalSeconds -lt 240)
  throw "Los servicios no estuvieron listos en 240s:`n$estados"
}

$variables = @(
  'P360_M05_BOOTSTRAP', 'P360_M05_TOPIC', 'P360_M05_COMPOSE_FILE',
  'P360_TEST_POSTGRES_URL', 'P360_TEST_POSTGRES_USER', 'P360_TEST_POSTGRES_PASSWORD'
)
$valoresPrevios = @{}
foreach ($nombre in $variables) { $valoresPrevios[$nombre] = [Environment]::GetEnvironmentVariable($nombre) }

$codigoFinal = 1
Push-Location $repo
try {
  Escribir "Comando reproducible:"
  Escribir "  pwsh -NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`"" 'White'
  Escribir "Repo: $repo"
  Escribir "Proyecto/compose aislado: $proyecto | contenedores: $prefijo-* | volúmenes: $prefijoVolumen*"
  Escribir "Puertos únicos: Kafka $p1/$p2/$p3 | PostgreSQL $p4 | BD $baseDatos"
  Escribir "Tópico efímero (3 particiones, RF3, min.insync.replicas=3): $topico"
  Escribir "Credenciales: solo usuario local p360_test de la BD desechable; sin AWS/Azure ni secretos." 'Yellow'

  Escribir '[1/4] Levantando clúster efímero y PostgreSQL desechable...'
  Invoke-Compose @('up', '-d') | Out-Null
  Esperar-Servicios
  Escribir 'Servicios listos (3 brokers + Postgres).'

  $env:P360_M05_BOOTSTRAP = $bootstrap
  $env:P360_M05_TOPIC = $topico
  $env:P360_M05_COMPOSE_FILE = $archivoCompose
  $env:P360_TEST_POSTGRES_URL = "jdbc:postgresql://127.0.0.1:$p4/$baseDatos"
  $env:P360_TEST_POSTGRES_USER = 'p360_test'
  $env:P360_TEST_POSTGRES_PASSWORD = 'p360_test'

  Escribir '[2/4] Ejecutando M05KafkaCaidaIT con el publisher real y broker real...'
  & mvn -o -Dtest=M05KafkaCaidaIT test | Tee-Object -FilePath $log
  $codigoMaven = $LASTEXITCODE

  Escribir '[3/4] Resultados:'
  $rutaReporte = Join-Path $repo 'target\surefire-reports'
  $reportes = @(Get-ChildItem -Path $rutaReporte -Filter 'TEST-*M05KafkaCaidaIT.xml' -ErrorAction SilentlyContinue)
  if ($reportes.Count -gt 0) {
    [xml]$xml = Get-Content -Path $reportes[0].FullName -Raw
    $suite = $xml.testsuite
    Escribir "  M05KafkaCaidaIT: ejecutadas=$($suite.tests) fallidas=$($suite.failures) errores=$($suite.errors) omitidas=$($suite.skipped)"
    if ([int]$suite.tests -gt 0 -and [int]$suite.failures -eq 0 -and
        [int]$suite.errors -eq 0 -and [int]$suite.skipped -eq 0 -and $codigoMaven -eq 0) {
      $codigoFinal = 0
      Escribir '  Todos los casos M05 pasaron con broker Kafka real.' 'Green'
    } else {
      Escribir "  Maven terminó con código $codigoMaven; revisa $log" 'Red'
    }
  } else {
    Escribir "  No se generó reporte surefire; revisa $log (código $codigoMaven)" 'Red'
  }
} catch {
  Escribir "Fallo en la prueba: $($_.Exception.Message)" 'Red'
  $codigoFinal = 1
} finally {
  Escribir '[4/4] Limpieza (finally)...'
  foreach ($nombre in $variables) {
    [Environment]::SetEnvironmentVariable($nombre, $valoresPrevios[$nombre])
  }
  try { Invoke-Compose @('start', 'broker3') | Out-Null } catch { Escribir "  Aviso al restaurar broker3: $($_.Exception.Message)" 'Yellow' }
  try {
    Invoke-Compose @('exec', '-T', 'broker1', 'kafka-topics',
      '--bootstrap-server', $bootstrapInterno, '--delete', '--topic', $topico) | Out-Null
    Escribir "  Tópico efímero borrado: $topico"
  } catch { Escribir "  Aviso al borrar el tópico: $($_.Exception.Message)" 'Yellow' }
  try {
    Invoke-Compose @('down', '-v', '--remove-orphans') | Out-Null
    Escribir '  Contenedores y volúmenes eliminados.'
  } catch { Escribir "  Aviso al bajar compose: $($_.Exception.Message)" 'Yellow' }
  if ($Depurar -and $codigoFinal -ne 0) {
    Escribir "  -Depurar: se conserva $temporal (compose y log)" 'Yellow'
  } else {
    Remove-Item -Path $temporal -Recurse -Force -ErrorAction SilentlyContinue
    Escribir '  Directorio temporal eliminado. Sin cambios en servicios de negocio.' 'Green'
  }
  Pop-Location
}

exit $codigoFinal
