# ms-carrito — Pedidos360

Microservicio de carrito (privado, por usuario `oid/sub` de Azure AD).

## Requisitos
- Java 17, Maven 3.9+, Docker

## Desarrollo local
```bash
docker compose up -d          # Postgres en localhost:5433 (convive con ms-productos en 5432)
mvn spring-boot:run           # :8082
```

## Modelo
- `Carrito` (`id`, `usuarioId` desde `oid/sub` del JWT, `createdAt`)
- `CarritoItem` (`id`, `carritoId`, `productoId`, `cantidad`, `precioUnitarioClp`)

`usuarioId` nunca viene del cliente, se toma del token (`CarritoController.java`).

## Endpoints (todos requieren JWT)
- `GET /carrito` — carrito del usuario autenticado `{id, usuarioId, createdAt, items}`
- `POST /carrito/items` — agrega `{productoId, cantidad, precioUnitarioClp?}`
- `PUT /carrito/items/{id}` — actualiza cantidad/precio
- `DELETE /carrito/items/{id}` — borra item
- `POST /carrito/checkout` — consulta el precio vigente de cada producto, registra la orden y vacía el carrito (devuelve `totalClp`). Si el catálogo no responde, devuelve 503 sin vaciar el carrito.

Escritura protegida con `SCOPE_Carrito.ReadWrite` (o `ROLE_Cliente`/`ROLE_Admin`) vía `@PreAuthorize` (`SecurityConfig.java` + `@EnableMethodSecurity` en `CarritoController.java`). Lectura solo requiere JWT válido.

## Producción
Configura `PRODUCTOS_URL` con el origen privado de ms-productos (`http://localhost:8081` en local). El precio de las líneas enviado desde el navegador no se usa para registrar la orden.

```bash
SPRING_PROFILES_ACTIVE=prod SPRING_DATASOURCE_URL=... AZURE_TENANT_ID=<Directory (tenant) ID> java -jar target/ms-carrito-*.jar
```

## Tests
```bash
mvn test
```
