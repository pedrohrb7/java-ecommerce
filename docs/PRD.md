# Product Requirements Document: Shop (java_commerce API)

**Status:** Draft
**Date:** 2026-07-21
**Owner:** Pedro Borges
**Related docs:** [`docs/CONSTRAINTS.md`](CONSTRAINTS.md) (project-wide rules),
[`docs/specs/`](specs/) (design decisions), [`docs/plans/`](plans/) (build-out),
[`docs/concepts/`](concepts/) (how it works), [`docs/backlog/`](backlog/) (known bugs and
improvement ideas)

## 1. Summary

Shop is a backend API for a multi-vendor e-commerce marketplace: independent sellers list
products, customers discover and buy them, and platform admins keep the marketplace running
safely. This document describes the product this API is building toward, not just the
authentication slice that exists today - it's the north star that later feature work
(catalog, cart, orders, payments) should be designed against, so those features fit a
coherent whole instead of being bolted on ad hoc.

This project is being built primarily to learn Java/Spring and API design; it isn't backed
by a real business case. The PRD is still written the way a real product's would be, because
that discipline - naming the user, the problem, and what's explicitly out of scope - is what
makes the resulting design decisions coherent rather than arbitrary.

## 2. Problem statement

Independent sellers who want to sell online face two bad options: build and maintain their
own storefront (cost, complexity, no built-in audience) or list on a large existing
marketplace (fees, no control, competing directly against the platform itself on price and
visibility). Customers, meanwhile, want one place to discover and buy from many small
sellers with a consistent, trustworthy checkout experience, rather than trusting a different
one-off storefront for every purchase.

Shop is the API layer for a marketplace that sits between these: sellers get a storefront
and reach without building their own infrastructure; customers get one consistent account,
cart, and checkout across every seller on the platform; admins get the tools to keep the
marketplace trustworthy (moderation, dispute handling, policy enforcement).

## 3. Target users

- **Customer** - browses the catalog across all sellers, manages a cart, places orders, and
  tracks their own order history. Never needs to know or care that different items in the
  same order might ship from different sellers.
- **Seller** - manages their own product listings, inventory, and pricing, and sees orders
  and payouts for what they've sold. Cannot see or affect other sellers' listings or data.
- **Admin** - operates the platform: moderates listings and accounts, resolves disputes, and
  has visibility across all sellers and customers. Not a self-service role -
  `/api/auth/register` only issues `CUSTOMER`/`SELLER` accounts; admins are provisioned
  out-of-band (see [`docs/specs/2026-07-20-jwt-auth-and-structure-design.md`](specs/2026-07-20-jwt-auth-and-structure-design.md)).

A single account is exactly one of these roles today (not, say, a seller who is also a
customer under the same login) - see the design spec above for the reasoning and what
would need to change if that assumption doesn't hold up later.

## 4. Vision: what "done" looks like

A customer can create an account, browse products from many independent sellers, add items
to a cart, check out, and track the resulting order(s) - with each seller's items handled as
their own fulfillment even within a single customer order. A seller can create an account,
list and manage their own products and inventory, and see the orders and revenue those
listings generate. An admin can see across the whole marketplace and take action - suspend a
listing, resolve a dispute, deactivate an account - without needing direct database access.

## 5. Goals

Because this isn't a live product with real traffic, success here is measured by capability
and correctness, not by adoption metrics (there's no "10,000 monthly active users" target to
report against). Concretely, the API should:

- Let a customer complete the full journey end-to-end: register -> browse -> cart -> order,
  purely through the API, with no manual data setup in between.
- Let a seller manage their own catalog and see only their own data - the multi-tenancy
  boundary (seller A cannot see or modify seller B's listings, inventory, or orders) is a
  hard product requirement, not just an implementation nicety.
- Give an admin the moderation/oversight tools needed to run the marketplace without direct
  database access for routine operations.
- Keep every endpoint's contract (request/response shapes, auth requirements, error
  behavior) discoverable and accurate via the Swagger/OpenAPI docs, so the API is usable by
  someone who has never read the source.

## 6. Scope

### Phase 1 - Accounts & Auth (shipped)

Registration, login, JWT access/refresh tokens, logout, and role-based authorization for the
three roles above. See [`docs/specs/2026-07-20-jwt-auth-and-structure-design.md`](specs/2026-07-20-jwt-auth-and-structure-design.md)
and [`docs/plans/2026-07-20-jwt-auth-and-structure-plan.md`](plans/2026-07-20-jwt-auth-and-structure-plan.md).
Interactive/OpenAPI docs added in [`docs/plans/2026-07-21-swagger-openapi-documentation-plan.md`](plans/2026-07-21-swagger-openapi-documentation-plan.md).

### Phase 2 - Product catalog (planned, not started)

Sellers create/update/delete their own product listings (name, description, price, stock,
category, images). Customers and unauthenticated visitors can browse and search the full
catalog across all sellers. A seller can only ever mutate their own listings.

### Phase 3 - Cart & checkout (planned, not started)

Customers add/remove/update items in a cart and check out into one or more orders, split by
seller for fulfillment purposes even when placed as a single checkout. Inventory is
decremented on order placement; out-of-stock items are rejected at checkout, not silently
allowed.

### Phase 4 - Orders & fulfillment (planned, not started)

Order status lifecycle (placed -> fulfilled -> delivered, plus cancellation) visible to the
customer who placed it and the seller(s) fulfilling it. Admins can see and intervene on any
order.

### Phase 5 - Payments (planned, not started)

A payment step in checkout. Given this is a learning project, this will most likely be a
stubbed/mock payment provider rather than a real integration - real card processing brings
PCI-DSS obligations that are explicitly out of scope (see Non-goals).

### Later / not yet scheduled

Reviews and ratings, seller payouts, search relevance/filtering beyond basic queries,
notifications (order updates, etc.). These aren't rejected, just not designed yet - adding
them here isn't a commitment.

## 7. Non-functional requirements

- **Security:** stateless JWT auth, bcrypt-hashed passwords, role-based authorization
  enforced at the API layer (not trusted from the client). Seller-to-seller and
  customer-to-customer data isolation is a security requirement, not just a UX nicety.
- **API contract quality:** every endpoint documented via OpenAPI/Swagger, kept in sync with
  the code by generating from annotations rather than hand-maintained spec files (see the
  Swagger plan linked above).
- **Consistency:** feature-based package structure (`user/`, `auth/`, `security/`,
  eventually `catalog/`, `cart/`, `order/`, etc.) rather than technical-layer packages, so
  each feature's code stays together as the product grows.

## 8. Non-goals (explicitly out of scope)

- **Real payment processing.** No PCI-DSS-scope card handling; any payment step will be a
  stub/mock, not a real processor integration.
- **A frontend/UI.** This is an API-only product; no web or mobile client is part of this
  project.
- **Multi-currency / internationalization.** Single currency, single locale, until/unless
  that becomes a real requirement.
- **Real-time features.** No live chat, live inventory sync across sessions, or websockets
  in the current vision.
- **Cross-role accounts.** A user is exactly one of `CUSTOMER`/`SELLER`/`ADMIN`; "a seller
  who also shops as a customer under the same login" is explicitly not supported today (see
  the design spec for what changing this would require).
- **Admin self-service signup.** Admins are always provisioned out-of-band, never through
  `/api/auth/register`.

## 9. Current status and tracking

Phase 1 is complete and documented. Interactive API docs (Swagger UI) are live once the app
is running - see the main [`README.md`](../README.md) for the URL. Known bugs are tracked in
[`docs/backlog/bugs/`](backlog/bugs/); implementation-quality/performance improvement ideas
are tracked in [`docs/backlog/refactor/`](backlog/refactor/). Each future phase above should
get its own design spec and plan under `docs/specs/` and `docs/plans/` before implementation
starts, following the pattern Phase 1 and the Swagger work already established.
