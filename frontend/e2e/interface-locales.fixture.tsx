import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import { MemoryRouter, Route, Routes } from "react-router";
import { LoginPage } from "../src/features/auth/LoginPage";
import { ProjectsPage } from "../src/features/projects/ProjectsPage";
import { SkillsPage } from "../src/features/skills/SkillsPage";
import { MediaSettingsPage } from "../src/features/settings/MediaSettingsPage";
import { MediaFunctionSettingsPage } from "../src/features/settings/MediaFunctionSettingsPage";
import { SystemSettingsPage } from "../src/features/settings/SystemSettingsPage";
import { AudioPromptTools } from "../src/features/canvas/AudioPromptTools";
import { RunningHubForm } from "../src/features/canvas/RunningHubForm";
import { AgentRunConversation } from "../src/features/canvas/AgentRunConversation";
import { VoiceLibrary } from "../src/features/canvas/VoiceLibrary";
import type { MediaCapability, MediaSettings, MediaStyle, PromptDefinition } from "../src/shared/api/client";
import { IMAGE_OPERATIONS, VIDEO_OPERATIONS, imageFunction, videoFunction } from "../src/shared/mediaFunctions";
import { setLocale } from "../src/shared/i18n";
import { PageShell } from "../src/shared/ui/PageShell";
import { Button } from "../src/shared/ui/primitives/button";
import { seededPrompts, seededStyles } from "../src/test/seededCatalogs";
import { packagedSkills, packagedSkillVersions } from "../src/test/builtinSkills";
import "../src/styles.css";
import "../src/features/canvas/AgentChatPrimitives.css";

const params = new URLSearchParams(location.search);
const requestedLocale = params.get("locale");
setLocale(requestedLocale === "ru" || requestedLocale === "ja" || requestedLocale === "zh" ? requestedLocale : "en");
const localId = (value: number) => `00000000-0000-4000-8000-000000000${value}`;
const localCapabilities = [
  [202, "本地图片处理", "LOCAL_IMAGE_PROCESSOR", "IMAGE_GENERATION"],
  [203, "本地视频深度提取", "LOCAL_VIDEO_PROCESSOR", "VIDEO_GENERATION"],
  [204, "本地视频音轨分离", "LOCAL_VIDEO_AUDIO_EXTRACTOR", "AUDIO_GENERATION"],
  [205, "本地智能配音合成", "LOCAL_VIDEO_COMPOSITOR", "VIDEO_GENERATION"],
  [206, "本地音频裁剪", "LOCAL_AUDIO_TRIMMER", "AUDIO_GENERATION"],
].map(([id, name, adapterId, kind]) => ({
  id: localId(Number(id)), name, adapterId, kind, enabled: true, version: 0, capabilityVersion: 1,
  minimumSeconds: 0, maximumSeconds: 60, maxReferenceImages: 1, maxReferenceVideos: 0, maxReferenceAudios: 0,
  supportsEndFrame: false, supportedVideoInputModes: [], defaultVideoInputMode: null,
  supportedImageAspectRatios: ["AUTO"], supportedImageResolutions: [], supportedImageQualities: [],
  supportsTransparentBackground: false, supportsImageMask: false, mappingSha256: "synthetic", settings: {},
})) as MediaCapability[];
const media: MediaSettings = { defaults: [
  { kind: "IMAGE_GENERATION", capabilityId: null, version: 0 },
  { kind: "VIDEO_GENERATION", capabilityId: null, version: 0 },
  { kind: "AUDIO_GENERATION", capabilityId: null, version: 0 },
], connections: [{ id: localId(201), name: "本地图片处理", platform: "LOCAL", enabled: true, version: 0,
  connectionVersion: 1, origin: null, keyMask: null, connectivityStatus: "NOT_CHECKED", realGenerationTested: false,
  capabilities: localCapabilities }] };
const functions = [...IMAGE_OPERATIONS.map(operation => ({ operation: imageFunction(operation),
  capabilityId: ["DEPTH_MAP", "UPSCALE", "RESIZE", "CROP", "ROTATE", "FLIP_HORIZONTAL", "FLIP_VERTICAL"].includes(operation) ? localId(202) : null,
  version: 0 })), ...VIDEO_OPERATIONS.map(operation => ({ operation: videoFunction(operation),
  capabilityId: operation === "DEPTH_MAP" ? localId(203) : operation === "EXTRACT_AUDIO" ? localId(204) : null, version: 0 }))];
const user = { id: "synthetic-admin", loginName: "demo-admin", role: "ADMIN" };
const prompts: PromptDefinition[] = seededPrompts;
const styles: MediaStyle[] = seededStyles.map(style => ({ ...style,
  thumbnailUrl: "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='160' height='90'%3E%3Crect width='160' height='90' fill='%235b718a'/%3E%3C/svg%3E" }));
const reads: Record<string, unknown> = {
  "/api/v1/auth/me": user,
  "/api/v1/projects": { items: [{ id: "synthetic-project", name: "Synthetic storyboard", aspectRatio: "LANDSCAPE_16_9", status: "ACTIVE", version: 0,
    createdAt: "2026-10-03T00:00:00Z", updatedAt: "2026-10-03T00:00:00Z" }], nextCursor: null },
  "/api/v1/settings/media-connections": media,
  "/api/v1/settings/media-functions": functions,
  "/api/v1/settings/prompts": { items: prompts },
  "/api/v1/settings/media-styles": styles,
  "/api/v1/skills": { items: packagedSkills, nextCursor: null, total: packagedSkills.length },
  "/api/v1/settings/llm": { configured: false, version: 0 },
  "/api/v1/settings/diagnostics": { llmMode: "MOCK", mediaMode: "MOCK", recentErrors: [] },
  "/api/v1/projects/synthetic-project/runs/synthetic-run/tasks": [],
  "/api/v1/projects/synthetic-project/runs/synthetic-run/actions": [],
  "/api/v1/projects/synthetic-project/runs/synthetic-run/media-approvals": [],
};
for (const version of packagedSkillVersions) reads[`/api/v1/skills/${version.skillId}/versions/${version.id}`] = {
  ...version, skillMd: "# Synthetic creative method\nExisting runtime instructions remain user content.",
  resources: [{ path: "references/synthetic-method.md", content: "Synthetic reference content.", contentHash: "synthetic" }],
};
// Every fetch terminates inside this fixture. Unexpected requests fail closed;
// the browser harness additionally blocks API and external network requests.
const requests: { path: string; method: string; language: string | null }[] = [];
const unexpected: string[] = [];
window.fetch = async (input, init) => {
  const request = new Request(new URL(typeof input === "string" ? input : input instanceof URL ? input.href : input.url, location.href), init);
  const path = new URL(request.url).pathname;
  requests.push({ path, method: request.method, language: request.headers.get("Accept-Language") });
  document.body.dataset.requests = JSON.stringify(requests);
  if (request.method === "GET" && path in reads) return Response.json(reads[path]);
  unexpected.push(`${request.method} ${path}`);
  document.body.dataset.unexpected = JSON.stringify(unexpected);
  return Response.json({ title: "Unexpected synthetic request", status: 500 }, { status: 500 });
};
document.body.dataset.unexpected = "[]";
document.body.dataset.seedCounts = JSON.stringify({ prompts: prompts.length, styles: styles.length });
const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity, refetchOnWindowFocus: false } } });
function Tools() {
  const [prompt, setPrompt] = useState("A quiet forest with distant birds");
  const [values, setValues] = useState<Record<string, string | number | boolean>>({ sound: false });
  const [voiceOpen, setVoiceOpen] = useState(false);
  const [speaker, setSpeaker] = useState("");
  const voiceContainer = useRef<HTMLDivElement>(null);
  return <PageShell title="Synthetic audio tools"><section className="ui-panel" style={{ maxWidth: 700 }}>
    <AudioPromptTools projectId="synthetic-project" canvasItemId="synthetic-item" prompt={prompt} hasMentions={false} onApply={setPrompt} />
    <RunningHubForm definition={{ fields: [{ key: "sound", label: "Keep sound", type: "BOOLEAN", required: false, advanced: false, defaultValue: true }] }}
      values={values} prompt="" durationSeconds={null} choices={[]} onChange={(key, value) => setValues(current => {
        const next = { ...current }; if (value === undefined) delete next[key]; else next[key] = value; return next;
      })} />
    <AgentRunConversation projectId="synthetic-project" active={false} run={{ id: "synthetic-run", status: "SUCCEEDED",
      instruction: "Create a synthetic storyboard", createdAt: "2026-10-03T00:00:00Z" }} />
    <output data-selected-speaker={speaker}>Speaker ID: {speaker || "Automatic"}</output>
    <div style={{ position: "fixed", right: 24, bottom: 24, zIndex: 20 }}>
      <Button type="button" data-open-voices onClick={() => setVoiceOpen(current => !current)}>Browse synthetic voice selection</Button>
      {voiceOpen ? <VoiceLibrary selected={speaker} onSelect={setSpeaker} onClose={() => setVoiceOpen(false)} containerRef={voiceContainer} /> : null}
    </div>
  </section></PageShell>;
}
createRoot(document.getElementById("root")!).render(<QueryClientProvider client={client}>
  <MemoryRouter initialEntries={[params.get("screen") ?? "/login"]}><Routes>
    <Route path="/login" element={<LoginPage />} /><Route path="/projects" element={<ProjectsPage />} />
    <Route path="/skills" element={<SkillsPage />} />
    <Route path="/settings/media" element={<MediaSettingsPage />} /><Route path="/settings/functions" element={<MediaFunctionSettingsPage />} />
    <Route path="/settings/general" element={<SystemSettingsPage />} /><Route path="/tools" element={<Tools />} />
  </Routes></MemoryRouter>
</QueryClientProvider>);
