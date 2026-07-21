# Role-check denials return 401 instead of 403

**Severity:** Medium - wrong status code on every role-gated endpoint denial, but the
request is still correctly rejected (no unauthorized access, no data exposure).
**Status:** Open
**Found:** 2026-07-21, while E2E-verifying the Swagger/OpenAPI documentation (see
[`docs/plans/2026-07-21-swagger-openapi-documentation-plan.md`](../plans/2026-07-21-swagger-openapi-documentation-plan.md))
against a local `./mvnw spring-boot:run` instance.
**Affected area:** `src/main/java/com/ecommerce/shop/security/SecurityConfig.java` - every
endpoint guarded by `.hasRole(...)` (currently only `/api/admin/**`, but this will hit any
future role-gated route the same way).

## Bug

A valid, authenticated user who is missing the required role should get `403 Forbidden`
from `/api/admin/**`. Instead they get `401 Unauthorized` with an empty body - the same
response an unauthenticated caller gets. A client can't distinguish "you're not logged in"
from "you're logged in but not allowed" from the status code alone, which is what these two
codes exist to communicate.

## How to reproduce

With MongoDB reachable at `localhost:27017` and the app running on port 8080:

```bash
# 1. Register and log in as a non-admin (CUSTOMER)
curl -s -X POST http://localhost:8080/api/auth/register -H "Content-Type: application/json" \
  -d '{"email":"repro@example.com","password":"password123","role":"CUSTOMER"}'

TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login -H "Content-Type: application/json" \
  -d '{"email":"repro@example.com","password":"password123"}' | python3 -c "import json,sys;print(json.load(sys.stdin)['accessToken'])")

# 2. Confirm the token is valid on an authenticated-only endpoint
curl -i http://localhost:8080/api/me -H "Authorization: Bearer $TOKEN"
# -> 200, returns the user - proves the token itself is fine

# 3. Hit the role-gated endpoint with the same, valid, non-admin token
curl -i http://localhost:8080/api/admin/ping -H "Authorization: Bearer $TOKEN"
```

**Observed:** step 3 returns `HTTP/1.1 401` with an empty body.
**Expected:** step 3 should return `HTTP/1.1 403` with an empty body.

## Root cause

Confirmed by re-running with `--logging.level.org.springframework.security=DEBUG`:

```
Securing GET /api/admin/ping
o.s.s.w.access.AccessDeniedHandlerImpl   : Responding with 403 status code
Securing GET /error
o.s.s.w.a.AnonymousAuthenticationFilter  : Set SecurityContextHolder to anonymous SecurityContext
```

Spring Security *does* correctly compute a 403 for the original request. But
`AccessDeniedHandlerImpl` gets there via `response.sendError(403)`, which triggers a
servlet-container forward to Spring Boot's `/error` handler. That forward re-enters the
*same* security filter chain as a fresh request. `/error` isn't in the `permitAll()`
matchers, so it falls under `anyRequest().authenticated()`; the forwarded request ends up
with an anonymous `SecurityContext`, which `ExceptionTranslationFilter` treats as "not
authenticated" rather than "forbidden" - so it invokes the custom
`authenticationEntryPoint` in `SecurityConfig`:

```java
.exceptionHandling(handling -> handling.authenticationEntryPoint(
        (request, response, authException) -> response.sendError(HttpServletResponse.SC_UNAUTHORIZED)
))
```

That second `sendError(401)` overwrites the still-uncommitted response before Tomcat sends
it, so the client only ever sees 401. This isn't specific to `/api/admin/ping` - it will
happen for any `AccessDeniedException` anywhere in the app, because the `/error` forward is
unconditionally re-secured.

## Suggested fix

Add `/error` to the `permitAll()` matchers in `SecurityConfig`:

```java
.requestMatchers("/error").permitAll()
```

This lets the forwarded `/error` request pass through without being re-authenticated, so
the original `AccessDeniedHandlerImpl` response (403) is what actually gets sent. This is
the standard fix for this well-known Spring Security + custom `AuthenticationEntryPoint`
interaction and shouldn't weaken anything - `/error` isn't a real resource, and Spring
Boot's default error page/JSON is already fine to serve without auth.

After the fix, re-verify: `/api/admin/ping` with a valid non-admin token should return 403,
and the Swagger docs for that endpoint (`docs/plans/2026-07-21-swagger-openapi-documentation-plan.md`,
Task 4) already describe both 401 and 403 as empty-body responses, so no doc changes should
be needed once the status code itself is corrected - just re-verify that assumption still
holds (Boot's default `/error` JSON might get involved if `/error` is `permitAll()`'d rather
than skipped, worth a quick empirical check).

## Notes

Not a security hole - unauthorized requests are still rejected either way, this is purely
about the response contract. But it will confuse any API consumer (including the Postman
collection and Swagger UI users) that branches on 401 vs. 403, and it silently defeats the
`@ApiResponse(responseCode = "403", ...)` documentation added for `/api/admin/ping` in the
Swagger work, since that code path is currently unreachable.
