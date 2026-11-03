package dev.javaharness.spi.tool;

import dev.javaharness.spi.workspace.WorkspaceTools;

/**
 * 引擎在每一次工具执行前后调用的拦截器，内置工具、插件工具和子智能体委派都不能绕过。
 * 拦截器按注册顺序执行；抛出的异常会让运行失败，因此实现应只做确定性的检查与改写。
 */
public interface ToolInterceptor {
    /** Return a reason to refuse the call before it runs, or null to let it run. */
    default String before(String runId, ToolCall call) {
        return null;
    }

    /** Rewrite a finished result before it is stored and shown to the model. */
    default WorkspaceTools.Result after(String runId, ToolCall call, WorkspaceTools.Result result) {
        return result;
    }
}
