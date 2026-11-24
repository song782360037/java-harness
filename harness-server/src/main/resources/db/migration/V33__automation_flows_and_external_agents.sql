-- Automations can trigger a flow instead of creating an ordinary session, and group members can run via ACP.
ALTER TABLE harness_automation ADD COLUMN flow_id VARCHAR(36) NOT NULL DEFAULT '';
ALTER TABLE harness_automation_run ADD COLUMN flow_id VARCHAR(36);
ALTER TABLE harness_automation_run ADD COLUMN flow_item_id VARCHAR(36);
ALTER TABLE harness_group_member ADD COLUMN external_agent_id VARCHAR(36) NOT NULL DEFAULT '';
