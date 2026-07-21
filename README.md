# shop

An e-commerce API built with Spring Boot 4.1 (Java 21), Spring Security, and MongoDB. Started as a learning project for JWT authentication and feature-based Java project structure — see [`docs/`](docs) for the design spec, concepts write-up, and implementation plan behind the current work.

## Prerequisites

- Java 21
- Docker (for running MongoDB locally)

The Maven wrapper (`./mvnw`) is committed, so a separate Maven install isn't required.

## Clone

```bash
git clone <repository-url>
cd api
```

## Run MongoDB

The app expects a MongoDB instance at `localhost:27017`. Start one with Docker Compose:

```bash
docker compose up -d mongodb
```

This starts a `mongo:7.0` container with a named volume (`mongo_data`) for persistence, so data survives container restarts.

## Build

```bash
./mvnw clean install
```

## Run the app

With MongoDB running (see above):

```bash
./mvnw spring-boot:run
```

The API starts on `http://localhost:8080`.

Alternatively, run the whole stack (app + MongoDB) in Docker:

```bash
docker compose up --build
```

## Run tests

```bash
./mvnw test
```

Most tests are integration tests that talk to a real MongoDB, so `docker compose up -d mongodb` must be running first. Tests use a separate `shop_test` database, so they won't touch your dev data.

## Configuration

Configuration lives in `src/main/resources/application.properties`. Key settings, overridable via environment variables:

| Property | Env var | Default |
|---|---|---|
| `spring.data.mongodb.uri` | `SPRING_DATA_MONGODB_URI` | `mongodb://localhost:27017/shop` |
| `jwt.secret` | `JWT_SECRET` | a dev-only placeholder — override this for any real deployment |

## API endpoints

| Method | Path | Auth required | Purpose |
|---|---|---|---|
| POST | `/api/auth/register` | none | Create a `CUSTOMER` or `SELLER` account (email, 8+ char password, role) |
| POST | `/api/auth/login` | none | Verify credentials, returns `{ accessToken, refreshToken, tokenType, expiresInMs }` |
| POST | `/api/auth/refresh` | none (valid refresh token in body) | Rotates to a new access + refresh token pair |
| POST | `/api/auth/logout` | none (valid refresh token in body) | Revokes the refresh token |
| GET | `/api/me` | any authenticated user | Returns the caller's own id/email/role |
| GET | `/api/admin/ping` | `ADMIN` only | Trivial role-check demo endpoint |

Send the access token as `Authorization: Bearer <token>` on any request that requires auth.
`ADMIN` accounts aren't self-service - `register` only accepts `CUSTOMER`/`SELLER`; create an
admin manually if you need one. See
[`docs/concepts/jwt-authentication.md`](docs/concepts/jwt-authentication.md) for how the tokens
and the security filter chain actually work.

A ready-to-import Postman collection covering every endpoint above lives at
[`docs/requests/java-commerce-api.postman_collection.json`](docs/requests/java-commerce-api.postman_collection.json)
(see [`docs/requests/README.md`](docs/requests/README.md) for usage). Interactive Swagger/OpenAPI
docs are planned but not yet implemented - see
[`docs/plans/2026-07-21-swagger-openapi-documentation-plan.md`](docs/plans/2026-07-21-swagger-openapi-documentation-plan.md).

## Project structure

Code is organized by feature under `com.ecommerce.shop` (e.g. `auth/`, `user/`, `security/`) rather than by technical layer. See [`docs/specs/2026-07-20-jwt-auth-and-structure-design.md`](docs/specs/2026-07-20-jwt-auth-and-structure-design.md) for the reasoning, and [`docs/plans/2026-07-20-jwt-auth-and-structure-plan.md`](docs/plans/2026-07-20-jwt-auth-and-structure-plan.md) for the task-by-task build-out.
