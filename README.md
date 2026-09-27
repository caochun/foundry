# Open Foundry Java

Open Foundry is a domain-neutral Java foundation for object, relationship, temporal state, governed actions, authorization, audit, events and synchronization. Business concepts arrive through Domain Packs; the platform does not contain a specific government or healthcare workflow.

## Current implementation boundary

This is a partial Java remake of `syzygyhack/open-foundry`, not yet feature-equivalent to upstream v0.3.0. The original v0.1 checklist is superseded by the [parity repair plan](spec/upstream-parity-plan.md). Default/readonly/CEL property semantics, arbitrary retrospective interval corrections, durable Action idempotency, advanced queries and full ODL remain unfinished. Required/type/enum/unique/immutable checks now apply to object and link writes; see [property validation migration](spec/property-validation-migration.md) before upgrading existing data. Ordinary temporal transitions and ordered late facts now use format-2 history; [legacy history migration boundaries](spec/temporal-migration.md) must be reviewed before upgrade.

The generic API now requires trusted schema/manifest registration for Actions, explicit permission relations and matching tenant/actor context. Direct ActionExecutor use requires an explicit authorization policy; default execution is denied. Registered reads hide sensitive fields by default and apply role-based field policies to current and historical values. The in-memory idempotency store is process-local only. See [ADR-0004](spec/adr/0004-upstream-parity-and-governed-boundary.md) for API and OpenFGA tuple migration requirements.

## Build

The project targets Java 21 and is verified with newer JDKs.

```bash
mvn test
```

## Modules

- `foundry-spi`: stable object, link, history, transaction and storage contracts.
- `foundry-schema`: ODL parsing, validation, schema diff and version registry.
- `foundry-storage-memory`: in-memory provider and local test baseline.
- `foundry-storage-jdbc`: relational provider with temporal history and database dialects.
- `foundry-actions`: Action manifests, CEL preconditions, transactions and idempotency.
- `foundry-pack`: Domain Pack loading and dependency validation.
- `foundry-events`: audit, transactional outbox, CloudEvents and deduplication.
- `foundry-security`: OIDC/JWT, OpenFGA adapter and field policies.
- `foundry-sync`: JDBC/REST connectors, mappings, provenance and materialized sync.
- `foundry-api`: shared application service, GraphQL runtime and JDK REST adapter.
- `foundry-conformance`: provider consistency tests.

Foundation specifications live under [`spec/`](spec/). A separate business repository can depend on released Foundry artifacts and provide its own Domain Pack.

## Database targets

PostgreSQL is available for local integration testing. openGauss is the first domestic database target, with Kingbase and Dameng dialect entry points. A target database instance and its approved JDBC driver are required before claiming production support.
