import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it, vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import type { AgentMediaApproval, AgentMediaApprovalRevision, MediaCapability, MediaDraft, MediaSettings } from "../../shared/api/client";
import { server } from "../../test/server";
import { AgentMediaApprovalEditor } from "./AgentMediaApprovalEditor";

const capability: MediaCapability = {
  id: "h3", name: "H3 official", adapterId: "MINIMAX_H3", kind: "VIDEO_GENERATION", enabled: true,
  version: 0, capabilityVersion: 1, minimumSeconds: 4, maximumSeconds: 15, mappingSha256: "synthetic",
  maxReferenceImages: 9, maxReferenceAudios: 3, maxReferenceVideos: 3, supportsEndFrame: true,
  supportedVideoInputModes: ["TEXT", "START_END", "GENERAL_REFERENCE"], defaultVideoInputMode: "TEXT",
  supportedImageAspectRatios: [], supportedImageResolutions: [], supportedImageQualities: [],
  supportsImageMask: false, supportsTransparentBackground: false,
  settings: { defaultParameters: { videoResolution: "768p" }, pricingByResolution: {
    "768p": { amount: "1", currency: "CNY", unit: "SECOND" }, "1440p": { amount: "2", currency: "CNY", unit: "SECOND" },
  } },
};
const draft: MediaDraft = {
  projectId: "project", canvasItemId: "item", prompt: "Waves on the coast", capabilityId: "h3", styleId: null,
  parameters: { videoResolution: "768p", aspectRatio: "16:9" }, durationSeconds: 5, videoInputMode: "TEXT",
  mediaInputs: [], mentions: [], displayMode: "DRAFT", version: 2, createdAt: "2026-10-01T00:00:00Z", updatedAt: "2026-10-01T00:00:00Z",
};
const output: AgentMediaApproval["outputs"][number] = {
  kind: "VIDEO", title: "Coast", artifactId: "artifact", canvasItemId: "item", draftVersion: 2,
  preview: { capabilityId: "h3", parameters: draft.parameters, priceUnknown: true },
};
const url = "/api/v1/projects/project/runs/run/media-approvals/approval";
function mount(options: { capabilities?: MediaCapability[]; draft?: MediaDraft } = {}) {
  const currentDraft = options.draft ?? draft;
  const settings: MediaSettings = { connections: [{ id: "connection", name: "My provider", platform: "MINIMAX", enabled: true,
    version: 0, connectionVersion: 1, origin: null, keyMask: null, connectivityStatus: "NOT_CHECKED", realGenerationTested: false,
    capabilities: options.capabilities ?? [capability] }], defaults: [{ kind: "VIDEO_GENERATION", capabilityId: "h3", version: 0 }] };
  server.use(
    http.get("/api/v1/settings/media-connections", () => HttpResponse.json(settings)),
    http.get("/api/v1/projects/project/canvas-items/item/media-draft", () => HttpResponse.json(currentDraft)),
    http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "synthetic" })),
  );
  const onSaved = vi.fn(); const onClose = vi.fn(); const client = createQueryClient();
  render(<QueryClientProvider client={client}><AgentMediaApprovalEditor projectId="project" runId="run" approvalId="approval"
    version={3} outputIndex={0} output={output} onSaved={onSaved} onClose={onClose} /></QueryClientProvider>);
  return { onSaved, onClose };
}

async function choose(label: string, option: string) {
  const user = userEvent.setup();
  await user.click(await screen.findByRole("combobox", { name: label }));
  await user.click(await screen.findByRole("option", { name: option }));
}

describe("pending video approval settings", () => {
  it("saves a supported resolution and duration for repricing, without making a generation decision", async () => {
    const requests: AgentMediaApprovalRevision[] = [];
    server.use(http.put(url, async ({ request }) => {
      requests.push(await request.json() as AgentMediaApprovalRevision);
      expect(request.headers.get("X-XSRF-TOKEN")).toBeTruthy();
      return HttpResponse.json({ id: "approval", version: 4, status: "PENDING", outputs: [] });
    }));
    const { onSaved } = mount();
    await choose("分辨率", "2K");
    const user = userEvent.setup();
    const duration = screen.getByRole("spinbutton", { name: "时长" });
    await user.clear(duration); await user.type(duration, "7");
    await user.click(screen.getByRole("button", { name: "保存并重新估算" }));
    await waitFor(() => expect(onSaved).toHaveBeenCalledOnce());
    expect(requests).toEqual([{ expectedVersion: 3, outputIndex: 0, capabilityId: "h3",
      parameters: { aspectRatio: "16:9", videoResolution: "1440p" }, durationSeconds: 7, videoInputMode: "TEXT" }]);
    expect(screen.queryByRole("button", { name: "批准并生成" })).not.toBeInTheDocument();
  });

  it("preserves edited values after a conflict or validation failure", async () => {
    server.use(http.put(url, () => HttpResponse.json({ code: "AGENT_MEDIA_APPROVAL_STALE", title: "Conflict" }, { status: 409 })));
    mount(); await choose("分辨率", "2K");
    const user = userEvent.setup(); await user.click(screen.getByRole("button", { name: "保存并重新估算" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("审批版本或生成输入已变化");
    expect(screen.getByRole("combobox", { name: "分辨率" })).toHaveTextContent("2K");
    expect(screen.getByRole("button", { name: "保存并重新估算" })).toBeEnabled();
  });

  it("uses the model's published parameter fields for native resolutions", async () => {
    const native: MediaCapability = { ...capability, adapterId: "WAN_VIDEO", settings: { nativeInputs: [
      { key: "resolution", label: "Output tier", type: "SELECT", source: "PARAMETER", required: true, advanced: false,
        defaultValue: "720P", options: [{ value: "720P", label: "720P" }, { value: "1080P", label: "1080P" }] },
    ] } };
    const requests: AgentMediaApprovalRevision[] = [];
    server.use(http.put(url, async ({ request }) => { requests.push(await request.json() as AgentMediaApprovalRevision); return HttpResponse.json({}); }));
    mount({ capabilities: [native], draft: { ...draft, parameters: { dynamicValues: { resolution: "720P" } }, durationSeconds: null } });
    await choose("Output tier *", "1080P");
    await userEvent.setup().click(screen.getByRole("button", { name: "保存并重新估算" }));
    await waitFor(() => expect(requests).toHaveLength(1));
    expect(requests[0]?.parameters.dynamicValues?.resolution).toBe("1080P");
  });

  it("refuses a provider switch that would drop reference material", async () => {
    const incompatible = { ...capability, id: "incompatible", name: "No reference inputs", maxReferenceImages: 0 };
    mount({ capabilities: [capability, incompatible], draft: { ...draft, videoInputMode: "GENERAL_REFERENCE", mediaInputs: [
      { versionId: "exact-image", artifactId: "image", role: "REFERENCE", order: 0, color: "#c084fc", sources: [] },
    ] } });
    await choose("生成能力", "My provider · No reference inputs");
    expect(await screen.findByRole("alert")).toHaveTextContent("无法保留全部参考素材");
    expect(screen.getByRole("combobox", { name: "生成能力" })).toHaveTextContent("H3 official");
  });
});
