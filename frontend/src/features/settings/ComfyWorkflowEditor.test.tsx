import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { useState } from "react";
import { beforeEach, describe, expect, it } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import type { ComfyUiWorkflowDefinition, MediaCapability } from "../../shared/api/client";
import { selectValue } from "../../test/controls";
import { server } from "../../test/server";
import { ComfyWorkflowEditor } from "./ComfyWorkflowEditor";
import { definition, graph } from "./comfyWorkflowFixture";
import { comfyWorkflowProblem } from "./comfyWorkflow";

function Harness({ initial }: { initial?: ComfyUiWorkflowDefinition }) {
  const [settings, setSettings] = useState<MediaCapability["settings"]>({ comfyWorkflow: initial });
  const [ready, setReady] = useState(false);
  const [queryClient] = useState(createQueryClient);
  return <QueryClientProvider client={queryClient}><form>
    <ComfyWorkflowEditor connectionId="comfy" adapterId="COMFY_VIDEO_V1" values={settings} onChange={setSettings} onReadyChange={setReady} />
    <button type="submit" disabled={!ready}>Publish fixture</button>
    <output data-testid="settings">{JSON.stringify(settings)}</output>
  </form></QueryClientProvider>;
}

describe("ComfyWorkflowEditor", () => {
  beforeEach(() => server.use(http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "synthetic" }))));
  it("imports API JSON, preserves custom nodes and constants, maps inputs and requires review", async () => {
    const requests: unknown[] = [];
    server.use(http.post("/api/v1/settings/media-connections/comfy/comfyui/preview", async ({ request }) => {
      requests.push(await request.json()); return HttpResponse.json(graph);
    }));
    render(<Harness />); const user = userEvent.setup();
    expect(screen.getByRole("button", { name: "Publish fixture" })).toBeDisabled();
    await user.upload(screen.getByLabelText("选择 API JSON 文件"), new File([JSON.stringify(graph)], "workflow.json", { type: "application/json" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "解析工作流" })).toBeEnabled());
    await user.click(screen.getByRole("button", { name: "解析工作流" }));
    await screen.findByRole("button", { name: /#11.*CustomTextEncoder/ });
    expect(requests).toEqual([{ workflowJson: JSON.stringify(graph) }]);
    expect(screen.getByLabelText("model 的固定值")).toHaveValue("custom-model.gguf");
    await selectValue(screen.getByRole("combobox", { name: "text 的参数来源" }), "PROMPT");
    await user.click(screen.getByRole("button", { name: /#14.*CustomGenerator/ }));
    expect(screen.queryByRole("combobox", { name: "conditioning 的参数来源" })).not.toBeInTheDocument();
    await selectValue(screen.getByRole("combobox", { name: "seconds 的参数来源" }), "DURATION_SECONDS");
    await selectValue(screen.getByRole("combobox", { name: "结果节点" }), "99");
    await selectValue(screen.getByRole("combobox", { name: "结果字段" }), "videos");
    await user.click(screen.getByRole("button", { name: "检查发布配置" }));
    expect(screen.getByRole("button", { name: "Publish fixture" })).toBeDisabled();
    await user.click(screen.getByRole("checkbox", { name: "已核对节点映射、结果节点与估算价格" }));
    expect(screen.getByRole("button", { name: "Publish fixture" })).toBeEnabled();
    expect(JSON.parse(screen.getByTestId("settings").textContent ?? "{}").comfyWorkflow).toEqual(definition);
    await user.click(screen.getByRole("button", { name: "2. 节点与参数映射" }));
    const steps = screen.getByLabelText("steps 的固定值");
    await user.clear(steps); await user.type(steps, "30");
    expect(screen.getByRole("button", { name: "Publish fixture" })).toBeDisabled();
  });

  it("keeps the current graph when replacement import fails and shows the failure", async () => {
    server.use(http.post("/api/v1/settings/media-connections/comfy/comfyui/preview", () => HttpResponse.json({ title: "Invalid workflow", detail: "API format required", code: "COMFYUI_WORKFLOW_INVALID" }, { status: 422, headers: { "Content-Type": "application/problem+json" } })));
    render(<Harness initial={definition} />); const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "1. 导入工作流" }));
    await user.type(screen.getByLabelText("API JSON 内容"), "invalid");
    await user.click(screen.getByRole("button", { name: "替换工作流并重新映射" }));
    expect(await screen.findByText("API format required")).toBeInTheDocument();
    expect(JSON.parse(screen.getByTestId("settings").textContent ?? "{}").comfyWorkflow).toEqual(definition);
    expect(screen.getByRole("button", { name: "Publish fixture" })).toBeDisabled();
  });

  it("rejects missing prompt, disconnected mappings, incomplete dimensions and noncontiguous images", () => {
    expect(comfyWorkflowProblem(undefined, true)).toContain("导入");
    expect(comfyWorkflowProblem({ ...definition, bindings: [] }, true)).toContain("提示词");
    expect(comfyWorkflowProblem({ ...definition, bindings: [...definition.bindings, { nodeId: "14", inputName: "steps", source: "WIDTH" }] }, true)).toContain("同时映射");
    expect(comfyWorkflowProblem({ ...definition, graph: { ...graph, "88": { class_type: "Other", inputs: { text: "unused" } } },
      bindings: [...definition.bindings, { nodeId: "88", inputName: "text", source: "NEGATIVE_PROMPT" }] }, true)).toContain("连接");
    expect(comfyWorkflowProblem({ ...definition, bindings: [...definition.bindings, { nodeId: "11", inputName: "model", source: "REFERENCE_IMAGE", referenceIndex: 1 }] }, true)).toContain("连续");
  });
});
