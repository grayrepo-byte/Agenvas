import { render,screen,waitFor } from "@testing-library/react";
import { ReactFlow,ReactFlowProvider,useReactFlow,type Node,type NodeProps } from "@xyflow/react";
import { describe,expect,it } from "vitest";
import { clickControl } from "../../test/controls";
import { ArtifactCardFrame } from "./ArtifactCardFrame";

type FrameNode = Node<{ title: string }, "frame">;

function Frame({ data, selected }: NodeProps<FrameNode>) {
  return <ArtifactCardFrame title={data.title} kindLabel="图片" selected={selected} locked={false}
    toolbar={<button type="button">编辑图片</button>}><span>图片内容</span></ArtifactCardFrame>;
}

const nodeTypes = { frame: Frame };
const initialNodes: FrameNode[] = [
  { id: "first", type: "frame", position: { x: 100, y: 200 }, width: 240, height: 300,
    selected: true, data: { title: "第一张" } },
  { id: "second", type: "frame", position: { x: 700, y: 100 }, width: 240, height: 300,
    selected: false, data: { title: "第二张" } },
];

function CanvasControls() {
  const { setNodes, setViewport } = useReactFlow<FrameNode>();
  return <>
    <button type="button" onClick={() => setNodes((nodes) => nodes.map((node) =>
      node.id === "first" ? { ...node, position: { x: 400, y: 350 } } : node))}>移动节点</button>
    <button type="button" onClick={() => void setViewport({ x: 80, y: 40, zoom: 1.5 })}>平移并缩放</button>
    <button type="button" onClick={() => setNodes((nodes) => nodes.map((node) =>
      ({ ...node, selected: node.id === "second" })))}>选择第二张</button>
    <button type="button" onClick={() => setNodes((nodes) => nodes.map((node) =>
      ({ ...node, selected: true })))}>多选</button>
    <button type="button" onClick={() => setNodes((nodes) => nodes.map((node) =>
      ({ ...node, selected: false })))}>取消选择</button>
  </>;
}

function showCanvas() {
  return render(<ReactFlowProvider><CanvasControls />
    <div style={{ width: 1000, height: 800 }}><ReactFlow<FrameNode> defaultNodes={initialNodes}
      nodeTypes={nodeTypes} defaultViewport={{ x: 0, y: 0, zoom: 1 }} /></div>
  </ReactFlowProvider>);
}

function toolbar() {
  return screen.getByRole("button", { name: "编辑图片" }).closest(".react-flow__node-toolbar");
}

describe("ArtifactCardFrame toolbar", () => {
  it("stays above its node as the node moves and the viewport pans and zooms", async () => {
    showCanvas();
    await waitFor(() => expect(toolbar()).toHaveStyle({
      transform: "translate(220px, 168px) translate(-50%, -100%)",
    }));
    await clickControl(screen.getByRole("button", { name: "移动节点" }));
    await waitFor(() => expect(toolbar()).toHaveStyle({
      transform: "translate(520px, 318px) translate(-50%, -100%)",
    }));
    await clickControl(screen.getByRole("button", { name: "平移并缩放" }));
    await waitFor(() => expect(toolbar()).toHaveStyle({
      transform: "translate(860px, 533px) translate(-50%, -100%)",
    }));
  });

  it("moves to the selected node and hides for empty and multiple selections", async () => {
    showCanvas();
    await screen.findByRole("button", { name: "编辑图片" });
    expect(toolbar()).toHaveAttribute("data-id", "first");
    await clickControl(screen.getByRole("button", { name: "选择第二张" }));
    await waitFor(() => expect(toolbar()).toHaveAttribute("data-id", "second"));
    expect(toolbar()).toHaveStyle({ transform: "translate(820px, 68px) translate(-50%, -100%)" });
    await clickControl(screen.getByRole("button", { name: "多选" }));
    await waitFor(() => expect(screen.queryByRole("button", { name: "编辑图片" })).not.toBeInTheDocument());
    await clickControl(screen.getByRole("button", { name: "取消选择" }));
    expect(screen.queryByRole("button", { name: "编辑图片" })).not.toBeInTheDocument();
  });
});
