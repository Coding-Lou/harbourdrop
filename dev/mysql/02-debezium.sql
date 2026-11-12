-- Least-privilege account for the Debezium MySQL connector (local development only).
-- Idempotent: also executed by the cdc-user-init service against existing data volumes.
CREATE USER IF NOT EXISTS 'debezium'@'%' IDENTIFIED BY 'hmdp_debezium_local_only';
GRANT SELECT, RELOAD, SHOW DATABASES, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'debezium'@'%';
FLUSH PRIVILEGES;
