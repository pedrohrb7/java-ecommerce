# Postman collection

`java-commerce-api.postman_collection.json` is a Postman Collection v2.1 file covering every current endpoint: `/api/auth/register`, `/api/auth/login`, `/api/auth/refresh`, `/api/auth/logout`, `/api/me`, `/api/admin/ping`.

## Import

Postman -> Import -> select the JSON file. No separate environment is needed; the collection carries its own variables.

## Variables

| Variable | Purpose |
|---|---|
| `baseUrl` | Defaults to `http://localhost:8080` |
| `accessToken` | Auto-filled by the Login/Refresh requests' test scripts |
| `refreshToken` | Auto-filled by the Login/Refresh requests' test scripts |

Run **Login** (or **Register** then **Login**) first - its test script writes the tokens into the collection variables, so **Me**, **Admin Ping**, **Refresh**, and **Logout** pick them up automatically without manual copy-pasting.

`Admin Ping` requires an `ADMIN` user, which `/api/auth/register` cannot create (`CUSTOMER`/`SELLER` only) - expect `403` unless you've promoted a user to `ADMIN` directly in MongoDB.

This collection is a manual convenience for local testing. See `docs/plans/2026-07-21-swagger-openapi-documentation-plan.md` for the plan to add interactive, always-current API documentation via Swagger/OpenAPI.
