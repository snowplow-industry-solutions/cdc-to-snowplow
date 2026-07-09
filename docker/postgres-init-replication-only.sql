-- Scaffold-demo bootstrap. Selected via the PG_INIT env var (see docker-compose.yml);
-- the default demo uses docker/postgres-init.sql instead.
--
-- Runs as the postgres superuser on first boot. Grants REPLICATION to the 'cdc' user
-- but creates NO tables: the scaffold demo shapes the schema by hand via docker/seed.sql,
-- so `scaffold` introspects a database you built yourself. See README "Scaffold demo".
ALTER USER cdc WITH REPLICATION;
