import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { CanvasViewportControls } from "./CanvasViewportControls";

const viewport = vi.hoisted(() => ({ transform: [0, 0, 0.67], minZoom: 0.25, maxZoom: 2 }));
const flow = vi.hoisted(() => ({ zoomIn: vi.fn(), zoomOut: vi.fn(), zoomTo: vi.fn(), fitView: vi.fn() }));
vi.mock("@xyflow/react", () => ({
  useReactFlow: () => flow,
  useStore: (selector: (state: typeof viewport) => unknown) => selector(viewport),
  MiniMap: () => <div role="img" aria-label="导航概览" />,
}));
beforeEach(() => {
  vi.clearAllMocks();
  viewport.transform = [0, 0, 0.67];
});
afterEach(() => vi.unstubAllGlobals());

it("shows the actual zoom, resets it, fits the canvas, and opens/closes the overview", async () => {
  const user = userEvent.setup();
  render(<CanvasViewportControls hasNodes />);
  expect(screen.getByRole("button", { name: "回到 100%" })).toHaveTextContent("67%");
  await user.click(screen.getByRole("button", { name: "回到 100%" }));
  expect(flow.zoomTo).toHaveBeenCalledWith(1, { duration: 220 });
  await user.click(screen.getByRole("button", { name: "适应画布" }));
  expect(flow.fitView).toHaveBeenCalledWith({ padding: 0.2, maxZoom: 1, duration: 220 });
  const overview = screen.getByRole("button", { name: "画布缩略图" });
  expect(overview).toHaveAttribute("aria-pressed", "false");
  await user.click(overview);
  expect(overview).toHaveAttribute("aria-pressed", "true");
  expect(screen.getByRole("img", { name: "导航概览" })).toBeVisible();
  await user.click(overview);
  expect(screen.queryByRole("img", { name: "导航概览" })).not.toBeInTheDocument();
});

it("respects zoom limits and disables navigation that needs nodes on an empty canvas", async () => {
  viewport.transform = [0, 0, 0.25];
  const view = render(<CanvasViewportControls hasNodes={false} />);
  expect(screen.getByRole("button", { name: "缩小画布" })).toBeDisabled();
  expect(screen.getByRole("button", { name: "适应画布" })).toBeDisabled();
  expect(screen.getByRole("button", { name: "画布缩略图" })).toBeDisabled();
  viewport.transform = [0, 0, 2];
  view.rerender(<CanvasViewportControls hasNodes />);
  expect(screen.getByRole("button", { name: "放大画布" })).toBeDisabled();
  expect(screen.getByRole("button", { name: "缩小画布" })).toBeEnabled();
});

it("uses immediate viewport changes when the system requests reduced motion", async () => {
  vi.stubGlobal("matchMedia", vi.fn(() => ({ matches: true })));
  const user = userEvent.setup();
  render(<CanvasViewportControls hasNodes />);
  await user.click(screen.getByRole("button", { name: "放大画布" }));
  expect(flow.zoomIn).toHaveBeenCalledWith({ duration: 0 });
  await user.click(screen.getByRole("button", { name: "缩小画布" }));
  expect(flow.zoomOut).toHaveBeenCalledWith({ duration: 0 });
});
