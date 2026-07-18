--liquibase formatted sql

--changeset agm:093-routing-decisions-message-hash-varchar runOnChange:false
--comment: Align routing_decisions.message_hash with the JPA entity declaration.
--         RoutingDecisionEntity maps the column with @Column length=64 which
--         Hibernate ddl-auto=validate expects as VARCHAR(64). Changeset 089
--         created it as CHAR(64), stored as Postgres bpchar. Schema validation
--         fails the boot with a type mismatch. ALTER to VARCHAR so the column
--         matches the JPA mapping.
ALTER TABLE routing_decisions ALTER COLUMN message_hash TYPE VARCHAR(64);
