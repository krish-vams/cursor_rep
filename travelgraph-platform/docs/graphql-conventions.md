# TravelGraph Platform — Shared GraphQL Conventions

These rules are **mandatory** across every subgraph in this platform (`property-service`,
`pricing-service`, `booking-service`, `user-service`, `review-service`) and are the
specification source for the schema linter implemented in Phase 7.2. Each rule below has a
stable rule ID (`TG-NNN`) so the linter can emit precise, machine-checkable diagnostics.

> Audience: subgraph authors, schema reviewers, the linter implementer, the breaking-change
> checker implementer, and the AI schema review assistant (advisory only).

---

## 1. Naming

| Rule ID | Element                          | Convention             | Examples                                |
| ------- | -------------------------------- | ---------------------- | --------------------------------------- |
| TG-001  | Object types                     | `PascalCase`           | `Property`, `BookingRequest`            |
| TG-002  | Interface types                  | `PascalCase`           | `Node`, `Reviewable`                    |
| TG-003  | Union types                      | `PascalCase` + `Payload`/`Result` suffix when used as a mutation return | `CreateBookingPayload` |
| TG-004  | Input types                      | `PascalCase` + `Input` suffix | `CreateBookingInput`             |
| TG-005  | Enum types                       | `PascalCase`           | `BookingStatus`                         |
| TG-006  | Enum values                      | `ALL_CAPS_SNAKE_CASE`  | `CONFIRMED`, `AWAITING_PAYMENT`         |
| TG-007  | Fields and arguments             | `camelCase`            | `checkInDate`, `pageSize`               |
| TG-008  | Scalars (custom)                 | `PascalCase`           | `DateTime`, `UUID`, `Money`             |
| TG-009  | Directives (custom)              | `camelCase`            | `@requiresAuth`, `@deprecated`          |
| TG-010  | Mutation field names             | `verb` + `Object` in camelCase | `createBooking`, `cancelBooking` |
| TG-011  | Boolean fields                   | Prefix with `is`/`has`/`can` | `isActive`, `hasReviews`, `canCancel` |
| TG-012  | Connection / pagination types    | `XConnection`, `XEdge` | `PropertyConnection`, `PropertyEdge`    |
| TG-013  | Avoid abbreviations              | Spell out unless industry-standard (`URL`, `ID`, `UUID`) | `description` not `desc` |
| TG-014  | No type or field name may collide with built-in scalars (`Int`, `Float`, `String`, `Boolean`, `ID`) | — | — |

---

## 2. Required documentation

| Rule ID | Element                                           | Requirement |
| ------- | ------------------------------------------------- | ----------- |
| TG-100  | Every object, interface, union, enum, input, scalar, and directive **must** have a description string (`""" ... """`). Single-line `"..."` is allowed for trivial fields but block strings are preferred. |
| TG-101  | Every field on a public type **must** have a description. |
| TG-102  | Every argument on a field **must** have a description. |
| TG-103  | Every enum value **must** have a description (explain when it's emitted, not just restate the name). |
| TG-104  | Descriptions must be in English, sentence-case, and end with a period. |
| TG-105  | Descriptions **must not** simply restate the name (`"""The id of the property"""` on field `id` is rejected). |

The linter treats internal types prefixed with `_` (federation builtins) as exempt from
TG-100/101/102.

---

## 3. Mutation payload union pattern

Every mutation returns a **union** named `<MutationName>Payload`. The union members are one
success type plus one or more error types. Errors are part of the schema (errors-as-data),
**not** thrown via the top-level `errors` array.

```graphql
type Mutation {
  """Create a booking for a property."""
  createBooking(input: CreateBookingInput!): CreateBookingPayload!
}

"""Result of attempting to create a booking."""
union CreateBookingPayload =
    CreateBookingSuccess
  | PropertyNotFoundError
  | DatesUnavailableError
  | ValidationError

"""Successfully created booking."""
type CreateBookingSuccess {
  """The newly created booking."""
  booking: Booking!
}

"""The requested property does not exist."""
type PropertyNotFoundError implements UserFacingError {
  """Human-readable message."""
  message: String!
  """Stable machine-readable error code."""
  code: ErrorCode!
  """The property ID that could not be found."""
  propertyId: ID!
}

"""The requested date range is unavailable."""
type DatesUnavailableError implements UserFacingError {
  message: String!
  code: ErrorCode!
  """Specific dates within the requested range that are taken."""
  conflictingDates: [Date!]!
}

"""Input failed validation."""
type ValidationError implements UserFacingError {
  message: String!
  code: ErrorCode!
  """Per-field validation messages."""
  fieldErrors: [FieldError!]!
}
```

| Rule ID | Requirement |
| ------- | ----------- |
| TG-200  | Every `Mutation` field's return type **must** be a non-null union named `<MutationFieldNamePascalCase>Payload`. |
| TG-201  | A mutation payload union **must** contain exactly one success member and at least one error member. |
| TG-202  | The success member **must** be named `<MutationFieldNamePascalCase>Success`. |
| TG-203  | Every error member **must** implement the shared `UserFacingError` interface (defined below). |
| TG-204  | Mutations **must not** return a bare object type or a list. |
| TG-205  | Mutation arguments **must** consist of a single `input: <MutationFieldNamePascalCase>Input!` argument. |
| TG-206  | Idempotent mutations (everything in `booking-service`) **must** accept an `idempotencyKey: String!` field on their input type. |

### Shared error interface (defined in the platform-level federation directives bundle)

```graphql
"""An error that is safe to surface to end users."""
interface UserFacingError {
  """Human-readable message intended for end-user display."""
  message: String!
  """Stable machine-readable error code."""
  code: ErrorCode!
}
```

---

## 4. `@deprecated` reason format

Every `@deprecated` usage **must** include a structured `reason` argument. The reason is a
single line with the following pipe-separated fields:

```
@deprecated(reason: "since=YYYY-MM-DD | removeAfter=YYYY-MM-DD | replacement=<fieldOrType> | reason=<short prose>")
```

Example:

```graphql
type Property {
  """Legacy nightly price. Use pricing.basePrice instead."""
  pricePerNight: Money @deprecated(reason: "since=2026-01-15 | removeAfter=2026-07-15 | replacement=pricing.basePrice | reason=Moved to pricing-service for federation.")
}
```

| Rule ID | Requirement |
| ------- | ----------- |
| TG-300  | `@deprecated` **must** include a `reason` argument. |
| TG-301  | `reason` **must** match `^since=\d{4}-\d{2}-\d{2} \| removeAfter=\d{4}-\d{2}-\d{2} \| replacement=\S+ \| reason=.+$`. |
| TG-302  | `removeAfter` **must** be at least 90 days after `since` (safe-deprecation window). |
| TG-303  | A field may only be removed once two conditions are both true: (a) `removeAfter` has passed, and (b) field-usage analytics show zero calls in the trailing 30 days. The breaking-change checker enforces (a); CI enforces (b). |
| TG-304  | `replacement` **must** point to an existing field, type, or `none` if no replacement exists. |

---

## 5. Error codes in `extensions`

Even though business errors are returned as union members (Section 3), any **transport-level**
or **uncaught** errors that surface in the top-level `errors` array **must** carry a stable
error code in `extensions`.

| Rule ID | Requirement |
| ------- | ----------- |
| TG-400  | Every error in the top-level `errors` array **must** include `extensions.code` of type `ErrorCode`. |
| TG-401  | `extensions.code` values **must** be drawn from the `ErrorCode` enum below — no ad-hoc string codes. |
| TG-402  | Errors **must** include `extensions.traceId` (W3C trace ID propagated from the router). |
| TG-403  | Internal stack traces, SQL fragments, and remote URLs **must not** appear in `message` or `extensions` in production builds. |
| TG-404  | Subgraph-emitted errors **must** include `extensions.subgraph` (the subgraph name) so the router can attribute failures. |

### Canonical `ErrorCode` enum

```graphql
"""Stable machine-readable error code. Clients should branch on this, not on `message`."""
enum ErrorCode {
  """Caller is not authenticated."""
  UNAUTHENTICATED
  """Caller is authenticated but not authorized for this operation."""
  FORBIDDEN
  """The referenced resource does not exist."""
  NOT_FOUND
  """The request failed schema-level or domain validation."""
  VALIDATION_FAILED
  """The request conflicts with current state (e.g. duplicate idempotency key, dates taken)."""
  CONFLICT
  """The caller has exceeded their rate limit."""
  RATE_LIMITED
  """An upstream subgraph failed (timeout, 5xx, circuit open)."""
  UPSTREAM_UNAVAILABLE
  """Query exceeded depth or cost limits."""
  QUERY_TOO_COMPLEX
  """Persisted query hash was not found in the registry."""
  PERSISTED_QUERY_NOT_FOUND
  """Catch-all for unexpected internal failures."""
  INTERNAL_ERROR
}
```

---

## 6. Federation rules

Apollo Federation v2 is a **non-negotiable** of this platform (see `Context.md`). The rules below
enforce that.

| Rule ID | Requirement |
| ------- | ----------- |
| TG-500  | Every entity (any type referenced across subgraphs) **must** declare `id: ID!` as a field. |
| TG-501  | Every entity **must** declare at least one `@key(fields: "...")` directive. |
| TG-502  | If `@key` references multiple fields, they **must** all be non-null fields on the same type. |
| TG-503  | The owning subgraph of an entity **must** implement the `_entities` resolver for it. |
| TG-504  | Subgraphs that **extend** an entity (e.g. `pricing-service` extending `Property`) **must** use `extend type` plus `@key` matching the owning subgraph's primary key. |
| TG-505  | Extended types **must not** redefine fields owned by another subgraph; they may only add new fields. |
| TG-506  | A field that resolves with an N+1 risk **must** be implemented via a DataLoader (enforced by code review + integration test, not by the linter — but the linter flags `@key` fields that lack a corresponding loader registration in service code annotations). |
| TG-507  | The reserved field name `id` **must** be of type `ID!` and **must not** be marked `@deprecated`. |
| TG-508  | `@external`, `@requires`, and `@provides` **must** include a description comment on the field explaining why the cross-subgraph dependency exists. |
| TG-509  | Introspection **must** be disabled in production builds (`graphql.introspection.enabled=false`); persisted queries are the only contract. |

---

## 7. Schema hygiene

| Rule ID | Requirement |
| ------- | ----------- |
| TG-600  | No type or field may be `null`able and `@nonNull`-annotated simultaneously (consistency with the underlying domain). |
| TG-601  | List fields **must** be non-null at the list level (`[Foo!]!`) unless the empty/absent distinction is explicitly meaningful. |
| TG-602  | Every paginated field **must** follow the Relay Connection spec (`first`, `after`, `last`, `before`, `edges`, `pageInfo`). |
| TG-603  | `Date`, `DateTime`, `UUID`, and `Money` are platform scalars; subgraphs **must not** redefine them. |
| TG-604  | Any field returning monetary values **must** use `Money`, never `Float` or `String`. |
| TG-605  | Internal/admin fields **must** be guarded with `@requiresAuth(role: ADMIN)` and **must not** be exposed in the public supergraph. |
| TG-606  | Every type and field added or removed **must** survive the breaking-change checker; if a removal is intended, follow the Section 4 deprecation window first. |

---

## 8. Linter implementation notes (Phase 7.2)

The linter in Phase 7.2 must:

1. Parse each subgraph SDL using the same GraphQL parser the router uses (single source of truth).
2. Walk the AST and emit a diagnostic for each rule violation, keyed by rule ID.
3. Treat all `TG-*` rules above as **errors that block CI** unless explicitly downgraded in
   `schema-registry/lint-config.yml`.
4. Output diagnostics in both human-readable and SARIF format (so GitHub Actions can annotate PRs).
5. Run **before** the breaking-change checker — a schema that fails linting never reaches the
   composer.

The AI schema review assistant (Phase 10) **never** blocks CI; it may suggest improvements that
fall outside the deterministic rule set above (e.g. naming clarity, missing examples in
descriptions, unclear deprecation prose), but only the linter and breaking-change checker can
fail the build.

---

## 9. Versioning

This document is versioned alongside the schemas. Changes that tighten a rule require a
deprecation window equivalent to the Section 4 schema rules; changes that loosen a rule may ship
immediately. Every change must update the `Changelog` below.

### Changelog

- **2026-05-05** — Initial version (Phase 0.3).
