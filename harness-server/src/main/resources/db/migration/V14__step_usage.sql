-- Provider-reported usage per model step; NULL when the service did not report it.
ALTER TABLE harness_step ADD COLUMN input_tokens INT;
ALTER TABLE harness_step ADD COLUMN output_tokens INT;
ALTER TABLE harness_step ADD COLUMN cached_tokens INT;
ALTER TABLE harness_step ADD COLUMN generation_millis BIGINT;
