import { Position, type EdgeProps } from "@xyflow/react";
import { render } from "@testing-library/react";
import { expect, it } from "vitest";
import { CanvasRelationEdge, type CanvasDisplayEdge } from "./CanvasRelationEdge";

it("uses the actual directed Bezier path for light while retaining the interaction path when disabled", () => {
  const props: EdgeProps<CanvasDisplayEdge> = { id: "relation", source: "parent", target: "child",
    sourceX: 500, sourceY: 300, targetX: 100, targetY: 100,
    sourcePosition: Position.Right, targetPosition: Position.Left,
    data: { connectionFlowEnabled: true } };
  const { container, rerender } = render(<svg><CanvasRelationEdge {...props} /></svg>);
  const path = container.querySelector(".react-flow__edge-path")?.getAttribute("d");
  expect(path).toMatch(/^M500,300/);
  expect(path).toMatch(/100,100$/);
  const lights = container.querySelectorAll(".canvas-relation-flow path");
  expect(lights).toHaveLength(3);
  for (const light of lights) expect(light.getAttribute("d")).toBe(path);
  expect(container.querySelector(".canvas-relation-flow")).toHaveAttribute("pointer-events", "none");
  rerender(<svg><CanvasRelationEdge {...props} data={{ connectionFlowEnabled: false }} /></svg>);
  expect(container.querySelector(".canvas-relation-flow")).toBeNull();
  expect(container.querySelector(".react-flow__edge-path")).toHaveAttribute("d", path!);
  expect(container.querySelector(".react-flow__edge-interaction")).not.toBeNull();
});
