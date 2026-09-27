# OpenFGA integration fixture

library-model.json was generated from the unmodified syzygyhack/open-foundry v0.3.0 (`1d7e1aa62208d32ed91d358f4503cf2d95523a7e`) `examples/library-pack/permissions/library-roles.fga` using the official `@openfga/syntax-transformer@0.2.2`, `transformer.transformDSLToJSONObject`.

DSL SHA-256: `20b1cbc759be52a8bbe58fea46ddebe6b892ad2db22f8184bbfff469809bbde1`.
JSON SHA-256: `c204f2c37849ba13dfe9a2699b96bca4b839c864871b1195d08ac235f7af28f6`.

Native integration tests write isolated stores to a loopback server and delete them afterwards. The normal test build excludes `*IT`; enable `-Popenfga-integration` and set `FOUNDRY_OPENFGA_URL`. Missing external configuration is a failure in that profile, not a skipped or simulated pass.

Upstream model license: [Apache-2.0](../upstream-v0.3.0/LICENSE).
