-- =============================================================================
-- V1__create_initial_schema.sql
-- Flyway Migration: Version 1 — Initial Database Schema
-- =============================================================================
--
-- WHY Flyway versioned migrations?
-- Every DB change is a versioned, tracked script. Flyway runs each script ONCE.
-- It tracks executed scripts in the "flyway_schema_history" table.
-- If the script was already run, Flyway skips it. If not, it runs it.
--
-- Naming convention: V{version}__{description}.sql
-- V = version prefix (required)
-- {version} = integer or timestamp (1, 2, 3 or 20240101_1200)
-- __ = double underscore (required separator)
-- {description} = snake_case description
--
-- Interview: "How do you handle DB schema changes in a team of 10 engineers?"
-- → Flyway. Each engineer creates a new migration file. CI/CD applies them in order.
-- → Cherry-pick from git and apply to any environment instantly.
-- =============================================================================


-- =============================================================================
-- TABLE: users
-- Created first because incidents.created_by references users
-- =============================================================================
CREATE TABLE IF NOT EXISTS users (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    -- WHY uuid_generate_v4()? Generates random UUID at DB level.
    -- If Java fails to set it, DB provides the default. Defence in depth.

    email           VARCHAR(255) NOT NULL UNIQUE,
    password_hash   VARCHAR(255) NOT NULL,
    first_name      VARCHAR(100),
    last_name       VARCHAR(100),
    role            VARCHAR(50)  NOT NULL DEFAULT 'ROLE_VIEWER',
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    last_login_at   TIMESTAMP
);

COMMENT ON TABLE users IS 'System users with role-based access control';
COMMENT ON COLUMN users.password_hash IS 'BCrypt hash. NEVER store plaintext passwords.';
-- WHY COMMENT ON?
-- Self-documenting schema. When a new engineer runs \d+ users in psql,
-- they understand the schema without reading application code. Production practice.


-- =============================================================================
-- TABLE: incidents
-- Core table — all investigation data links here
-- =============================================================================
CREATE TABLE IF NOT EXISTS incidents (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    title               VARCHAR(500) NOT NULL,
    description         TEXT,
    status              VARCHAR(50)  NOT NULL DEFAULT 'OPEN',
    severity            VARCHAR(20)  NOT NULL,
    service_name        VARCHAR(255) NOT NULL,
    environment         VARCHAR(50)  DEFAULT 'production',
    rca_summary         TEXT,
    -- WHY TEXT for rca_summary?
    -- AI-generated RCA can be thousands of words. VARCHAR(500) would truncate it.
    resolution_notes    TEXT,
    resolved_at         TIMESTAMP,
    closed_at           TIMESTAMP,
    created_by          VARCHAR(255) NOT NULL DEFAULT 'SYSTEM',
    created_at          TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP   NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE incidents IS 'Core incident records. Each incident represents a declared production problem.';
COMMENT ON COLUMN incidents.status IS 'State machine: OPEN → INVESTIGATING → RESOLVED → CLOSED';
COMMENT ON COLUMN incidents.rca_summary IS 'AI-generated root cause analysis populated after agent pipeline completes';

-- WHY CHECK constraint on status?
-- Database-level validation. Even if application has a bug and sends invalid status,
-- the DB will reject it. Defence in depth.
ALTER TABLE incidents
    ADD CONSTRAINT chk_incident_status
    CHECK (status IN ('OPEN', 'INVESTIGATING', 'RESOLVED', 'CLOSED'));

ALTER TABLE incidents
    ADD CONSTRAINT chk_incident_severity
    CHECK (severity IN ('SEV1', 'SEV2', 'SEV3', 'SEV4'));


-- =============================================================================
-- TABLE: alerts
-- Raw signals from monitoring systems
-- =============================================================================
CREATE TABLE IF NOT EXISTS alerts (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    incident_id     UUID NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
    -- WHY ON DELETE CASCADE?
    -- If an incident is deleted, delete its alerts too. No orphaned records.
    -- In production: incidents are rarely deleted (soft delete preferred).

    title           VARCHAR(500) NOT NULL,
    description     TEXT,
    severity        VARCHAR(20)  NOT NULL,
    source          VARCHAR(100) NOT NULL,
    -- source = "prometheus", "datadog", "pagerduty", "custom"

    service_name    VARCHAR(255) NOT NULL,
    environment     VARCHAR(50),
    fingerprint     VARCHAR(64)  NOT NULL,
    -- fingerprint = SHA-256 of (source + service_name + alert_type)
    -- Used for deduplication within a time window

    raw_payload     JSONB,
    -- WHY JSONB over JSON?
    -- JSON: stored as text, parsed on every read
    -- JSONB: stored as binary, indexed with GIN index, faster reads/queries
    -- GIN index enables: WHERE raw_payload @> '{"alertname": "HighCPU"}'::jsonb

    fired_at        TIMESTAMP,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE alerts IS 'Raw alert signals from monitoring systems. Multiple alerts can map to one incident.';
COMMENT ON COLUMN alerts.fingerprint IS 'SHA-256 hash for deduplication. Prevents alert storms from creating duplicate incidents.';


-- =============================================================================
-- TABLE: investigation_results
-- Stores each AI agent's output
-- =============================================================================
CREATE TABLE IF NOT EXISTS investigation_results (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    incident_id         UUID NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
    agent_type          VARCHAR(100) NOT NULL,
    findings            TEXT,
    reasoning           TEXT,
    -- WHY store reasoning?
    -- "Explainable AI" — users can understand why the AI reached a conclusion.
    -- Critical for trust in enterprise AI systems.

    confidence_score    DECIMAL(3,2),
    -- DECIMAL(3,2): values 0.00 to 1.00
    -- Precision = 3 total digits, Scale = 2 decimal places

    execution_time_ms   BIGINT,
    model_used          VARCHAR(100),
    tokens_used         INTEGER,
    created_at          TIMESTAMP NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE investigation_results IS 'Per-agent AI investigation outputs. Enables audit and explainability of AI decisions.';


-- =============================================================================
-- TABLE: audit_logs
-- Immutable audit trail for compliance
-- =============================================================================
CREATE TABLE IF NOT EXISTS audit_logs (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    entity_type     VARCHAR(100) NOT NULL,
    entity_id       VARCHAR(255) NOT NULL,
    action          VARCHAR(100) NOT NULL,
    old_value       TEXT,
    new_value       TEXT,
    performed_by    VARCHAR(255),
    ip_address      VARCHAR(45),
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
    -- WHY no updated_at? Audit logs are IMMUTABLE. Never update, never delete.
);

COMMENT ON TABLE audit_logs IS 'Immutable audit trail. INSERT only. Required for SOC2/HIPAA compliance.';


-- =============================================================================
-- TABLE: knowledge_documents
-- RAG source documents (runbooks, SOPs, past incident reports)
-- =============================================================================
CREATE TABLE IF NOT EXISTS knowledge_documents (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    title           VARCHAR(500) NOT NULL,
    content         TEXT NOT NULL,
    document_type   VARCHAR(100) NOT NULL DEFAULT 'RUNBOOK',
    -- Types: RUNBOOK, INCIDENT_REPORT, SOP, ARCHITECTURE_DOC

    service_name    VARCHAR(255),
    tags            TEXT[],
    -- WHY TEXT[]? PostgreSQL native array. Store tags without a join table.
    -- Query: WHERE 'kafka' = ANY(tags)

    created_by      VARCHAR(255),
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE knowledge_documents IS 'Source documents for RAG. Runbooks, SOPs, and past incidents ingested by Knowledge Base Agent.';


-- =============================================================================
-- INDEXES
-- Covering the most common query patterns
-- =============================================================================

-- Incidents: most common queries are by status (dashboard) and service (filter)
CREATE INDEX idx_incidents_status     ON incidents(status);
CREATE INDEX idx_incidents_severity   ON incidents(severity);
CREATE INDEX idx_incidents_service    ON incidents(service_name);
CREATE INDEX idx_incidents_created_at ON incidents(created_at DESC);
-- WHY DESC on created_at? "Latest incidents first" is the most common sort.
-- Index stores data sorted DESC → no sort operation needed at query time.

-- Alerts: deduplication + incident lookup
CREATE INDEX idx_alerts_incident_id  ON alerts(incident_id);
CREATE INDEX idx_alerts_fingerprint  ON alerts(fingerprint);
CREATE INDEX idx_alerts_created_at   ON alerts(created_at DESC);

-- GIN index on JSONB payload: enables JSON field queries
CREATE INDEX idx_alerts_raw_payload  ON alerts USING GIN(raw_payload);
-- WHY GIN (Generalized Inverted Index)?
-- Standard B-tree doesn't work on JSONB. GIN creates an inverted index on all JSON keys/values.
-- Enables: WHERE raw_payload @> '{"service": "checkout"}' — fast lookups in JSON.

-- Audit logs: compliance team queries by entity or actor
CREATE INDEX idx_audit_entity_id     ON audit_logs(entity_id);
CREATE INDEX idx_audit_performed_by  ON audit_logs(performed_by);
CREATE INDEX idx_audit_created_at    ON audit_logs(created_at DESC);

-- Investigation results: lookup all agent results for an incident
CREATE INDEX idx_inv_results_incident ON investigation_results(incident_id);

-- Knowledge documents: full text search using tsvector
CREATE INDEX idx_knowledge_docs_text ON knowledge_documents
    USING GIN(to_tsvector('english', title || ' ' || content));
-- WHY tsvector GIN index?
-- PostgreSQL built-in full-text search. to_tsvector converts text to searchable tokens.
-- Cheaper than Elasticsearch for simple document search.
-- We use BOTH — this for simple search, Elasticsearch for advanced analytics.


-- =============================================================================
-- SEED DATA
-- Default admin user and sample data for development
-- =============================================================================

-- Default admin user (password: Admin@123456)
-- BCrypt hash generated with: BCrypt.hashpw("Admin@123456", BCrypt.gensalt(12))
INSERT INTO users (email, password_hash, first_name, last_name, role)
VALUES (
    'admin@incidentplatform.com',
    '$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewjFzDZ/GtyLhFau',
    'Admin',
    'User',
    'ROLE_ADMIN'
) ON CONFLICT (email) DO NOTHING;
-- WHY ON CONFLICT DO NOTHING?
-- Flyway may run on a DB that already has the seed data (e.g., after rollback + re-run).
-- This makes the migration idempotent — safe to run multiple times.
-- Interview: "What is idempotency?" → Running N times = same result as running once.
