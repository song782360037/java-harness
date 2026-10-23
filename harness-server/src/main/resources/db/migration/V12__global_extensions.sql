-- Retain former settings for recovery while removing workspace overrides from execution.
CREATE TABLE harness_plugin_binding_archive AS SELECT * FROM harness_plugin_binding;
MERGE INTO harness_plugin_binding(scope,name,installation_id,enabled,config,secrets,allowed_tools,trusted_digest,revision)
KEY(scope,name)
SELECT 'user',name,installation_id,enabled,config,secrets,'["*"]','',revision+1
FROM (
    SELECT b.*,ROW_NUMBER() OVER (PARTITION BY name ORDER BY CASE WHEN scope='user' THEN 0 ELSE 1 END,revision DESC,scope) AS priority
    FROM harness_plugin_binding b
) WHERE priority=1;
DELETE FROM harness_plugin_binding WHERE scope<>'user';
CREATE TABLE harness_extension_authoring (
    installation_id VARCHAR(36) PRIMARY KEY REFERENCES harness_plugin_installation(id),
    definition CLOB NOT NULL
);
