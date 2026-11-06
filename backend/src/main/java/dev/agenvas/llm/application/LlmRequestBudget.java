package dev.agenvas.llm.application;

import dev.agenvas.settings.application.LlmModelPolicy;
import java.util.List;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;

/** Trusted per-request planning and wire limits, persisted before the model call. */
public record LlmRequestBudget(int schemaVersion, int contextWindowTokens, int responseReserveTokens,
        int inputBudget, int summaryTokens, int recentToolRounds, int compactionTriggerTokens,
        LlmModelPolicy.OutputParameter outputParameter, long directorVersion) {
    public LlmRequestBudget {
        if (schemaVersion != 1 || contextWindowTokens < 2 || contextWindowTokens > 2_000_000
                || responseReserveTokens < 1 || responseReserveTokens >= contextWindowTokens
                || inputBudget < 1 || inputBudget > contextWindowTokens - responseReserveTokens
                || summaryTokens < 1 || summaryTokens > inputBudget / 4
                || recentToolRounds < 0 || recentToolRounds > 16
                || compactionTriggerTokens < 1 || compactionTriggerTokens > inputBudget
                || outputParameter == null || directorVersion < 1) throw new Failure("MODEL_POLICY_INVALID");
    }
    public static LlmRequestBudget resolve(DirectorSettings settings, LlmModelPolicy policy) {
        if (policy == null) policy = LlmModelPolicy.genericDefault();
        int output = Math.min(settings.responseReserveTokens(), policy.maxOutputTokens());
        // With independent input/output declarations, only the application supplies a
        // combined ceiling. The persisted provider policy continues to have no context claim.
        int window = policy.contextWindowTokens() == null ? settings.contextWindowTokens()
                : Math.min(settings.contextWindowTokens(), policy.contextWindowTokens());
        int input = window - output;
        if (policy.inputLimitTokens() != null) input = Math.min(input, policy.inputLimitTokens());
        if (input < 4) throw new Failure("AGENT_CONTEXT_BUDGET_EXCEEDED");
        return new LlmRequestBudget(1, window, output, input, Math.min(settings.summaryTokens(), input / 4),
                settings.recentToolRounds(), Math.min(input, (int)((long)window * settings.compactionThresholdPercent() / 100)),
                policy.outputParameter(), settings.version());
    }
    static LlmRequestBudget application(DirectorSettings settings) {
        return new LlmRequestBudget(1, settings.contextWindowTokens(), settings.responseReserveTokens(), settings.inputBudget(),
                settings.summaryTokens(), settings.recentToolRounds(), settings.compactionTriggerTokens(),
                LlmModelPolicy.OutputParameter.MAX_TOKENS, settings.version());
    }
    public LlmRequestBudget summaryBudget() {
        int output = Math.min(responseReserveTokens, summaryTokens);
        return new LlmRequestBudget(1, contextWindowTokens, output, inputBudget, summaryTokens, recentToolRounds,
                compactionTriggerTokens, outputParameter, directorVersion);
    }
    public void requireInput(List<Message> messages, List<ToolCallback> tools) {
        if (messages.size() > LlmProtocolCodec.MAX_MESSAGES || LlmTokenEstimator.estimate(messages, tools) > inputBudget)
            throw new Failure("AGENT_CONTEXT_BUDGET_EXCEEDED");
    }
    public static Failure unknown() { return new Failure("MODEL_CAPACITY_UNKNOWN"); }
    public static Failure runtimeChanged() { return new Failure("MODEL_RUNTIME_CONTEXT_CHANGED"); }
    public static final class Failure extends IllegalArgumentException {
        private Failure(String code) { super(code); }
    }
}
