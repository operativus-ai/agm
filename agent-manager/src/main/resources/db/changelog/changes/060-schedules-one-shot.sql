--liquibase formatted sql

--changeset agm:060-schedules-one-shot
-- One-shot schedules (T037-3): when one_shot = true, ScheduleExecutionPoller flips
-- is_active=false on the same transaction as the first dispatch, so the schedule
-- fires exactly once and never re-fires on subsequent ticks. Default false preserves
-- the existing recurring-schedule semantics for every row that pre-dates this column.
ALTER TABLE schedules ADD COLUMN one_shot BOOLEAN NOT NULL DEFAULT FALSE;
--rollback ALTER TABLE schedules DROP COLUMN one_shot;
