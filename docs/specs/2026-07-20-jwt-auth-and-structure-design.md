# JWT Authentication & Feature-Based Folder Structure — Design

- **Date:** 2026-07-20
- **Status:** Approved
- **Project:** `shop` (java_commerce API) — Spring Boot 4.1.0, Java 21, MongoDB

## Context

The project is a freshly generated Spring Boot skeleton (MongoDB, Web MVC, Lombok) with no
security, no domain model, and everything in a single flat package
(`com.ecommerce.shop`). This design covers the first real feature: user accounts with
JWT-based authentication and role-based authorization, plus the package structure the rest
of the app (products, orders, etc.) will follow going forward.

Secondary goal: this is a learning project. Design decisions below note the trade-off
considered and why the chosen option was picked, not just the "production best practice" —
where a simpler alternative was passed over for pedagogical reasons, that's called out.

## Roles

Three distinct, mutually exclusive kinds of user: `CUSTOMER`, `SELLER`, `ADMIN`. Modeled as
a single `Role` enum field on `User`, not a `Set<Role>` — a person is one kind of user in
this app. If that assumption changes later (e.g. a seller who is also a customer), the field
can become a set without touching the auth flow itself.

`ADMIN` accounts are not self-service. `/api/auth/register` only accepts `CUSTOMER` or
`SELLER`. Admins are created out-of-band (manual DB insert / seed script) — deliberately no
"become an admin" endpoint.

## Package structure (feature-based)

```
com.ecommerce.shop
├── ShopApplication.java
├── common/
│   └── exception/
│       ├── ApiException.java           # base runtime exception carrying an HTTP status
│       ├── GlobalExceptionHandler.java # @RestControllerAdvice → maps exceptions to ApiError JSON
│       └── ApiError.java               # { timestamp, status, error, message, path }
├── user/
│   ├── User.java              # @Document, collection "users"
│   ├── Role.java              # enum: CUSTOMER, SELLER, ADMIN
│   └── UserRepository.java
├── auth/
│   ├── AuthController.java    # register / login / refresh / logout
│   ├── AuthService.java
│   ├── RefreshToken.java      # @Document, collection "refresh_tokens"
│   ├── RefreshTokenRepository.java
│   └── dto/
│       ├── RegisterRequest.java
│       ├── LoginRequest.java
│       ├── RefreshRequest.java
│       └── AuthResponse.java
├── security/
│   ├── SecurityConfig.java            # filter chain, password encoder, auth manager
│   ├── JwtService.java                # generate / parse / validate tokens
│   ├── JwtAuthenticationFilter.java   # reads Authorization header, populates security context
│   ├── JwtProperties.java             # binds jwt.secret / jwt.access-expiration / jwt.refresh-expiration
│   └── CustomUserDetailsService.java  # loads User by email, used only by the login flow
└── demo/
    └── DemoController.java    # GET /api/me, GET /api/admin/ping
```

**Why feature-based over layered-by-type:** each package (`auth/`, `user/`, `security/`) is
readable end-to-end without jumping between parallel `controller/`, `service/`,
`repository/` trees. `user` is split out from `auth` because other features (products,
orders) will reference `User` later; `auth` is specifically the login/token feature and
nothing else will depend on its internals directly.

## Data model

**`User`** (Mongo collection `users`)

| Field | Type | Notes |
|---|---|---|
| `id` | `String` | Mongo ObjectId |
| `email` | `String` | unique index |
| `password` | `String` | BCrypt hash, never returned in responses |
| `role` | `Role` | `CUSTOMER`, `SELLER`, or `ADMIN` |
| `createdAt` | `Instant` | |

**`RefreshToken`** (Mongo collection `refresh_tokens`)

| Field | Type | Notes |
|---|---|---|
| `id` | `String` | Mongo ObjectId |
| `userId` | `String` | references `User.id` |
| `jti` | `String` | the refresh JWT's unique ID claim; unique index |
| `expiresAt` | `Instant` | |
| `revoked` | `boolean` | default `false` |
| `createdAt` | `Instant` | |

Only the `jti` is stored, never the raw refresh token — revocation is a DB flag flip, not a
token comparison, and a leaked `refresh_tokens` collection doesn't hand out usable tokens
(the signing key is still required to forge one).

## Auth flow & endpoints

| Method | Path | Auth required | Purpose |
|---|---|---|---|
| POST | `/api/auth/register` | none | Create a CUSTOMER or SELLER account |
| POST | `/api/auth/login` | none | Verify credentials, issue access + refresh token |
| POST | `/api/auth/refresh` | none (valid refresh token in body) | Exchange for a new access + refresh token pair |
| POST | `/api/auth/logout` | none (valid refresh token in body) | Revoke the refresh token |
| GET | `/api/me` | any authenticated user | Returns caller's own email/role |
| GET | `/api/admin/ping` | ADMIN only | Demonstrates role-based authorization |

`register` returns `201` with basic user info only — no tokens. The client calls `login`
separately afterward. Chosen over an auto-login-on-register shortcut so the two flows stay
distinct and each is easier to reason about independently.

### JWT mechanics

- Library: `jjwt` (`io.jsonwebtoken`, 0.12.x), signing algorithm HMAC-SHA256, secret read
  from `jwt.secret` in `application.properties` (local dev only — production would source
  this from an environment variable / secrets manager, not source control).
- **Access token** — 15 minute expiry. Claims: `sub` (userId), `email`, `role`,
  `type: "access"`.
- **Refresh token** — 7 day expiry. Claims: `sub` (userId), `jti` (random UUID),
  `type: "refresh"`.
- The `type` claim is checked on every validation so an access token can't be replayed at
  `/refresh` and a refresh token can't be used to authenticate a normal request.
- `/refresh` validates the token, confirms its `jti` exists in `RefreshToken` and is neither
  revoked nor expired, then **rotates**: issues a new access+refresh pair and revokes the old
  `jti` in the same operation. A stolen-and-reused refresh token becomes a hard failure on the
  legitimate user's next refresh, which is the detection signal for token theft.
- `/logout` parses the refresh token and marks its `jti` revoked immediately — this only
  works because revocation is a DB lookup, not pure signature verification.

### Security configuration

- Stateless sessions (`SessionCreationPolicy.STATELESS`), CSRF disabled (no cookies are
  involved in this flow, so there's no CSRF surface).
- Authorization rules: `/api/auth/**` → `permitAll()`; `/api/admin/**` → `hasRole("ADMIN")`;
  everything else → `authenticated()`.
- `JwtAuthenticationFilter` (a `OncePerRequestFilter`) reads the `Authorization: Bearer
  <token>` header, validates it as an access token, and builds the `Authentication` directly
  from the token's claims (userId, email, role) — no database lookup per request. This is the
  point of stateless JWT auth: the token itself carries what's needed to authorize the
  request.
- `CustomUserDetailsService` is used only during `/login`, where Spring Security's
  `AuthenticationManager`/`DaoAuthenticationProvider` needs to load the user by email and
  verify the password hash.
- `PasswordEncoder`: `BCryptPasswordEncoder`.

## Error handling

`GlobalExceptionHandler` (`@RestControllerAdvice`) maps exceptions to a consistent JSON body
(`ApiError`: `timestamp`, `status`, `error`, `message`, `path`):

- Duplicate email on register → `409`
- Bad credentials on login → `401`, generic "invalid email or password" message (does not
  reveal whether the email or the password was wrong)
- Invalid, expired, or wrong-`type` token → `401`
- Bean validation failures (`@Valid` on request DTOs) → `400` with field-level messages
- Unhandled exceptions → `500`, generic body, no stack trace in the response

## Testing approach

Implementation will follow this repo's TDD convention. Planned coverage:

- Unit tests for `JwtService`: generation, valid parse, expired token rejected, tampered
  signature rejected, wrong `type` claim rejected.
- Integration tests (`spring-boot-starter-webmvc-test` +
  `spring-boot-starter-data-mongodb-test`) covering the full register → login → refresh →
  logout flow, and `/api/admin/ping` returning `403` for a CUSTOMER and `200` for an ADMIN.

## New dependencies

Added to `pom.xml`:

- `spring-boot-starter-security`
- `spring-boot-starter-validation` (for `@Valid` on request DTOs)
- `io.jsonwebtoken:jjwt-api`, `io.jsonwebtoken:jjwt-impl` (runtime),
  `io.jsonwebtoken:jjwt-jackson` (runtime) — pinned to a current 0.12.x release during
  implementation

## Documentation

- This spec: `docs/specs/2026-07-20-jwt-auth-and-structure-design.md`
- `docs/concepts/jwt-authentication.md` — plain-language walkthrough of the concepts
  involved (JWTs, signing, access vs refresh tokens, stateless auth, password hashing, the
  security filter chain, role-based authorization, refresh rotation/revocation, and why
  feature-based packaging was chosen), filled in progressively as each piece is implemented.

## Out of scope (for this design)

- Email verification, password reset flows
- OAuth2 / social login
- Admin self-registration or an admin-creation endpoint
- Rate limiting on auth endpoints
- Multi-role users (`Set<Role>`)
