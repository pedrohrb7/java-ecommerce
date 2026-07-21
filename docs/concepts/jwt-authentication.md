# JWT Authentication — Concepts & Decisions

This doc is a plain-language companion to
[`docs/specs/2026-07-20-jwt-auth-and-structure-design.md`](../specs/2026-07-20-jwt-auth-and-structure-design.md).
The spec says *what* we're building; this explains *why the pieces work the way they do*, so
it's useful to reread later without digging through code. It'll grow section by section as
each piece gets implemented, with links to the actual code once it exists.

## What is a JWT?

A JSON Web Token is three base64url-encoded parts joined by dots:
`header.payload.signature`.

- **Header** — which algorithm signed it (e.g. `HS256`).
- **Payload** — the "claims": arbitrary data, e.g. `sub` (subject/user id), `role`, `exp`
  (expiry timestamp). Anyone can decode and read this — it is **not encrypted**, just
  encoded. Never put secrets (passwords, card numbers) in a JWT payload.
- **Signature** — `HMAC-SHA256(header + "." + payload, secretKey)`. This is what makes the
  token trustworthy: anyone can *read* the payload, but only someone holding the secret key
  can produce a signature that matches it. If a client tampers with the payload (e.g.
  changes `role: "CUSTOMER"` to `role: "ADMIN"`), the signature no longer matches and the
  server rejects the token.

So a JWT is a tamper-evident, self-contained claim — not an encrypted secret.

## Signing vs. encryption

We're using a *signed* JWT (JWS), not an *encrypted* one (JWE). Signing proves the token
wasn't altered and came from someone holding the secret; it does not hide the contents. That
tradeoff is fine here because the payload only contains a user id, email, and role — nothing
that needs to stay secret from the token's own bearer.

## Why stateless authentication?

A traditional session-based login stores session state server-side (e.g. in memory or Redis)
and gives the client an opaque session ID cookie; every request requires a lookup to resolve
that ID back to a user. A JWT-based approach puts the (signed) user identity *in the token
itself*, so a request can be authenticated by checking a signature — no database round-trip
per request. That's "stateless" auth: the server holds no session state for access tokens.

The tradeoff: because the token is self-contained, the server can't simply "delete" it to log
someone out early — it's valid until it expires, unless you build a revocation mechanism.
That's exactly why refresh tokens (below) are handled differently from access tokens.

## Access tokens vs. refresh tokens

Two tokens, two different jobs:

- **Access token** — short-lived (15 min here), sent with every API request, never checked
  against the database. Short expiry limits the damage window if one leaks.
- **Refresh token** — long-lived (7 days here), used *only* to get a new access token, and
  *is* checked against the database (via its `jti` claim — see below). This is what makes
  logout and revocation actually possible: the access token can't be revoked early, but the
  refresh token can, so once it's revoked the user can't mint new access tokens anymore. The
  worst case for a leaked access token is a 15-minute window, not indefinite access.

## Why store a `jti`, not the raw refresh token

Every refresh token includes a `jti` (JWT ID) claim — a random UUID unique to that token. We
store *that* in MongoDB (linked to the user, with an expiry and a `revoked` flag), not the
full token string. Two reasons:

1. Revocation becomes a cheap DB flag flip instead of needing to compare token strings.
2. If the `refresh_tokens` collection ever leaked, it wouldn't contain anything usable —
   forging a valid token still requires the signing key, which isn't in the database.

## Refresh token rotation

Every time `/api/auth/refresh` is called, the old refresh token is revoked and a *new* one is
issued alongside the new access token — the old one can never be used again. This matters for
detecting theft: if an attacker steals a refresh token and uses it, then the legitimate user's
next refresh attempt fails (their token was already consumed). That failure is the signal that
something is wrong, which a non-rotating refresh token would never surface.

## Why a `type` claim on every token

Access and refresh tokens are both just JWTs signed with the same secret — nothing stops
someone from taking a refresh token and sending it as if it were an access token, unless the
server checks for it. Adding `type: "access"` / `type: "refresh"` to the claims and checking
it on every validation closes that gap: a refresh token presented to a normal API endpoint, or
an access token presented to `/refresh`, is rejected regardless of a valid signature.

## Password hashing

Passwords are hashed with BCrypt before being stored — never stored or logged in plain text.
BCrypt is deliberately slow (tunable via its "cost factor"), which makes brute-forcing a
stolen password hash impractical compared to a fast hash like plain SHA-256. Login compares
the submitted password against the stored hash via the same BCrypt function; the plaintext
password is never persisted anywhere.

## The security filter chain

Spring Security processes every incoming request through a chain of servlet filters before it
reaches a controller. Our `JwtAuthenticationFilter` is inserted into that chain: it looks for
an `Authorization: Bearer <token>` header, validates the token, and — if valid — populates the
`SecurityContext` with an `Authentication` built from the token's claims. Everything
downstream (controllers, `@PreAuthorize`, `hasRole(...)` checks) just reads that context; it
never needs to know a JWT was involved.

## Role-based authorization

Once the filter has populated the security context with the caller's role, authorization is
just a rule check — either declaratively in `SecurityConfig` (`.requestMatchers("/api/admin/**").hasRole("ADMIN")`)
or on individual endpoints. The role itself travels inside the access token's claims, so no
extra database lookup is needed to answer "is this caller allowed to do this?".

## 401 vs. 403 — and a Spring Security default that trips people up

`401 Unauthorized` means "I don't know who you are" (missing or invalid credentials);
`403 Forbidden` means "I know who you are, and you're not allowed to do this." Our tests
expect that split: no token → 401, valid token but wrong role → 403.

Spring Security doesn't give you 401 for free, though. It only sends 401 if something in the
chain — normally `httpBasic()` or `formLogin()` — registers an `AuthenticationEntryPoint` that
does so. We use neither (there's no browser login form or basic-auth prompt in a JWT API), so
without extra configuration Spring Security falls back to its default entry point,
`Http403ForbiddenEntryPoint`, which sends 403 for *everything* — including "you sent no token
at all." That surfaced as a real, failing test (`meWithoutTokenReturns401` got 403) once
`SecurityConfig` was wired up. The fix is to register an explicit `authenticationEntryPoint`
in the filter chain that returns 401 for the "not authenticated" case, leaving Spring
Security's default `AccessDeniedHandler` to return 403 for the separate "authenticated but
wrong role" case. Worth remembering any time a hand-rolled `SecurityFilterChain` skips
`httpBasic`/`formLogin`.

## Why feature-based packaging

Grouping code by feature (`auth/`, `user/`, `security/`) rather than by technical layer
(`controller/`, `service/`, `repository/`) means everything related to one concern lives
together — reading `auth/` top to bottom tells the whole story of registration/login/refresh
without jumping between parallel directory trees. It also scales better as more features
(products, orders, etc.) get added: each new feature is a new self-contained package, not more
files scattered across the same three top-level folders.

---

## Implemented so far

- **Local MongoDB & config plumbing** (Task 1) — `docker-compose.yml` runs a `mongo:7.0` container on `localhost:27017`; `application.properties` / `src/test/resources/application.properties` hold the Mongo URI and `jwt.*` settings (dev vs. test use separate databases, `shop` and `shop_test`).
- **User domain model** (Task 2) — `src/main/java/com/ecommerce/shop/user/{Role,User,UserRepository}.java`, tested in `UserRepositoryTest.java`. `Role` is the three-value enum (`CUSTOMER`, `SELLER`, `ADMIN`); `User` is the Mongo `@Document` with a unique-indexed `email`.
- **Signing, access/refresh tokens, the `type` claim, expiry** (Task 3) — `src/main/java/com/ecommerce/shop/security/JwtService.java`, tested in `JwtServiceTest.java`. Configuration (`jwt.secret`, expirations) is bound via `JwtProperties.java` (`@ConfigurationProperties`) from `application.properties`. The base `ApiException`/`ApiError` types (`common/exception/`) that later error handling builds on are also in place.
- **Password hashing & login-time user lookup** (Task 4) — `UserPrincipal.java` (wraps `User` as Spring Security's `UserDetails`), `CustomUserDetailsService.java`, tested in `CustomUserDetailsServiceTest.java`. Only used during login itself — see the next point for how a normal request is authenticated.
- **Security filter chain, role-based authorization, the 401/403 entry point fix** (Task 5) — `AuthenticatedUser.java`, `JwtAuthenticationFilter.java`, `SecurityConfig.java`, `demo/{MeResponse,DemoController}.java`, tested in `DemoControllerIntegrationTest.java`. This is where per-request auth happens: the filter builds `Authentication` straight from the access token's claims (no DB hit), and `/api/me` / `/api/admin/ping` prove it end to end. See "401 vs. 403" above for a real bug this task's tests caught.
- **Refresh token storage** (Task 6) — `auth/{RefreshToken,RefreshTokenRepository}.java`, tested in `RefreshTokenRepositoryTest.java`. The Mongo document backing the "Why store a `jti`" section above — `userId`, `jti`, `expiresAt`, `revoked`, nothing else.
- **Register endpoint & centralized error handling** (Task 7) — `auth/{AuthController,AuthService,DuplicateEmailException}.java`, `auth/dto/{RegisterRequest,UserResponse}.java`, `common/exception/GlobalExceptionHandler.java`, tested in `AuthControllerRegisterTest.java`. `POST /api/auth/register` hashes the password with the `PasswordEncoder` bean from `SecurityConfig` before saving, and rejects `ADMIN` as a self-selected role via a `@Pattern` constraint on `RegisterRequest`, not application code — bean validation failures and `ApiException` subclasses both funnel through `GlobalExceptionHandler` into the same `ApiError` JSON shape.
- **Login endpoint** (Task 8) — `auth/dto/{LoginRequest,AuthResponse}.java`, `AuthService.login()`/`issueTokenPair()`, `POST /api/auth/login` in `AuthController`, tested in `AuthControllerLoginTest.java`. Login is the one place `AuthenticationManager`/`DaoAuthenticationProvider`/`UserPrincipal` (Task 4) actually get used — everywhere else, requests are authenticated straight from the access token's claims. Wrong password and unknown email both come back as the same generic 401, because `DaoAuthenticationProvider` hides `UsernameNotFoundException` behind `BadCredentialsException` by default — this is what stops an attacker from using login responses to enumerate which emails are registered.
- **Refresh endpoint & rotation** (Task 9) — `auth/dto/RefreshRequest.java`, `AuthService.refresh()`/`revoke()`, `POST /api/auth/refresh` in `AuthController`, tested in `AuthControllerRefreshTest.java`. This is the "Refresh token rotation" section above made concrete: `refresh()` looks up the incoming token's `jti`, rejects it if revoked/expired/unknown, flips its `revoked` flag to `true` via the `revoke()` helper (the row stays, matching "Why store a `jti`" above), then calls the same private `issueTokenPair()` helper `login()` uses. A first pass at this used `deleteById` instead of a flag flip — worked, but left `RefreshToken.revoked` dead (checked, never set) and contradicted the spec's own "revokes the old" wording, so it was corrected to the flag-based version during doc review.
- **Logout endpoint** (Task 10) — `AuthService.logout()`, `POST /api/auth/logout` in `AuthController`, tested (along with the full register → login → refresh → logout → admin-role-check flow) in `AuthControllerLogoutAndFlowTest.java`. Reuses the same `revoke()` helper `refresh()` uses — logout is just "revoke this token and don't issue a new one."

All ten tasks from `docs/plans/2026-07-20-jwt-auth-and-structure-plan.md` are implemented; 30/30
tests passing. JWT auth (register/login/refresh/logout, role-based authorization) is complete
for this project's first feature.
