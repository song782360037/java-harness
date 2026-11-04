-- Prompts are limited only by the model context window, not by a fixed column length.
ALTER TABLE harness_run ALTER COLUMN prompt CLOB NOT NULL;
ALTER TABLE harness_automation ALTER COLUMN prompt CLOB NOT NULL;
