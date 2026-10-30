-- Apply before starting an existing PostgreSQL database with ddl-auto=validate.
ALTER TABLE diagnostic ADD COLUMN IF NOT EXISTS ai_run_request_id varchar(255);
ALTER TABLE diagnostic ADD COLUMN IF NOT EXISTS ai_run_status varchar(255) DEFAULT 'IDLE';
ALTER TABLE diagnostic ADD COLUMN IF NOT EXISTS ai_run_error_code varchar(255);
ALTER TABLE diagnostic ADD COLUMN IF NOT EXISTS ai_run_result_id bigint;
ALTER TABLE diagnostic ADD COLUMN IF NOT EXISTS ai_run_expires_at timestamp(6);
