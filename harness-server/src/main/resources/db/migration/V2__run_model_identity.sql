ALTER TABLE harness_run ADD COLUMN provider VARCHAR(32) NOT NULL DEFAULT 'simulated';
ALTER TABLE harness_run ADD COLUMN model VARCHAR(128) NOT NULL DEFAULT 'local-simulator';
