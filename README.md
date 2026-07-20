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

## Project structure

Code is organized by feature under `com.ecommerce.shop` (e.g. `auth/`, `user/`, `security/`) rather than by technical layer. See [`docs/specs/2026-07-20-jwt-auth-and-structure-design.md`](docs/specs/2026-07-20-jwt-auth-and-structure-design.md) for the reasoning, and [`docs/plans/2026-07-20-jwt-auth-and-structure-plan.md`](docs/plans/2026-07-20-jwt-auth-and-structure-plan.md) for the task-by-task build-out.
