# Open Foundry Java

Open Foundry is a domain-neutral Java foundation for object, relationship, temporal state, governed actions, authorization, audit, events and synchronization. Business concepts arrive through Domain Packs; the platform does not contain a specific government or healthcare workflow.

## Current implementation boundary

This is a partial Java remake of `syzygyhack/open-foundry`, not yet feature-equivalent to upstream v0.3.0. The original v0.1 checklist is superseded by the [parity repair plan](spec/upstream-parity-plan.md). Arbitrary retrospective interval corrections, advanced queries and full ODL remain unfinished. Actions now persist side-effect continuations, retry policies and version-checked local compensation; see [execution and recovery semantics](spec/adr/0012-durable-action-side-effects.md). Production scheduling, operations and real external integrations remain to be verified. An explicit ontology-target authorization mode now supports the original Library OpenFGA model, verified against a real OpenFGA server; see [integration setup](spec/openfga-integration.md). Pack bundles now compose dependency-visible types, load field policies and declared assets, and initialize seeds explicitly with transactional receipts; see [bundle setup](spec/pack-bundles.md). FGA model compilation/deployment, tuple lifecycle and OIDC/service authentication remain incomplete. LAZY countLinks computed fields now execute on reads with protected API projections; see [computed semantics](spec/adr/0015-lazy-computed-fields.md). Declared relationship fields now support governed bidirectional navigation and terminated-link projections through GraphQL and REST; see [navigation semantics](spec/adr/0009-declared-relationship-navigation.md). Literal defaults, managed readonly audit fields, field/type CEL constraints and ordinary interface inheritance now execute for object and link writes; see [declaration semantics](spec/adr/0008-declarative-properties-and-interfaces.md) for limits and migration behavior. Required/type/enum/unique/immutable checks now apply to object and link writes; see [property validation migration](spec/property-validation-migration.md) before upgrading existing data. Ordinary temporal transitions and ordered late facts now use format-2 history; [legacy history migration boundaries](spec/temporal-migration.md) must be reviewed before upgrade.

Object queries now support schema-validated filters and ordering, visibility-aware pagination/counts, additive GraphQL connections and REST queries; see [query semantics and limits](spec/adr/0017-governed-object-queries.md). Governed COUNT/SUM/AVG/MIN/MAX, grouping and result pagination now use the same visibility boundary; see [aggregate contracts](spec/adr/0018-governed-aggregations.md). Text search now exposes explicit upstream term/phrase modes with visible-only scores, highlights and pagination; see [search semantics](spec/adr/0019-governed-search.md). GraphQL plural queries now default to upstream-style connections with forward/backward cursor pagination; explicit legacy lists remain available. See [pagination and migration](spec/adr/0020-connection-pagination.md). Native storage search/aggregation, database pushdown and consistent multi-read snapshots remain incomplete.

The generic API now requires trusted schema/manifest registration for Actions, explicit permission relations and matching tenant/actor context. GraphQL generates typed Action inputs/results with enum and platform-scalar validation; an explicit legacy JSON mode remains available. See [typed Action compatibility](spec/adr/0016-typed-action-api.md). Direct ActionExecutor use requires an explicit authorization policy; default execution is denied. Registered reads hide sensitive fields by default and apply role-based field policies to current and historical values. Actions support transaction-scoped filtered relationship deletion with concrete-target authorization and replay checks; see [selection semantics](spec/adr/0010-transactional-link-selection.md). Actions now prefer transactional command receipts: JDBC persists them with effects, audit and outbox; in-memory providers remain process-local. See [command receipts](spec/command-receipts.md) for recovery and upgrade boundaries. Outbox delivery and consumer completion receipts now use expiring claims and ownership tokens; see [event recovery and legacy receipt review](spec/adr/0011-leased-event-delivery.md). This is at-least-once delivery, not exactly-once external side effects. See [ADR-0004](spec/adr/0004-upstream-parity-and-governed-boundary.md) for API and OpenFGA tuple migration requirements.

## Build

The project targets Java 21 and is verified with newer JDKs.

```bash
mvn test
```

## Modules

- `foundry-spi`: stable object, link, history, transaction and storage contracts.
- `foundry-schema`: ODL parsing, validation, schema diff and version registry.
- `foundry-validation`: shared declaration validation and CEL property constraints for storage providers.
- `foundry-storage-memory`: in-memory provider and local test baseline.
- `foundry-storage-jdbc`: relational provider with temporal history and database dialects.
- `foundry-actions`: Action manifests, CEL preconditions, transactions, idempotency, durable side effects and compensation.
- `foundry-pack`: Dependency-aware ontology composition, Pack assets, field policies and transactional seed initialization.
- `foundry-events`: audit, transactional outbox, CloudEvents and deduplication.
- `foundry-security`: OIDC/JWT, OpenFGA adapter and field policies.
- `foundry-sync`: JDBC/REST connectors, mappings, provenance and materialized sync.
- `foundry-api`: shared application service, GraphQL runtime and JDK REST adapter.
- `foundry-conformance`: provider consistency tests.

Foundation specifications live under [`spec/`](spec/). A separate business repository can depend on released Foundry artifacts and provide its own Domain Pack.

## Database targets

PostgreSQL is available for local integration testing. openGauss is the first domestic database target, with Kingbase and Dameng dialect entry points. A target database instance and its approved JDBC driver are required before claiming production support.
