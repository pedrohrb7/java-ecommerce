# Swagger / OpenAPI Documentation Implementation Plan

**Status:** Tasks 1-4 implemented and verified against a local `./mvnw spring-boot:run` instance on an alternate port (the `shop-app` Docker container was left untouched, at the user's request, so it still serves a stale pre-auth image - see Task 5). Task 5's app-restart/README steps are pending a manual `docker compose up -d --build app` by the user.

**Bug found during verification, out of scope for this plan:** see
[`docs/backlog/bugs/2026-07-21-admin-role-check-returns-401-instead-of-403.md`](../backlog/bugs/2026-07-21-admin-role-check-returns-401-instead-of-403.md).

**Goal:** Make interactive, always-current OpenAPI (Swagger) documentation the official API documentation for `shop`, replacing manual upkeep of endpoint tables in `README.md` as the source of truth (the tables can stay as a quick reference, but the generated docs become authoritative). This complements, not replaces, the manual Postman collection at `docs/requests/` - Swagger UI is for exploring/trying the API; the Postman collection is for saved, repeatable request flows.

**Why generated docs matter here:** endpoint shape currently lives only in code (`AuthController`, `DemoController`, the `auth/dto` records) and has to be hand-transcribed into `README.md` and the Postman collection whenever it changes. OpenAPI generation from annotations removes that manual sync step for the request/response schema itself.

**Tech stack decision:** [`springdoc-openapi`](https://springdoc.org/) - the standard OpenAPI 3 integration for Spring Boot (Spring Fox is unmaintained). Verified compatible starter for this project's Spring Boot 4.1.0 / Spring Framework 7 stack: `org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.3`. Pin this version explicitly in `pom.xml` (Boot's parent POM does not manage springdoc versions) and confirm on first build that it resolves cleanly against Boot 4.1.0 - if not, check the [springdoc release notes](https://github.com/springdoc/springdoc-openapi/releases) for the version that lists Boot 4.1 support and use that instead.

## Global constraints

- Package placement: OpenAPI config is cross-cutting infrastructure, not a feature. Put it in a new `common/config/` package (sibling to the existing `common/exception/`), not inside `security/` or `auth/`.
- `SecurityConfig` currently permits only `/api/auth/**` and locks everything else behind authentication (`anyRequest().authenticated()`). Swagger UI's static assets and the `/v3/api-docs` JSON must be added to the `permitAll()` matchers or the docs page itself will 401.
- Keep `docs/requests/java-commerce-api.postman_collection.json` and this plan's Swagger UI in sync conceptually (same six endpoints); no need to keep them byte-identical, but don't let one document an endpoint the other omits.
- Don't hand-write OpenAPI YAML/JSON - springdoc generates the spec from the existing controllers, `@Valid` DTOs, and annotations added below. Hand-maintained spec files would immediately drift, same problem this plan exists to solve.

---

### Task 1: Add the dependency and confirm auto-generation works

**Files:**
- Modify: `pom.xml`

**Steps:**
- [x] Add to `pom.xml`:
  ```xml
  <dependency>
      <groupId>org.springdoc</groupId>
      <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
      <version>3.0.3</version>
  </dependency>
  ```
- [x] `./mvnw clean install` and confirm it resolves against Boot 4.1.0 without version conflicts. If it fails, check springdoc's release notes for the correct version for Boot 4.1 and update the version above accordingly. (Resolved cleanly, no conflicts.)
- [x] Run the app and confirm `http://localhost:8080/v3/api-docs` returns a JSON OpenAPI document (it will only cover `/api/me` and `/api/admin/ping` at this point, since `/api/auth/**` is a `POST`-only permitAll zone that springdoc can already see - Spring Security blocking the docs UI itself is handled in Task 2). Expect this first run to otherwise mostly work already, since springdoc auto-detects `@RestController` beans without further config. (Verified on local port 8081, not the Docker container - see plan status above.)

---

### Task 2: Open the Swagger paths in Spring Security

**Files:**
- Modify: `src/main/java/com/ecommerce/shop/security/SecurityConfig.java`

**Steps:**
- [x] Add to the `authorizeHttpRequests` matchers in `securityFilterChain`, before `anyRequest().authenticated()`:
  ```java
  .requestMatchers(
          "/v3/api-docs/**",
          "/swagger-ui/**",
          "/swagger-ui.html"
  ).permitAll()
  ```
- [x] Restart the app and confirm `http://localhost:8080/swagger-ui/index.html` loads without a 401/redirect-to-login. (Verified on local port 8081.)

---

### Task 3: OpenAPI metadata bean and JWT bearer scheme

**Files:**
- Create: `src/main/java/com/ecommerce/shop/common/config/OpenApiConfig.java`

**Purpose:** without this, Swagger UI has no way to attach `Authorization: Bearer <token>` to try-it-out calls against `/api/me` and `/api/admin/ping`, and the docs page has no title/description.

**Steps:**
- [x] Create the config class:
  ```java
  package com.ecommerce.shop.common.config;

  import io.swagger.v3.oas.models.Components;
  import io.swagger.v3.oas.models.OpenAPI;
  import io.swagger.v3.oas.models.info.Info;
  import io.swagger.v3.oas.models.security.SecurityRequirement;
  import io.swagger.v3.oas.models.security.SecurityScheme;
  import org.springframework.context.annotation.Bean;
  import org.springframework.context.annotation.Configuration;

  @Configuration
  public class OpenApiConfig {

      private static final String BEARER_SCHEME = "bearerAuth";

      @Bean
      public OpenAPI shopOpenApi() {
          return new OpenAPI()
                  .info(new Info()
                          .title("Java Commerce API")
                          .version("v1")
                          .description("JWT-authenticated e-commerce API. Register/login to obtain "
                                  + "an access token, then use the Authorize button below with "
                                  + "'Bearer <accessToken>' to call protected endpoints."))
                  .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME))
                  .components(new Components()
                          .addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                                  .name(BEARER_SCHEME)
                                  .type(SecurityScheme.Type.HTTP)
                                  .scheme("bearer")
                                  .bearerFormat("JWT")));
      }
  }
  ```
- [x] Confirm Swagger UI now shows an "Authorize" padlock, and that `/api/auth/**` endpoints (which don't need the header) still show up without requiring it - global `addSecurityItem` marks it as available/default, not mandatory per-endpoint, so this is cosmetic-only unless Task 4 overrides it per-operation. (Overridden per-operation in Task 4 via `@SecurityRequirements` on the four `/api/auth/**` methods.)

---

### Task 4: Annotate controllers and DTOs for accurate, readable docs

**Files:**
- Modify: `src/main/java/com/ecommerce/shop/auth/AuthController.java`
- Modify: `src/main/java/com/ecommerce/shop/demo/DemoController.java`
- Modify: `src/main/java/com/ecommerce/shop/auth/dto/*.java` (all five records)
- Modify: `src/main/java/com/ecommerce/shop/demo/MeResponse.java`
- Modify: `src/main/java/com/ecommerce/shop/common/exception/ApiError.java`

**Steps:**
- [x] Add `@Tag(name = "Auth")` / `@Tag(name = "Demo")` at the class level of each controller, so Swagger UI groups endpoints instead of listing them flat.
- [x] Add `@Operation(summary = ..., description = ...)` to each of the six endpoint methods, phrased for a reader who has never seen the code - e.g. register's description should state the role restriction (`CUSTOMER`/`SELLER` only, `ADMIN` not self-service) and the 8-character password minimum, since those are business rules not obvious from the URL.
- [x] Add `@ApiResponses` per method covering the real outcomes: `register` -> 201 + 409 (duplicate email, via `DuplicateEmailException`/`GlobalExceptionHandler`) + 400 (validation); `login` -> 200 + 401; `refresh`/`logout` -> 200/204 + 401 (invalid/expired refresh token, via `InvalidTokenException`); `me`/`admin/ping` -> 200 + 401 + 403. **Correction found during verification:** `ApiError` is only accurate for responses raised by `GlobalExceptionHandler` (register/login/refresh/logout's error cases, confirmed empirically). `/api/me` and `/api/admin/ping`'s 401/403 are raised by the security filter chain directly and return an **empty body**, not `ApiError` - the annotations were corrected to describe this instead of claiming a schema that isn't actually sent. (Related: [`docs/backlog/bugs/2026-07-21-admin-role-check-returns-401-instead-of-403.md`](../backlog/bugs/2026-07-21-admin-role-check-returns-401-instead-of-403.md).)
- [x] Add `@Schema(description = ...)` on each record component (e.g. `RegisterRequest.role`: "Must be CUSTOMER or SELLER"; `AuthResponse.expiresInMs`: "Access token lifetime in milliseconds from issuance"). These descriptions are the main payoff of this task - they're exactly the details currently only discoverable by reading validation annotations or `application.properties`.
- [x] Re-check `/v3/api-docs` and `/swagger-ui/index.html` after each file to confirm the annotations parse (a malformed annotation fails silently or throws at startup depending on the error - restart after each file rather than batching all edits). (Verified via `./mvnw clean compile` plus live requests against the local port-8081 instance.)

---

### Task 5: End-to-end verification and README update

**Steps:**
- [ ] With `docker compose up -d mongodb` running, `./mvnw spring-boot:run`, then in a browser: register a `CUSTOMER` via Swagger UI's try-it-out, log in, click Authorize and paste `Bearer <accessToken>`, then call `/api/me` and confirm it returns the right user - this is the real "does this work for an end user of the docs" check, not just "does the JSON spec look right."
- [ ] Also try `/api/admin/ping` unauthenticated and as a non-admin, confirming the 401/403 responses documented in Task 4 match reality.
- [ ] Update `README.md`: add a line under "API endpoints" pointing to `http://localhost:8080/swagger-ui/index.html` as the live, canonical API reference once the app is running, ahead of the manual table (which can stay as a no-server-needed quick glance).
- [ ] Optional, matches this repo's existing pattern of a teaching doc per feature ([`docs/concepts/jwt-authentication.md`](../concepts/jwt-authentication.md)): add `docs/concepts/openapi-swagger.md` covering what OpenAPI/Swagger UI are, how springdoc derives the spec from annotations instead of hand-written YAML, and why that keeps docs from drifting the way the README table can.
