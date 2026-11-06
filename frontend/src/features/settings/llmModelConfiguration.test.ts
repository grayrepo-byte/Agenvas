import { describe, expect, it } from "vitest";
import type { LlmModelMetadata } from "../../shared/api/client";
import { automaticModelOptions, modelOptionsFromPolicy, modelSelection } from "./llmModelConfiguration";
const unloaded: LlmModelMetadata = { id: "model", contextWindowTokens: 131072, inputLimitTokens: null,
  outputLimitTokens: null, runtimeContextTokens: null, digest: null, deploymentContextTokens: null };
describe("model capacity selection", () => {
  it.each([null, undefined])("allows saving an unknown capacity with the generic million-token default", policy => {
    const draft = modelOptionsFromPolicy(policy);
    expect(modelSelection(draft, "model")).toEqual({ provider: "COMPATIBLE", mode: "MANUAL",
      contextWindowTokens: 1_000_000, inputLimitTokens: null, maxOutputTokens: 4096, outputParameter: "MAX_TOKENS" });
  });
  it("retains saved manual capacity and separate input limits without adding a generic context", () => {
    const policy = { provider: "GEMINI", source: "MANUAL", contextWindowTokens: null,
      inputLimitTokens: 32000, maxOutputTokens: 2048, outputParameter: "MAX_TOKENS", modelDigest: null,
      checkedAt: null, runtimeContextTokens: null, theoreticalContextTokens: null, reportedOutputTokens: null } as const;
    expect(modelSelection(modelOptionsFromPolicy(policy), "model")).toMatchObject({ mode: "MANUAL",
      contextWindowTokens: null, inputLimitTokens: 32000, maxOutputTokens: 2048 });
    expect(modelSelection(modelOptionsFromPolicy({ ...policy, contextWindowTokens: 16384 }), "model"))
      .toMatchObject({ contextWindowTokens: 16384 });
  });
  it("does not promote Ollama architecture capacity or a missing runtime into automatic settings", () => {
    expect(automaticModelOptions({ ...modelOptionsFromPolicy(null), provider: "OLLAMA" }, unloaded, "preview")).toBeNull();
    const draft = automaticModelOptions({ ...modelOptionsFromPolicy(null), provider: "OLLAMA" },
      { ...unloaded, runtimeContextTokens: 4096 }, "preview")!;
    expect(draft.context).toBe("4096");
    expect(draft.output).toBe("1024");
    expect(modelSelection(draft, "model")).toMatchObject({ mode: "AUTO", previewId: "preview", maxOutputTokens: 1024 });
    expect(modelSelection(draft, "other")).toBeNull();
  });
  it("keeps separate Gemini input and output metadata and never submits it as combined context", () => {
    const draft = automaticModelOptions({ ...modelOptionsFromPolicy(null), provider: "GEMINI" },
      { ...unloaded, contextWindowTokens: null, inputLimitTokens: 32000, outputLimitTokens: 2048 }, "preview")!;
    expect(draft.context).toBe(""); expect(draft.input).toBe("32000"); expect(draft.output).toBe("2048");
    expect(modelSelection(draft, "model")).not.toHaveProperty("contextWindowTokens", 32000);
  });
  it("permits labelled manual capacity and rejects malformed or impossible numbers", () => {
    const draft = { ...modelOptionsFromPolicy(null), context: "16384", output: "2048" };
    expect(modelSelection(draft, "custom")).toMatchObject({ mode: "MANUAL", contextWindowTokens: 16384, maxOutputTokens: 2048 });
    expect(modelSelection({ ...draft, context: "2e4" }, "custom")).toBeNull();
    expect(modelSelection({ ...draft, output: "16384" }, "custom")).toBeNull();
  });
});
