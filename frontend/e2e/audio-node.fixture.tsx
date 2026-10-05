import { QueryClientProvider } from "@tanstack/react-query";
import { ReactFlow, useNodesState, type NodeProps } from "@xyflow/react";
import { createRoot } from "react-dom/client";
import { createQueryClient } from "../src/app/queryClient";
import { ArtifactCardFrame } from "../src/features/canvas/ArtifactCardFrame";
import { AudioPlayer } from "../src/features/canvas/AudioPlayer";
import { CANVAS_POINTER_THRESHOLD } from "../src/features/canvas/canvasInteraction";
import { setLocale } from "../src/shared/i18n";
import "@xyflow/react/dist/style.css";
import "../src/styles.css";

setLocale("zh");
function AudioNode({ selected }: NodeProps) {
  return <ArtifactCardFrame title="合成音频" kindLabel="音频" selected={selected} locked={false}
    className="audio-canvas-card" toolbar={<button type="button">音频操作</button>}>
    <AudioPlayer src="/e2e/audio-node.wav" title="合成音频" selected={selected} />
  </ArtifactCardFrame>;
}
const nodeTypes = { audio: AudioNode };
function Fixture() {
  const [nodes, setNodes, onNodesChange] = useNodesState([
    { id: "audio", type: "audio", data: {}, position: { x: 120, y: 120 },
      width: 420, height: 160, selected: false },
  ]);
  return <main style={{ width: "100vw", height: "100vh" }}>
    <ReactFlow nodes={nodes} onNodesChange={onNodesChange} nodeTypes={nodeTypes}
      colorMode="dark" panOnDrag={false} selectNodesOnDrag={false}
      nodeClickDistance={CANVAS_POINTER_THRESHOLD} nodeDragThreshold={CANVAS_POINTER_THRESHOLD}
      onNodeClick={(_, node) => setNodes((current) => current.map((item) => ({ ...item, selected: item.id === node.id })))} />
  </main>;
}
createRoot(document.getElementById("root")!).render(
  <QueryClientProvider client={createQueryClient()}><Fixture /></QueryClientProvider>,
);
