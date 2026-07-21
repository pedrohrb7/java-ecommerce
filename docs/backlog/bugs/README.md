# Bug registry

Every known bug in `shop` gets one file here, named `YYYY-MM-DD-short-slug.md`
(date the bug was found, matching the `docs/specs/` and `docs/plans/`
convention). Use `TEMPLATE.md` as the starting point for a new entry.

For performance and implementation-quality improvements that aren't bugs
(nothing is broken, just improvable), see
[`docs/backlog/refactor/`](../refactor/) instead.

## Severity levels

| Level | Meaning |
|---|---|
| **Critical** | Security bypass, data loss/corruption, or the app is unusable for a core flow. Fix before anything else ships. |
| **High** | Wrong behavior on a common path with a real user-facing or security-adjacent impact, but not an active exploit or outage. |
| **Medium** | Incorrect behavior with a workaround, or wrong on an uncommon/edge path. Contract violations (wrong status code, wrong shape) that don't break functionality land here. |
| **Low** | Cosmetic, log noise, minor inefficiency, or a rough edge that doesn't affect correctness. |

## Status values

`Open` - `In Progress` - `Fixed` - `Won't Fix` (with reasoning in the entry).

## Index

| Date | Entry | Severity | Status |
|---|---|---|---|
| 2026-07-21 | [Role-check denials return 401 instead of 403](2026-07-21-admin-role-check-returns-401-instead-of-403.md) | Medium | Open |
