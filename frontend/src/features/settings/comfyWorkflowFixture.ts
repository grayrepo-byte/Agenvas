import type { ComfyUiGraph, ComfyUiWorkflowDefinition } from "../../shared/api/client";
export const graph: ComfyUiGraph = {
  "11": { class_type: "CustomTextEncoder", inputs: { text: "fixed prompt", model: "custom-model.gguf" } },
  "14": { class_type: "CustomGenerator", inputs: { conditioning: ["11", 0], seconds: 3, steps: 20 } },
  "99": { class_type: "SaveVideo", inputs: { video: ["14", 0] } },
};
export const definition: ComfyUiWorkflowDefinition = { schemaVersion: 1, graph,
  bindings: [{ nodeId: "11", inputName: "text", source: "PROMPT" }, { nodeId: "14", inputName: "seconds", source: "DURATION_SECONDS" }],
  output: { nodeId: "99", field: "videos" }, width: 1024, height: 1024, minimumSeconds: 1, maximumSeconds: 5, fps: 24, frameMultiple: 1, frameOffset: 0 };
