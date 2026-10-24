import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import sourceUrl from "../../backend/src/main/resources/media-styles/photographic.webp";
import { ImageOperationPanel } from "../src/features/canvas/ImageOperationPanel";
import { ImageResizePanel } from "../src/features/canvas/ImageResizePanel";
import { ImageFunctionConfiguration } from "../src/features/canvas/ImageFunctionConfiguration";
import { VideoOperationPanel } from "../src/features/canvas/VideoOperationPanel";
import { AudioTrimDialog } from "../src/features/canvas/AudioTrimDialog";
import { imageOperationLabel } from "../src/shared/mediaFunctions";
import { setLocale, resolveLocale } from "../src/shared/i18n";
import type { ImageOperation } from "../src/shared/api/client";
import { Button } from "../src/shared/ui/primitives/button";
import { imageFunctionsFixture, imageFunctionSettings } from "../src/test/imageFunctionsFixture";
import { videoFunctionsFixture } from "../src/test/videoFunctionsFixture";
import "../src/styles.css";

// Built-in generated style reference and synthetic settings. This fixture cannot call a Provider.
const params = new URLSearchParams(location.search);
setLocale(resolveLocale(params.get("locale")) ?? "zh");
const operations: ImageOperation[] = ["EXPRESSION_EDIT", "OUTPAINT", "THREE_VIEW", "VIEW_ANGLE", "LAYER_SPLIT", "REMOVE_BACKGROUND", "OBJECT_REMOVE", "UPSCALE", "RESIZE"];
const initial = operations.find((entry) => entry === params.get("tool")) ?? "EXPRESSION_EDIT";
const client = new QueryClient({ defaultOptions: { queries: { staleTime: Infinity, retry: false } } });
const imageSettings = imageFunctionsFixture();
const videoSettings = videoFunctionsFixture();
client.setQueryData(["settings", "media"], { defaults: [], connections: [...imageSettings.connections, ...videoSettings.connections] });
client.setQueryData(["media-functions"], [...imageFunctionSettings(),
  { operation: "VIDEO_EXTRACT_AUDIO", capabilityId: "audio-cap", version: 3 },
  { operation: "VIDEO_UPSCALE", capabilityId: "upscale-cap", version: 2 },
  { operation: "VIDEO_DEPTH_MAP", capabilityId: "depth-cap", version: 2 }]);
function Fixture() {
  const [tool, setTool] = useState<ImageOperation | "AUDIO" | "VIDEO" | null>(params.has("audio") ? "AUDIO" : params.has("video") ? "VIDEO" : initial);
  const [result, setResult] = useState("");
  useEffect(() => {
    const originalFetch = window.fetch.bind(window);
    window.fetch = async (input, init) => {
      const url = new URL(input instanceof Request ? input.url : String(input), location.href);
      if (!url.pathname.startsWith("/api/") || /^\/api\/v1\/projects\/synthetic-video-edit\/assets\/synthetic-library-[3456]\/(content|thumbnail)$/.test(url.pathname)) return originalFetch(input, init);
      if (url.pathname === "/api/v1/auth/csrf") return Response.json({ headerName: "X-XSRF-TOKEN", token: "synthetic" });
      if (url.pathname === "/api/v1/projects/synthetic-media-tools/artifacts/synthetic-audio/audio-trims" && init?.method === "POST") {
        setResult(String(init.body));
        return Response.json({ id: "synthetic-trim-task", status: "READY" });
      }
      return Response.json({ detail: "Synthetic fixture: network operation blocked" }, { status: 503 });
    };
    return () => { window.fetch = originalFetch; };
  }, []);
  const busy = params.has("busy");
  const error = params.has("error") ? new Error("Synthetic submission failure · input preserved") : null;
  const submit = (value: unknown) => setResult(JSON.stringify(value));
  return <main style={{ padding: 32 }}>
    <h1>媒体工具 · Mock 交互预览</h1>
    <p>使用内置生成参考图与合成配置，提交仅显示参数，不发送网络请求。</p>
    <nav style={{ display: "flex", gap: 8, flexWrap: "wrap", marginBlock: 24 }}>
      {operations.map((operation) => <Button type="button" variant="outline" key={operation} onClick={() => setTool(operation)}>{imageOperationLabel(operation)}</Button>)}
      <Button variant="outline" onClick={() => setTool("VIDEO")}>音频分离</Button>
      <Button variant="outline" onClick={() => setTool("AUDIO")}>裁剪音频</Button>
    </nav>
    <output>{result}</output>
    {tool === "AUDIO" ? <AudioTrimDialog projectId="synthetic-media-tools" artifactId="synthetic-audio"
      source={{ canvasItemId: "synthetic-item", sourceVersionId: "synthetic-version", expectedCanvasItemVersion: 1 }}
      src="/api/v1/projects/synthetic-video-edit/assets/synthetic-library-3/content" title="合成音频" durationMs={12000} onClose={() => setTool(null)} />
      : tool === "VIDEO" ? <VideoOperationPanel operation="EXTRACT_AUDIO" sourceTitle="合成视频" sourceVersionId="synthetic-video"
        sourceUrl="/api/v1/projects/synthetic-video-edit/assets/synthetic-library-5/content"
        posterUrl="/api/v1/projects/synthetic-video-edit/assets/synthetic-library-5/thumbnail"
        busy={busy} error={error} onClose={() => setTool(null)} onSubmit={submit} />
      : tool ? <ImageFunctionConfiguration key={tool} operation={tool} sourceVersionId="synthetic-source" busy={busy} onClose={() => setTool(null)} onSubmit={submit}>
        {({ capability, controls, submitDisabled, submit: run }) => tool === "RESIZE" ? <ImageResizePanel sourceUrl={sourceUrl} sourceTitle="山间来信 · 参考图"
          sourceWidth={480} sourceHeight={720} loading={false} metadataError={null} onRetryMetadata={() => {}}
          busy={busy} error={error} extraControls={controls} submitDisabled={submitDisabled} onClose={() => setTool(null)} onSubmit={run} />
          : <ImageOperationPanel operation={tool} sourceUrl={sourceUrl} sourceTitle="山间来信 · 参考图" sourceWidth={480} sourceHeight={720}
            capabilities={[capability]} extraControls={controls} submitDisabled={submitDisabled} busy={busy} error={error}
            onClose={() => setTool(null)} onSubmit={run} />}
      </ImageFunctionConfiguration> : null}
  </main>;
}
createRoot(document.getElementById("root")!).render(<QueryClientProvider client={client}><Fixture /></QueryClientProvider>);
