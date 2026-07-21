# Refactor / performance backlog

Tracks code in `shop` that works correctly but could be improved - performance,
readability, duplication, or a better long-term implementation. Nothing here is
broken; if it's producing a wrong result, it belongs in
[`docs/backlog/bugs/`](../bugs/) instead.

Every entry gets one file here, named `YYYY-MM-DD-short-slug.md` (date found,
matching the `docs/specs/` / `docs/plans/` / `docs/backlog/bugs/` convention).
Use `TEMPLATE.md` as the starting point for a new entry.

## Impact levels

| Level | Meaning |
|---|---|
| **High** | Measurable user-facing latency/cost, or a design that will actively fight future features (hard to extend, easy to misuse). |
| **Medium** | Noticeable inefficiency or duplication that adds real maintenance cost, but doesn't block anything today. |
| **Low** | Minor cleanup - readability, small duplication, style drift. Nice to have. |

## Effort levels

| Level | Meaning |
|---|---|
| **Small** | Localized change, one file or a handful of lines, low risk. |
| **Medium** | Spans a few files or touches a shared abstraction; needs care but not a redesign. |
| **Large** | Cross-cutting change, new abstraction, or touches a lot of call sites. |

## Status values

`Open` - `In Progress` - `Done` - `Won't Do` (with reasoning in the entry).

## Index

| Date | Entry | Impact | Effort | Status |
|---|---|---|---|---|
| _(none yet)_ | | | | |
