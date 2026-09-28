package org.openfoundry.foundation.storage.jdbc;

/** Database-specific spellings kept outside the storage engine. */
public interface DatabaseDialect {
    String name();

    String textType();

    String timestampType();

    default String paginationClause() {
        return " LIMIT ? OFFSET ?";
    }

    default String currentTablesDdl() {
        String text = textType();
        String timestamp = timestampType();
        return """
                CREATE TABLE IF NOT EXISTS of_write_guards (
                  tenant_id VARCHAR(255) PRIMARY KEY
                );
                CREATE TABLE IF NOT EXISTS of_objects (
                  tenant_id VARCHAR(255) NOT NULL,
                  object_type VARCHAR(255) NOT NULL,
                  object_id VARCHAR(512) NOT NULL,
                  version BIGINT NOT NULL,
                  created_at %s NOT NULL,
                  updated_at %s NOT NULL,
                  deleted_at %s NULL,
                  last_transaction_id VARCHAR(255),
                  last_action_id VARCHAR(255),
                  properties_json %s NOT NULL,
                  PRIMARY KEY (tenant_id, object_type, object_id)
                );
                CREATE INDEX IF NOT EXISTS idx_of_objects_type ON of_objects (tenant_id, object_type, object_id);
                CREATE TABLE IF NOT EXISTS of_object_history (
                  tenant_id VARCHAR(255) NOT NULL,
                  object_type VARCHAR(255) NOT NULL,
                  object_id VARCHAR(512) NOT NULL,
                  version BIGINT NOT NULL,
                  operation VARCHAR(32) NOT NULL,
                  temporal_format INTEGER NOT NULL DEFAULT 1,
                  valid_from %s NOT NULL,
                  valid_to %s NULL,
                  recorded_at %s NOT NULL,
                  transaction_id VARCHAR(255) NOT NULL,
                  action_id VARCHAR(255),
                  actor_id VARCHAR(255),
                  source_system VARCHAR(255),
                  state_json %s NOT NULL,
                  PRIMARY KEY (tenant_id, object_type, object_id, version)
                );
                CREATE INDEX IF NOT EXISTS idx_of_object_history_time
                  ON of_object_history (tenant_id, object_type, object_id, valid_from, recorded_at);
                CREATE TABLE IF NOT EXISTS of_links (
                  tenant_id VARCHAR(255) NOT NULL,
                  link_type VARCHAR(255) NOT NULL,
                  link_id VARCHAR(512) NOT NULL,
                  from_type VARCHAR(255) NOT NULL,
                  from_id VARCHAR(512) NOT NULL,
                  to_type VARCHAR(255) NOT NULL,
                  to_id VARCHAR(512) NOT NULL,
                  version BIGINT NOT NULL,
                  created_at %s NOT NULL,
                  updated_at %s NOT NULL,
                  deleted_at %s NULL,
                  valid_from %s NOT NULL,
                  valid_to %s NULL,
                  last_transaction_id VARCHAR(255),
                  last_action_id VARCHAR(255),
                  properties_json %s NOT NULL,
                  PRIMARY KEY (tenant_id, link_type, link_id)
                );
                CREATE INDEX IF NOT EXISTS idx_of_links_from
                  ON of_links (tenant_id, link_type, from_type, from_id);
                CREATE INDEX IF NOT EXISTS idx_of_links_to
                  ON of_links (tenant_id, link_type, to_type, to_id);
                CREATE TABLE IF NOT EXISTS of_link_history (
                  tenant_id VARCHAR(255) NOT NULL,
                  link_type VARCHAR(255) NOT NULL,
                  link_id VARCHAR(512) NOT NULL,
                  from_type VARCHAR(255) NOT NULL,
                  from_id VARCHAR(512) NOT NULL,
                  to_type VARCHAR(255) NOT NULL,
                  to_id VARCHAR(512) NOT NULL,
                  version BIGINT NOT NULL,
                  operation VARCHAR(32) NOT NULL,
                  temporal_format INTEGER NOT NULL DEFAULT 1,
                  valid_from %s NOT NULL,
                  valid_to %s NULL,
                  recorded_at %s NOT NULL,
                  transaction_id VARCHAR(255) NOT NULL,
                  action_id VARCHAR(255),
                  actor_id VARCHAR(255),
                  source_system VARCHAR(255),
                  state_json %s NOT NULL,
                  PRIMARY KEY (tenant_id, link_type, link_id, version)
                );
                CREATE INDEX IF NOT EXISTS idx_of_link_history_time
                  ON of_link_history (tenant_id, link_type, link_id, valid_from, recorded_at);
                CREATE TABLE IF NOT EXISTS of_audit_records (
                  id VARCHAR(255) PRIMARY KEY,
                  tenant_id VARCHAR(255) NOT NULL,
                  timestamp_value %s NOT NULL,
                  actor_id VARCHAR(255),
                  operation_type VARCHAR(64) NOT NULL,
                  object_type VARCHAR(255), object_id VARCHAR(512), action_type VARCHAR(255),
                  transaction_id VARCHAR(255), result VARCHAR(32) NOT NULL,
                  detail_json %s NOT NULL
                );
                CREATE INDEX IF NOT EXISTS idx_of_audit_object
                  ON of_audit_records (tenant_id, object_type, object_id, timestamp_value);
                CREATE TABLE IF NOT EXISTS of_outbox_events (
                  id VARCHAR(255) PRIMARY KEY,
                  tenant_id VARCHAR(255) NOT NULL,
                  type VARCHAR(255) NOT NULL,
                  subject VARCHAR(512),
                  occurred_at %s NOT NULL,
                  transaction_id VARCHAR(255),
                  data_json %s NOT NULL,
                  published_at %s NULL
                );
                CREATE INDEX IF NOT EXISTS idx_of_outbox_pending
                  ON of_outbox_events (tenant_id, published_at, occurred_at);
                CREATE TABLE IF NOT EXISTS of_command_receipts (
                  tenant_id VARCHAR(255) NOT NULL,
                  receipt_key VARCHAR(64) NOT NULL,
                  actor_id VARCHAR(255) NOT NULL,
                  action_name VARCHAR(255) NOT NULL,
                  request_hash VARCHAR(64) NOT NULL,
                  result_json %s NOT NULL,
                  PRIMARY KEY (tenant_id, receipt_key)
                );
                CREATE TABLE IF NOT EXISTS of_consumed_events (
                  event_id VARCHAR(255) PRIMARY KEY,
                  consumed_at %s NOT NULL
                );
                """.formatted(timestamp, timestamp, timestamp, text,
                        timestamp, timestamp, timestamp, text,
                        timestamp, timestamp, timestamp, timestamp, timestamp, text,
                        timestamp, timestamp, timestamp, text,
                        timestamp, text, timestamp, text, timestamp, text, timestamp) + """
                CREATE TABLE IF NOT EXISTS of_action_executions (
                  tenant_id VARCHAR(255) NOT NULL, execution_id VARCHAR(255) NOT NULL,
                  actor_id VARCHAR(255) NOT NULL, action_name VARCHAR(255) NOT NULL,
                  version BIGINT NOT NULL, status VARCHAR(64) NOT NULL, available_at %s,
                  state_json %s NOT NULL, PRIMARY KEY (tenant_id, execution_id)
                );
                CREATE INDEX IF NOT EXISTS idx_of_action_executions_due
                  ON of_action_executions (tenant_id, actor_id, available_at);
                """.formatted(timestamp, text) + """
                CREATE TABLE IF NOT EXISTS of_field_lineage (
                  tenant_id VARCHAR(255) NOT NULL, entity_type VARCHAR(255) NOT NULL, entity_id VARCHAR(512) NOT NULL,
                  lineage_seq BIGINT NOT NULL, field_name VARCHAR(512) NOT NULL, entity_version BIGINT NOT NULL,
                  value_present BOOLEAN NOT NULL, value_hash VARCHAR(64) NOT NULL, recorded_at %s NOT NULL,
                  transaction_id VARCHAR(255) NOT NULL, actor_id VARCHAR(255), source_json %s NOT NULL,
                  PRIMARY KEY (tenant_id, entity_type, entity_id, lineage_seq)
                );
                CREATE INDEX IF NOT EXISTS idx_of_lineage_field ON of_field_lineage (tenant_id, entity_type, entity_id, field_name, lineage_seq);
                """.formatted(timestamp, text) + """
                CREATE TABLE IF NOT EXISTS of_ingestion_receipts (
                  tenant_id VARCHAR(255) NOT NULL, receipt_key VARCHAR(64) NOT NULL, request_hash VARCHAR(64) NOT NULL,
                  target_type VARCHAR(255) NOT NULL, target_id VARCHAR(512) NOT NULL, actor_id VARCHAR(255),
                  result_json %s NOT NULL, PRIMARY KEY (tenant_id, receipt_key)
                );
                CREATE TABLE IF NOT EXISTS of_ingestion_checkpoints (
                  tenant_id VARCHAR(255) NOT NULL, checkpoint_key VARCHAR(64) NOT NULL, version BIGINT NOT NULL,
                  source_sequence BIGINT NOT NULL, token_json %s NOT NULL, source_system VARCHAR(255) NOT NULL,
                  configuration_hash VARCHAR(64) NOT NULL, PRIMARY KEY (tenant_id, checkpoint_key)
                );
                """.formatted(text, text);
    }

    static DatabaseDialect h2() {
        return new SimpleDialect("h2", "CLOB", "TIMESTAMP WITH TIME ZONE");
    }

    static DatabaseDialect postgresql() {
        return new SimpleDialect("postgresql", "TEXT", "TIMESTAMPTZ");
    }

    static DatabaseDialect openGauss() {
        return new SimpleDialect("openGauss", "TEXT", "TIMESTAMPTZ");
    }

    static DatabaseDialect kingbase() {
        return new SimpleDialect("kingbase", "TEXT", "TIMESTAMPTZ");
    }

    static DatabaseDialect dameng() {
        return new SimpleDialect("dameng", "CLOB", "TIMESTAMP");
    }

    static DatabaseDialect standard(String name, String textType, String timestampType) {
        return new SimpleDialect(name, textType, timestampType);
    }

    record SimpleDialect(String name, String textType, String timestampType) implements DatabaseDialect {
        public SimpleDialect {
            if (name == null || name.isBlank() || textType == null || textType.isBlank()
                    || timestampType == null || timestampType.isBlank()) {
                throw new IllegalArgumentException("dialect values must not be blank");
            }
        }
    }
}
