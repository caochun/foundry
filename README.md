# Open Foundry Java

Open Foundry is a domain-neutral Java foundation for object, relationship, temporal state, governed actions, authorization, audit, events and synchronization. Business concepts arrive through Domain Packs; the platform does not contain a specific government or healthcare workflow.

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
