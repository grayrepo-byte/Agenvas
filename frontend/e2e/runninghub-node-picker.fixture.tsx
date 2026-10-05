import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useState } from "react";
import { createRoot } from "react-dom/client";
import type { RunningHubDefinition } from "../src/shared/api/client";
import { RunningHubDefinitionEditor } from "../src/features/settings/RunningHubDefinitionEditor";
import { selectRunningHubNodes } from "../src/features/settings/runningHubSelection";
import { Dialog } from "../src/shared/ui/Dialog";
import { Button } from "../src/shared/ui/primitives/button";
import "../src/styles.css";

// Synthetic inputs exercise the real modal scroll lock without backend or credentials.
const NODE_COUNT = 40;
const initial: RunningHubDefinition = {
  schemaVersion: 1, protocolVersion: "V2", targetType: "AI_APP", targetId: "123",
  fields: Array.from({ length: NODE_COUNT }, (_, index) => ({
    key: `reference${index + 1}`, label: `参考图${index + 1}`, type: "IMAGE",
    // The last node contains unfinished mappings; unchecking it must bypass native validation.
    nodeId: String(index + 1), fieldName: index === NODE_COUNT - 1 ? "" : "image", required: false,
  })), outputs: [{ kind: "IMAGE", primary: true, maxCount: 1 }],
  fixedBindings: [{ nodeId: String(NODE_COUNT), fieldName: "", value: 8 }],
  nodeOptions: [{ nodeId: "101", label: "预览图片" }, { nodeId: "102", label: `保存图片 · ${"较长输出节点名称".repeat(18)}` }],
};
function Fixture() {
  const [open, setOpen] = useState(false);
  const [definition, setDefinition] = useState(initial);
  const [selectedNodeIds, setSelectedNodeIds] = useState<string[]>();
  const [published, setPublished] = useState<RunningHubDefinition>();
  return <main className="app-page">
    <Button onClick={() => setOpen(true)}>打开配置</Button>
    <output data-testid="published-definition">{published ? JSON.stringify(published) : ""}</output>
    {open ? <Dialog title="合成 RunningHub 配置" className="runninghub-capability-dialog"
      onClose={() => setOpen(false)} onSubmit={(event) => {
        event.preventDefault(); setPublished(selectRunningHubNodes(definition, selectedNodeIds));
      }} footer={<Button type="submit">保存合成配置</Button>}>
      <RunningHubDefinitionEditor connectionId="synthetic" adapterId="RUNNINGHUB_IMAGE" value={definition}
        selectedNodeIds={selectedNodeIds} onChange={(next, _kind, selection) => {
          setDefinition(next); setSelectedNodeIds(selection);
        }} />
    </Dialog> : null}
  </main>;
}
createRoot(document.getElementById("root")!).render(<QueryClientProvider client={new QueryClient()}><Fixture /></QueryClientProvider>);
