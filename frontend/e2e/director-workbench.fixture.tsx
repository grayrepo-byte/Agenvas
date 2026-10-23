import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useState } from "react";
import { createRoot } from "react-dom/client";
import { DirectorWorkbench } from "../src/features/canvas/DirectorWorkbench";
import { createDirectorScene } from "../src/features/canvas/directorScene";
import type { UploadCanvasItemVersionRequest } from "../src/shared/api/client";
import { resolveLocale, setLocale } from "../src/shared/i18n";
import { Button } from "../src/shared/ui/primitives/button";
import { imageFunctionsFixture, imageFunctionSettings, imageWorkflowCapability } from "../src/test/imageFunctionsFixture";
import "../src/styles.css";

// Synthetic browser evidence only: actual local WebGL and PNG bytes, no backend or Provider.
setLocale(resolveLocale(new URL(location.href).searchParams.get("locale")) ?? "zh");
const workflowFixture = new URL(location.href).searchParams.has("workflow");
const restoreFixture = new URL(location.href).searchParams.has("restore");
const legacyFixture = new URL(location.href).searchParams.has("legacy");
const legacyBase = createDirectorScene();
const legacyScene = legacyFixture ? { ...legacyBase, schemaVersion: 1,
  objects: legacyBase.objects.map((object) => ({ ...object, name: "旧版人偶", scale: 1.6 })) } : undefined;
const mediaSettings = imageFunctionsFixture();
if (workflowFixture) {
  const capability = imageWorkflowCapability();
  capability.id = "synthetic-angle-workflow";
  const definition = capability.settings.runningHub;
  if (definition) definition.fields.push(...Array.from({ length: 8 }, (_, index) => ({
    key: `synthetic-field-${index}`, label: `合成参数 ${index + 1}`, type: "STRING" as const,
    required: false, advanced: false, nodeId: String(index + 10), fieldName: "value",
  })));
  const connection = mediaSettings.connections[1];
  if (connection) mediaSettings.connections.push({ ...connection, id: "synthetic-workflow", platform: "RUNNINGHUB", capabilities: [capability] });
}
const evidence = { uploads: [] as { width: number; height: number; bytes: number; colors: number }[],
  scenes: [] as UploadCanvasItemVersionRequest[], angles: [] as unknown[] };
let capturedImageUrl: string | undefined;
const publish = () => {
  const output = document.getElementById("director-browser-evidence");
  if (output) output.textContent = JSON.stringify(evidence);
};
const originalFetch = window.fetch.bind(window);
window.fetch = async (input, init) => {
  const url = new URL(input instanceof Request ? input.url : String(input), location.href);
  if (!url.pathname.startsWith("/api/")) return originalFetch(input, init);
  let response: unknown;
  if (url.pathname.endsWith("/auth/csrf")) response = { headerName: "X-XSRF-TOKEN", token: "synthetic-token" };
  else if (url.pathname.endsWith("/media-functions")) response = imageFunctionSettings(workflowFixture ? "synthetic-angle-workflow" : undefined);
  else if (url.pathname.endsWith("/media-connections")) response = mediaSettings;
  else if (url.pathname.endsWith("/assets")) {
    if (!(init?.body instanceof FormData)) throw new Error("Expected a real multipart PNG upload");
    const file = init.body.get("file");
    if (!(file instanceof File) || file.type !== "image/png") throw new Error("Expected real PNG file bytes");
    const image = await createImageBitmap(file);
    const sample = document.createElement("canvas"); sample.width = 32; sample.height = 32;
    const context = sample.getContext("2d");
    if (!context) throw new Error("Cannot sample the generated frame");
    context.drawImage(image, 0, 0, 32, 32);
    const pixels = context.getImageData(0, 0, 32, 32).data;
    const colors = new Set<string>();
    for (let offset = 0; offset < pixels.length; offset += 4) colors.add(`${pixels[offset]},${pixels[offset + 1]},${pixels[offset + 2]}`);
    evidence.uploads.push({ width: image.width, height: image.height, bytes: file.size, colors: colors.size });
    if (capturedImageUrl) URL.revokeObjectURL(capturedImageUrl);
    capturedImageUrl = URL.createObjectURL(file);
    const capturedImage = document.getElementById("director-browser-capture");
    if (capturedImage instanceof HTMLImageElement) capturedImage.src = capturedImageUrl;
    image.close();
    response = { id: `synthetic-capture-${evidence.uploads.length}` };
  } else if (url.pathname.endsWith("/upload-version")) {
    evidence.scenes.push(JSON.parse(String(init?.body)) as UploadCanvasItemVersionRequest);
    response = { id: "synthetic-result" };
  } else if (url.pathname.endsWith("/image-operations")) {
    evidence.angles.push(JSON.parse(String(init?.body)) as unknown);
    response = { id: "synthetic-task", status: "READY" };
  } else throw new Error(`Unexpected synthetic request: ${url.pathname}`);
  publish();
  return new Response(JSON.stringify(response), { headers: { "Content-Type": "application/json" } });
};

const sourceCanvas = document.createElement("canvas"); sourceCanvas.width = 640; sourceCanvas.height = 360;
const sourceContext = sourceCanvas.getContext("2d");
if (sourceContext) {
  sourceContext.fillStyle = "#D6D2CA"; sourceContext.fillRect(0, 0, 640, 360);
  sourceContext.fillStyle = "#D9A35F"; sourceContext.fillRect(260, 100, 120, 200);
}
function Fixture() {
  const [open, setOpen] = useState(true);
  return <main style={{ padding: 24 }}>
    <Button id="open-director-fixture" onClick={() => setOpen(true)}>打开合成导演台</Button>
    <output id="director-browser-evidence" className="sr-only">{JSON.stringify(evidence)}</output>
    <img id="director-browser-capture" alt="Synthetic captured PNG evidence" hidden={open || evidence.uploads.length === 0}
      style={{ display: open || evidence.uploads.length === 0 ? "none" : "block", maxWidth: "100%", maxHeight: "80dvh", marginTop: 16 }} />
    {open && <DirectorWorkbench projectId="synthetic-project" artifactId="synthetic-artifact" canvasItemId="synthetic-item"
      expectedVersion={2} sourceVersionId="synthetic-source" sourceTitle="Synthetic source image"
      initialScene={restoreFixture ? evidence.scenes.at(-1)?.directorScene ?? legacyScene : legacyScene}
      sourceUrl={sourceCanvas.toDataURL("image/png")} onClose={() => setOpen(false)} />}
  </main>;
}
const fixtureRoot = createRoot(document.getElementById("root")!);
fixtureRoot.render(<QueryClientProvider client={new QueryClient({
  defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
})}><Fixture /></QueryClientProvider>);
if (import.meta.hot) import.meta.hot.dispose(() => {
  // A fixture entry can rerun during HMR; release its WebGL viewport and synthetic fetch first.
  fixtureRoot.unmount();
  window.fetch = originalFetch;
  if (capturedImageUrl) URL.revokeObjectURL(capturedImageUrl);
});
