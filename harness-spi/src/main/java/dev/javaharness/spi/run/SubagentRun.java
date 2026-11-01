package dev.javaharness.spi.run;

/** 由父运行的 delegate_task 调用创建的子智能体运行；与父运行同属一个会话，但不占用会话的活动任务位置。 */
public record SubagentRun(String parentRunId, String parentCallId, String agent, Run run) {}
