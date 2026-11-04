import type { LlmModelMetadata, LlmModelPolicy, LlmModelProvider, LlmModelSelection } from "../../shared/api/client";
export type ModelOptionsDraft = { provider: LlmModelProvider; mode: "AUTO" | "MANUAL"; context: string; input: string;
  output: string; outputParameter: LlmModelSelection["outputParameter"]; previewId: string | null; previewModelId: string | null; manualOverride: boolean };
export function modelOptionsFromPolicy(policy: LlmModelPolicy | null | undefined): ModelOptionsDraft {
  return { provider: policy?.provider ?? "COMPATIBLE", mode: policy && policy.source !== "MANUAL" ? "AUTO" : "MANUAL",
    context: String(policy?.contextWindowTokens ?? ""), input: String(policy?.inputLimitTokens ?? ""),
    output: String(policy?.maxOutputTokens ?? 4096), outputParameter: policy?.outputParameter ?? "MAX_TOKENS", previewId: null, previewModelId: null,
    manualOverride: policy?.source === "MANUAL" };
}
export function automaticModelOptions(draft: ModelOptionsDraft, model: LlmModelMetadata, previewId: string): ModelOptionsDraft | null {
  const declared = draft.provider === "OLLAMA" ? model.runtimeContextTokens : model.deploymentContextTokens ?? model.contextWindowTokens;
  const context = declared && model.contextWindowTokens ? Math.min(declared, model.contextWindowTokens) : declared;
  if (!context && !model.inputLimitTokens) return null;
  const safeWindow = context ?? model.inputLimitTokens;
  const output = Math.max(1, Math.min(number(draft.output) ?? 4096, model.outputLimitTokens ?? 4096, safeWindow ? Math.floor(safeWindow / 4) : 4096));
  return { ...draft, mode: "AUTO", context: String(context ?? ""), input: String(model.inputLimitTokens ?? ""), output: String(output),
    previewId, previewModelId: model.id };
}
export function modelSelection(draft: ModelOptionsDraft, modelId: string): LlmModelSelection | null {
  const context = number(draft.context), input = number(draft.input), output = number(draft.output);
  if ((!context && !input) || !output || output > 2_000_000 || (context && output >= context)
    || (draft.context !== "" && !context) || (draft.input !== "" && !input)) return null;
  if (draft.mode === "AUTO") {
    if (!draft.previewId || draft.previewModelId !== modelId) return null;
    return { provider: draft.provider, mode: "AUTO", previewId: draft.previewId, maxOutputTokens: output, outputParameter: draft.outputParameter };
  }
  return { provider: draft.provider, mode: "MANUAL", contextWindowTokens: context, inputLimitTokens: input,
    maxOutputTokens: output, outputParameter: draft.outputParameter };
}
function number(value: string): number | null {
  if (!/^[1-9]\d*$/.test(value)) return null;
  const result = Number(value);
  return Number.isSafeInteger(result) && result <= 16_000_000 ? result : null;
}
