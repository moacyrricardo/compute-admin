-- spec-035: per-(machine, discoverer family) discovery enablement.
-- Backs DiscoveryEnablement. H2 dialect, ddl-auto is none so the table is created
-- here. Only deviations from a family's account-level default are stored; a machine
-- with no row for a family falls back to that default (docker off, everything else
-- on), so this table is empty until an operator toggles a family. This supersedes
-- the interim `ca.discovery.docker.enabled` flag (spec-033): docker discovery is now
-- gated per machine by the absence/`enabled=false` of a DOCKER row, not a global
-- property. Not @Audited (an operational capability toggle) — no `_aud` table.

CREATE TABLE discovery_enablement (
    id         VARCHAR(36) NOT NULL,
    machine_id VARCHAR(36) NOT NULL,
    family     VARCHAR(20) NOT NULL,
    enabled    BOOLEAN     NOT NULL,
    created_at TIMESTAMP   NOT NULL,
    updated_at TIMESTAMP   NOT NULL,
    CONSTRAINT pk_discovery_enablement PRIMARY KEY (id),
    CONSTRAINT uq_discovery_enablement_machine_family UNIQUE (machine_id, family),
    CONSTRAINT fk_discovery_enablement_machine FOREIGN KEY (machine_id) REFERENCES machine (id)
);

CREATE INDEX idx_discovery_enablement_machine ON discovery_enablement (machine_id);
