-- A ready-made flow for reviewing pull requests. It is an ordinary flow: the user can edit or delete it,
-- and it is not created again. No workspace is bound, so the agents only discuss until the user picks one.
INSERT INTO harness_flow(id, name, description, workspace_id, definition, created_at, revision)
VALUES (
    '00000000-0000-4000-8000-0000000f10a1',
    'PR 流水线',
    '每个 PR 依次经过门禁检查、代码审查和风险评估，低风险直接放行，高风险由你验收。使用前请点标题旁的编辑按钮，绑定代码所在的工作区，发起事项时写明 PR 的分支、链接或改动说明。',
    NULL,
    '{"nodes":[{"key":"start","type":"TRIGGER","name":"新 PR","description":"有一个 PR 需要评审","modelProfileId":"","reasoningEffort":null,"approvalMode":""},{"key":"gate","type":"AGENT","name":"门禁检查员","description":"检查这个 PR 是否具备评审条件：标题和描述是否说清了改了什么、为什么改；改动范围是否单一、大小是否适合一次评审；是否附带必要的测试或验证说明。只做准入判断，不评价代码质量。不具备条件时逐条列出需要作者补充的内容。","modelProfileId":"default","reasoningEffort":null,"approvalMode":"MANUAL"},{"key":"review","type":"AGENT","name":"代码审查员","description":"逐个文件审查 PR 的改动：正确性、边界条件、错误处理、并发与安全问题，以及是否符合项目现有的风格和约定。问题按 高 / 中 / 低 分级，每条给出文件位置、触发场景和修改建议。存在高或中风险问题时结论为需要修改，否则为通过。","modelProfileId":"default","reasoningEffort":null,"approvalMode":"MANUAL"},{"key":"risk","type":"AGENT","name":"风险评估员","description":"结合前面的审查结论评估合并这个 PR 的风险：是否涉及数据库结构、公共接口、权限与安全、支付或数据删除等敏感逻辑；影响面有多大；出问题后能否快速回滚；测试是否覆盖了关键路径。改动局部、可回滚且有测试覆盖时结论为低风险，否则为高风险并说明需要人重点确认什么。","modelProfileId":"default","reasoningEffort":null,"approvalMode":"MANUAL"},{"key":"accept","type":"HUMAN","name":"人工验收","description":"风险评估员认为这个 PR 风险较高，请查看前面的审查结论和风险说明后决定是否合并。","modelProfileId":"","reasoningEffort":null,"approvalMode":""},{"key":"merged","type":"END","name":"可以合并","description":"评审通过，可以合并。这里只标记结果，不会执行合并。","modelProfileId":"","reasoningEffort":null,"approvalMode":""},{"key":"returned","type":"END","name":"打回作者","description":"需要作者修改后重新提交。","modelProfileId":"","reasoningEffort":null,"approvalMode":""}],"edges":[{"from":"start","to":"gate","label":""},{"from":"gate","to":"review","label":"通过"},{"from":"gate","to":"returned","label":"打回"},{"from":"review","to":"risk","label":"通过"},{"from":"review","to":"returned","label":"需要修改"},{"from":"risk","to":"merged","label":"低风险"},{"from":"risk","to":"accept","label":"高风险"},{"from":"accept","to":"merged","label":"合并"},{"from":"accept","to":"returned","label":"打回"}]}',
    CURRENT_TIMESTAMP,
    1
);
