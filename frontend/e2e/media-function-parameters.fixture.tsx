import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useState } from "react";
import { createRoot } from "react-dom/client";
import { ImageFunctionConfiguration } from "../src/features/canvas/ImageFunctionConfiguration";
import { VideoOperationPanel } from "../src/features/canvas/VideoOperationPanel";
import { RunningHubForm, type RunningHubValue } from "../src/features/canvas/RunningHubForm";
import { Select } from "../src/shared/ui/Select";
import { Button } from "../src/shared/ui/primitives/button";
import type { RunningHubField } from "../src/shared/api/client";
import { imageFunctionsFixture, imageFunctionSettings, imageWorkflowCapability } from "../src/test/imageFunctionsFixture";
import { videoFunctionsFixture } from "../src/test/videoFunctionsFixture";
import "../src/styles.css";
import "../src/features/canvas/ArtifactCardFrame.css";

// Local synthetic contracts exercise the same controls under toolbar and preview layouts.
const mode = new URLSearchParams(location.search).get("mode") ?? "image";
const busy = new URLSearchParams(location.search).has("busy");
const scalarFields: RunningHubField[] = [
  { key: "sound", label: "PrimitiveBoolean", description: "保留声音", type: "BOOLEAN", nodeId: "4", fieldName: "value", defaultValue: true, required: false, advanced: false },
  { key: "cache", label: "PrimitiveBoolean", description: "如果处理失败，请开启这个选项后重试；关闭可能增加处理时间。", type: "BOOLEAN", nodeId: "5", fieldName: "value", defaultValue: false, required: false, advanced: false },
];
const videoSettings = videoFunctionsFixture();
const video = videoSettings.connections.flatMap(connection => connection.capabilities).find(capability => capability.id === "upscale-cap")!;
video.settings.runningHub!.fields.push(...scalarFields);
const imageSettings = imageFunctionsFixture();
const image = imageWorkflowCapability();
image.settings.runningHub!.fields.push(...scalarFields);
imageSettings.connections.push({ ...videoSettings.connections[1]!, capabilities: [image] });
const client = new QueryClient({ defaultOptions: { queries: { staleTime: Infinity } } });
client.setQueryData(["settings", "media"], mode === "video" ? videoSettings : imageSettings);
client.setQueryData(["media-functions"], mode === "video"
  ? [{ operation: "VIDEO_UPSCALE", capabilityId: video.id, version: 5 }]
  : imageFunctionSettings().map(setting => setting.operation === "IMAGE_UPSCALE" ? { ...setting, capabilityId: image.id } : setting));
function submitted(value: unknown) { document.body.dataset.submitted = JSON.stringify(value); }
function Fixture() {
  const [values, setValues] = useState<Record<string, RunningHubValue>>({});
  const [scale, setScale] = useState("2");
  return mode === "preview" ? <main style={{ width: "min(400px, calc(100vw - 48px))", margin: 24 }}>
    <RunningHubForm definition={image.settings.runningHub!} values={values} prompt="" durationSeconds={null} choices={[]}
      disabled={busy} onChange={(key, value) => setValues(current => {
        const next = { ...current }; if (value === undefined) delete next[key]; else next[key] = value; return next;
      })} />
  </main> : <div className="artifact-card-toolbar" style={{ margin: 24 }}>
    <Button type="button">工具栏操作</Button>
    <div className="media-version-picker"><Button type="button">媒体版本</Button></div>
    <div className="text-card-version-picker"><Button type="button">文字版本</Button></div>
    {mode === "video" ? <VideoOperationPanel operation="UPSCALE" sourceVersionId="synthetic-video" sourceTitle="合成视频"
      busy={busy} error={null} onClose={() => {}} onSubmit={submitted} /> : <ImageFunctionConfiguration operation="UPSCALE"
      sourceVersionId="synthetic-image" busy={busy} onClose={() => {}} onSubmit={submitted}>
      {({ controls, submitDisabled, submit }) => <div className="media-operation-panel" role="dialog" aria-label="图片参数">
        <header><strong>图片参数</strong></header>
        <label>本地控件示例<Select value={scale} disabled={busy} onChange={event => setScale(event.target.value)}><option value="2">2×</option><option value="4">4×</option></Select></label>
        {controls}
        <footer><Button type="button" disabled={busy || submitDisabled} onClick={() => submit({})}>开始处理</Button></footer>
      </div>}
    </ImageFunctionConfiguration>}
  </div>;
}
createRoot(document.getElementById("root")!).render(<QueryClientProvider client={client}><Fixture /></QueryClientProvider>);
