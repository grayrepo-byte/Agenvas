package dev.agenvas.llm.application;

import dev.agenvas.task.domain.Task;
import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 将一个模型响应中的工具调用作为同一事务执行，防止后一个无效调用留下部分产物。 */
@Service
public class ToolBatchExecutionService {

    /** 单次工具调用的权限、幂等与业务命令执行入口。 */
    private final ToolExecutionService tools;

    /** 注入带持久化去重和租约校验的单项工具执行服务。
     * @param tools 执行或重放单条模型工具调用
     */
    public ToolBatchExecutionService(ToolExecutionService tools) {
        this.tools = tools;
    }

    /**
     * 按模型原始顺序执行工具；任何一项失败时回滚本批业务变更与工具账本。
     *
     * @param context 服务端确认的所有者、项目和 Run 作用域
     * @param stepIndex 本批工具所属的持久化模型回合序号
     * @param calls 从已保存响应恢复的原始工具调用列表
     * @param lease 当前模型回合任务的租约快照
     * @param workerId 租约持有者标识，逐项执行时均需重新核验
     */
    @Transactional
    public void executeLeased(TrustedToolContext context, int stepIndex,
            List<AssistantMessage.ToolCall> calls, Task lease, String workerId) {
        for (AssistantMessage.ToolCall call : calls) {
            tools.executeLeased(context, stepIndex, call.id(), lease, workerId);
        }
    }
}
