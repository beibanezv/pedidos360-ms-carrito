# ms-carrito — Pedidos360

Microservicio de carrito (privado, por usuario `oid/sub` de Azure AD).

## Requisitos
- Java 17, Maven 3.9+, Docker

## Desarrollo local
```bash
docker compose up -d          # Postgres en localhost:5433 (convive con ms-productos en 5432)
mvn spring-boot:run           # :8082
```

## Endpoints (todos requieren JWT)
- `GET /carrito` — lista items del usuario autenticado
- `POST /carrito` — agrega `{productoId, cantidad, precioClp?}`
- `PUT /carrito/{id}` — actualiza cantidad
- `DELETE /carrito/{id}` — borra item
- `DELETE /carrito` — vacía carrito

Scope/role: `SCOPE_Carrito.ReadWrite` o `ROLE_Cliente`/`ROLE_Admin` (claim `scp`/`roles` de Azure). Ver `SecurityConfig.java`.

## Producción
```bash
SPRING_PROFILES_ACTIVE=prod SPRING_DATASOURCE_URL=... AZURE_TENANT_JWKS_URI=https://login.microsoftonline.com/<tenant>/v2.0 java -jar target/ms-carrito-*.jar
```

## Tests
```bash
mvn test
```
