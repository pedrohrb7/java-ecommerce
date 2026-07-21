# Project Constraints

Rules that apply to the whole `shop` codebase, not just whichever feature is being built
right now. Any new spec or plan under `docs/specs/`/`docs/plans/` should assume these hold
and only call out constraints specific to that piece of work - not repeat these.

If a rule here needs to change, update it here first and note why, rather than quietly
diverging in a new feature's code.

## Tech stack

- Java 21, Spring Boot 4.1.0. Don't hardcode a dependency version that Boot's parent POM
  already manages - only pin a version explicitly when the library isn't Boot-managed (e.g.
  `io.jsonwebtoken:jjwt-*:0.12.6`, `org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.3`).
- MongoDB via Spring Data MongoDB. `spring.data.mongodb.uri` resolves to
  `mongodb://localhost:27017/shop` for the running app and `.../shop_test` for tests - both
  require MongoDB reachable at `localhost:27017` (`docker compose up -d mongodb`).
- No hand-written OpenAPI YAML/JSON. The spec is generated from controller/DTO annotations
  (`springdoc-openapi`) so it can't drift from the code - see
  [`docs/plans/2026-07-21-swagger-openapi-documentation-plan.md`](plans/2026-07-21-swagger-openapi-documentation-plan.md).

## Code organization

- Feature-based packages under `com.ecommerce.shop` (`user/`, `auth/`, `security/`,
  `demo/`, `common/`, and future features like `catalog/`, `cart/`, `order/`) - never
  introduce top-level `controller/`/`service/`/`repository/` packages that split a feature
  across technical layers instead of keeping it together.
- Cross-cutting infrastructure (config that isn't itself a feature - e.g. OpenAPI setup)
  lives under `common/config/`, not inside whichever feature happens to need it first.

## Roles and multi-tenancy

- Three roles: `CUSTOMER`, `SELLER`, `ADMIN`, modeled as a single `Role` enum field on
  `User` (not a set) - a person is exactly one kind of user. See
  [`docs/specs/2026-07-20-jwt-auth-and-structure-design.md`](specs/2026-07-20-jwt-auth-and-structure-design.md)
  for what changing this would require.
- `ADMIN` is never self-service. `/api/auth/register` only ever issues `CUSTOMER`/`SELLER`
  accounts; admins are provisioned out-of-band.
- Seller-to-seller and customer-to-customer data isolation is a hard requirement once
  catalog/orders exist: a seller can query/mutate only their own listings, inventory, and
  orders. This is enforced at the API/service layer, never left to the client. See
  [`docs/PRD.md`](PRD.md) for the product-level reasoning.

## Auth and security

- Passwords hashed with `BCryptPasswordEncoder`. Never log or return a password.
- JWTs carry a `type` claim (`"access"` or `"refresh"`), checked on every validation - an
  access token must never be accepted where a refresh token is expected or vice versa.
- Access tokens expire in 15 minutes, refresh tokens in 7 days
  (`jwt.access-token-expiration-ms` / `jwt.refresh-token-expiration-ms`).
- Refresh tokens are tracked in MongoDB (`refresh_tokens` collection) by their `jti` claim,
  not the raw token string. `/api/auth/refresh` rotates: the old `jti` is deleted and a new
  access+refresh pair is issued.
- `JwtAuthenticationFilter` must catch its own token-validation failures and leave the
  security context empty rather than let an exception propagate - filters run before the
  `DispatcherServlet`, so `@RestControllerAdvice` can never catch what they throw.
- Login must not distinguish "wrong password" from "unknown email" in its response - both
  surface as a generic 401 ("Invalid email or password").
- Any new `permitAll()` matcher in `SecurityConfig` (e.g. for docs endpoints) must be
  reviewed against the rest of the filter chain, not just added in isolation - see
  [`docs/backlog/bugs/2026-07-21-admin-role-check-returns-401-instead-of-403.md`](backlog/bugs/2026-07-21-admin-role-check-returns-401-instead-of-403.md)
  for an example of how a security-filter interaction (the `/error` forward) can silently
  break a status code that looks correct in isolation.

## API error contract

- Errors raised inside a controller/service (validation failures, `ApiException` subtypes,
  `BadCredentialsException`) go through `GlobalExceptionHandler` and return the `ApiError`
  JSON shape: `{ timestamp, status, error, message, path }`.
- Errors raised by the security filter chain itself (missing/invalid token -> 401,
  insufficient role -> 403) happen *before* `GlobalExceptionHandler` can see them and
  currently return an **empty body**, not `ApiError`. Don't document or assume an `ApiError`
  body for these unless `SecurityConfig` is changed to route them through the same handler -
  verify empirically (curl / a test) rather than assuming, since the two error paths look
  similar but aren't.

## Documentation conventions

- `docs/PRD.md` - the product vision (single file, kept current, not dated).
- `docs/CONSTRAINTS.md` - this file: project-wide rules. Single file, kept current.
- `docs/specs/YYYY-MM-DD-slug.md` - a design decision and its reasoning, one file per
  feature/decision, dated to when it was written.
- `docs/plans/YYYY-MM-DD-slug.md` - a task-by-task implementation plan with checkbox steps,
  dated, one file per feature.
- `docs/concepts/slug.md` - teaching write-ups explaining how something works, updated
  progressively as the relevant code lands.
- `docs/backlog/bugs/` and `docs/backlog/refactor/` - one file per known bug / improvement
  idea (`YYYY-MM-DD-slug.md`), each with a `README.md` index and severity/impact levels, and
  a `TEMPLATE.md` for new entries. Bugs describe wrong behavior; refactor entries describe
  correct-but-improvable code. Don't mix the two.
- `docs/requests/` - the Postman collection for manual testing, kept endpoint-complete
  alongside the Swagger docs (not necessarily byte-identical, but neither should document an
  endpoint the other omits).
