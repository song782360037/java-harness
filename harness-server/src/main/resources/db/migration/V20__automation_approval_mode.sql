-- Approval mode chosen per automation; each triggered run is submitted with it. Existing automations stay manual.
ALTER TABLE harness_automation ADD COLUMN approval_mode VARCHAR(16) DEFAULT 'MANUAL' NOT NULL;
