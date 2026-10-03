-- One database, one schema per owning service (see docs/architecture/ARCHITECTURE.md).
-- Each service migrates only its own schema with Flyway.
CREATE SCHEMA IF NOT EXISTS broker;
CREATE SCHEMA IF NOT EXISTS certification;
CREATE SCHEMA IF NOT EXISTS workflow;
CREATE SCHEMA IF NOT EXISTS knowledge;
