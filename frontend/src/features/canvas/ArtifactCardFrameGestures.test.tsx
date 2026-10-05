import { createEvent,fireEvent,render,screen } from "@testing-library/react";
import { ReactFlow,type NodeProps } from "@xyflow/react";
import { useState } from "react";
import { describe,expect,it,vi } from "vitest";
import { clickControl } from "../../test/controls";
import { ArtifactCardFrame } from "./ArtifactCardFrame";

const action = vi.fn();
function Card({ selected }: NodeProps) {
  return <ArtifactCardFrame title="图片" kindLabel="图片" selected={selected} locked={false}
    toolbar={<button onClick={action}>查看详情</button>}><p>预览</p></ArtifactCardFrame>;
}
const nodeTypes = { card: Card };
const NODE_SIZE = { width: 260, height: 150 };
function Canvas() {
  const [selected, setSelected] = useState(true);
  return <ReactFlow nodeTypes={nodeTypes}
    nodes={[{ id: "card", type: "card", position: { x: 0, y: 0 }, data: {},
      ...NODE_SIZE, measured: NODE_SIZE, selected }]}
    onMoveStart={(event) => { if (event) setSelected(false); }} />;
}

describe("ArtifactCardFrame toolbar gestures", () => {
  it("keeps the toolbar mounted through mouse down so its action can run", async () => {
    action.mockClear();
    render(<Canvas />);
    const button = await screen.findByRole("button", { name: "查看详情" });
    const down = createEvent.mouseDown(button, { button: 0 });
    Object.defineProperty(down, "view", { value: window });
    fireEvent(button, down);
    expect(button).toBeInTheDocument();
    const up = createEvent.mouseUp(button, { button: 0 });
    Object.defineProperty(up, "view", { value: window });
    fireEvent(button, up);
    await clickControl(button);
    expect(action).toHaveBeenCalledOnce();
  });
});
