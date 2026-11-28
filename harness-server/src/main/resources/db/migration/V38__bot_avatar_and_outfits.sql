ALTER TABLE harness_bot ADD COLUMN avatar_path VARCHAR(255) NOT NULL DEFAULT '';
ALTER TABLE harness_bot ADD COLUMN outfit_json VARCHAR(512) NOT NULL DEFAULT '[]';
ALTER TABLE harness_bot ADD COLUMN hidden BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE harness_bot SET outfit_json = '["goldCrown"]' WHERE is_main = TRUE AND outfit_json = '[]';
