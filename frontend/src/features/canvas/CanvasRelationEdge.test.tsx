import { Position, type EdgeProps } from "@xyflow/react";
import { act, render } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { CanvasRelationEdge, type CanvasDisplayEdge } from "./CanvasRelationEdge";

afterEach(() => vi.unstubAllGlobals());

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
  expect(lights).toHaveLength(2);
  for (const light of lights) expect(light.getAttribute("d")).toBe(path);
  expect(container.querySelector("animateMotion")).toHaveAttribute("path", path!);
  expect(container.querySelector(".canvas-relation-flow")).toHaveAttribute("pointer-events", "none");
  rerender(<svg><CanvasRelationEdge {...props} data={{ connectionFlowEnabled: false }} /></svg>);
  expect(container.querySelector(".canvas-relation-flow")).toBeNull();
  expect(container.querySelector(".react-flow__edge-path")).toHaveAttribute("d", path!);
  expect(container.querySelector(".react-flow__edge-interaction")).not.toBeNull();
});

it("uses independent soft gradient masks so multiple edges do not share their moving light", () => {
  const props: EdgeProps<CanvasDisplayEdge> = { id: "first", source: "parent", target: "child",
    sourceX: 0, sourceY: 100, targetX: 400, targetY: 100,
    sourcePosition: Position.Right, targetPosition: Position.Left, data: { connectionFlowEnabled: true } };
  const { container } = render(<svg><CanvasRelationEdge {...props} /><CanvasRelationEdge {...props} id="second" /></svg>);
  const masks = [...container.querySelectorAll("mask")];
  expect(masks).toHaveLength(2);
  expect(new Set(masks.map((mask) => mask.id)).size).toBe(2);
  const stops = [...container.querySelector("radialGradient")!.querySelectorAll("stop")]
    .map((stop) => Number(stop.getAttribute("stop-opacity")));
  expect(stops[0]).toBe(1);
  expect(stops.at(-1)).toBe(0);
  expect(stops.every((value, index) => index === 0 || value < stops[index - 1]!)).toBe(true);
  for (const [index, masked] of [...container.querySelectorAll("g[mask]")].entries()) {
    const bounds = masked.querySelector("rect")!;
    expect(Number(bounds.getAttribute("height"))).toBeGreaterThan(0);
    expect(masked.getAttribute("mask")).toBe(`url(#${masks[index]!.id})`);
  }
});

it("removes the native animation when the system switches to reduced motion, retaining the static line", () => {
  let reduced = false;
  const listeners = new Set<() => void>();
  // jsdom has no matchMedia implementation; expose its change notifications explicitly.
  vi.stubGlobal("matchMedia", vi.fn(() => ({ matches: reduced,
    addEventListener: (_event: string, listener: () => void) => listeners.add(listener),
    removeEventListener: (_event: string, listener: () => void) => listeners.delete(listener),
  })));
  const props: EdgeProps<CanvasDisplayEdge> = { id: "relation", source: "parent", target: "child",
    sourceX: 0, sourceY: 0, targetX: 400, targetY: 0,
    sourcePosition: Position.Right, targetPosition: Position.Left, data: { connectionFlowEnabled: true } };
  const { container } = render(<svg><CanvasRelationEdge {...props} /></svg>);
  expect(container.querySelector("animateMotion")).not.toBeNull();
  act(() => { reduced = true; listeners.forEach((notify) => notify()); });
  expect(container.querySelector("animateMotion")).toBeNull();
  expect(container.querySelector(".react-flow__edge-path")).not.toBeNull();
  act(() => { reduced = false; listeners.forEach((notify) => notify()); });
  expect(container.querySelector("animateMotion")).not.toBeNull();
});
