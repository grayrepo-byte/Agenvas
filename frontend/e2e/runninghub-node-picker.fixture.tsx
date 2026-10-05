import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useState } from "react";
import { createRoot } from "react-dom/client";
import type { RunningHubDefinition } from "../src/shared/api/client";
import { RunningHubDefinitionEditor } from "../src/features/settings/RunningHubDefinitionEditor";
import { Dialog } from "../src/shared/ui/Dialog";
import { Button } from "../src/shared/ui/primitives/button";
import "../src/styles.css";

// Synthetic inputs exercise the real modal scroll lock without backend or credentials.
const NODE_COUNT = 40;
const initial: RunningHubDefinition = {
  schemaVersion: 1, protocolVersion: "V2", targetType: "AI_APP", targetId: "123",
  fields: Array.from({ length: NODE_COUNT }, (_, index) => ({
    key: `reference${index + 1}`, label: `参考图${index + 1}`, type: "IMAGE",
    nodeId: String(index + 1), fieldName: "image", required: false,
  })), outputs: [{ kind: "IMAGE", primary: true, maxCount: 1 }],
  nodeOptions: [{ nodeId: "101", label: "预览图片" }, { nodeId: "102", label: `保存图片 · ${"较长输出节点名称".repeat(18)}` }],
};
function Fixture() {
  const [open, setOpen] = useState(false);
  const [definition, setDefinition] = useState(initial);
  return <main className="app-page">
    <Button onClick={() => setOpen(true)}>打开配置</Button>
    {open ? <Dialog title="合成 RunningHub 配置" className="runninghub-capability-dialog"
      onClose={() => setOpen(false)} onSubmit={(event) => event.preventDefault()}>
      <RunningHubDefinitionEditor connectionId="synthetic" adapterId="RUNNINGHUB_IMAGE" value={definition} onChange={setDefinition} />
    </Dialog> : null}
  </main>;
}
createRoot(document.getElementById("root")!).render(<QueryClientProvider client={new QueryClient()}><Fixture /></QueryClientProvider>);
