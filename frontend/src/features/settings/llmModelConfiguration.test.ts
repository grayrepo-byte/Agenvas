import { describe, expect, it } from "vitest";
import type { LlmModelMetadata } from "../../shared/api/client";
import { automaticModelOptions, modelOptionsFromPolicy, modelSelection } from "./llmModelConfiguration";
const unloaded: LlmModelMetadata = { id: "model", contextWindowTokens: 131072, inputLimitTokens: null,
  outputLimitTokens: null, runtimeContextTokens: null, digest: null, deploymentContextTokens: null };
describe("model capacity selection", () => {
  it("keeps unknown capacity empty and blocks an unconfirmed save", () => {
    const draft = modelOptionsFromPolicy(null);
    expect(draft.context).toBe("");
    expect(modelSelection(draft, "model")).toBeNull();
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
