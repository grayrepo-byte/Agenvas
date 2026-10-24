import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { ReactFlow, ReactFlowProvider } from "@xyflow/react";
import { describe, expect, it, vi } from "vitest";
import { t } from "../../shared/i18n";
import { clickControl } from "../../test/controls";
import { CanvasGroupRenameDialog } from "./CanvasGroupActions";
import { CanvasGroupNode, type CanvasGroupFlowNode, type CanvasGroupNodeData } from "./CanvasGroupNode";
import { CANVAS_GROUP_INPUT_HANDLE, CANVAS_GROUP_OUTPUT_HANDLE, canvasGroupNodeId } from "./canvasGroups";

// Handle geometry is exercised in native Chromium; jsdom has no DOMMatrix implementation.
const updateNodeInternals = vi.hoisted(() => vi.fn());
vi.mock("@xyflow/react", async (importOriginal) => ({
  ...await importOriginal<typeof import("@xyflow/react")>(),
  useUpdateNodeInternals: () => updateNodeInternals,
}));

describe("canvas group name form", () => {
  it("validates trimmed Unicode character count and submits a trimmed name", async () => {
    const onSave = vi.fn();
    render(<CanvasGroupRenameDialog mode="create" onSave={onSave} onClose={vi.fn()} />);
    const name = screen.getByRole("textbox", { name: t("canvas.group.name") });
    await clickControl(screen.getByRole("button", { name: t("canvas.group.save") }));
    expect(screen.getByRole("alert")).toHaveTextContent(t("canvas.group.invalidName"));
    expect(name).toHaveAttribute("aria-invalid", "true");
    fireEvent.change(name, { target: { value: "😀".repeat(81) } });
    await clickControl(screen.getByRole("button", { name: t("canvas.group.save") }));
    expect(onSave).not.toHaveBeenCalled();
    fireEvent.change(name, { target: { value: `  ${"😀".repeat(80)}  ` } });
    await clickControl(screen.getByRole("button", { name: t("canvas.group.save") }));
    expect(onSave).toHaveBeenCalledWith("😀".repeat(80));
  });

  it("retains the editable name and displays a failed save", async () => {
    const onSave = vi.fn().mockRejectedValueOnce(new Error("version conflict")).mockResolvedValueOnce(undefined);
    render(<CanvasGroupRenameDialog mode="rename" initialName="原始名称" onSave={onSave} onClose={vi.fn()} />);
    const name = screen.getByRole("textbox", { name: t("canvas.group.name") });
    fireEvent.change(name, { target: { value: "修改后的名称" } });
    await clickControl(screen.getByRole("button", { name: t("canvas.group.save") }));
    expect(await screen.findByRole("alert")).toHaveTextContent("version conflict");
    expect(name).toHaveValue("修改后的名称");
    await clickControl(screen.getByRole("button", { name: t("canvas.group.save") }));
    expect(onSave).toHaveBeenNthCalledWith(2, "修改后的名称");
  });

  it("prevents duplicate submission, cancellation and Escape during a pending save", async () => {
    let finish: () => void = () => {};
    const onSave = vi.fn(() => new Promise<void>((resolve) => { finish = resolve; }));
    const onClose = vi.fn();
    render(<CanvasGroupRenameDialog mode="rename" initialName="测试分组" onSave={onSave} onClose={onClose} />);
    const save = screen.getByRole("button", { name: t("canvas.group.save") });
    await clickControl(save);
    expect(screen.getByRole("button", { name: t("common.saving") })).toBeDisabled();
    expect(screen.getByRole("textbox")).toBeDisabled();
    await clickControl(screen.getByRole("button", { name: t("common.cancel") }));
    fireEvent.keyDown(screen.getByRole("dialog"), { key: "Escape" });
    fireEvent.submit(screen.getByRole("textbox").closest("form")!);
    expect(onSave).toHaveBeenCalledTimes(1);
    expect(onClose).not.toHaveBeenCalled();
    finish();
    await waitFor(() => expect(screen.getByRole("button", { name: t("canvas.group.save") })).toBeEnabled());
  });

  it("freezes a failed request's name and explicitly retries the same intent while allowing cancellation", async () => {
    const onSave = vi.fn().mockRejectedValueOnce(new Error("synthetic unavailable")).mockResolvedValueOnce(undefined);
    const onClose = vi.fn();
    const editor = render(<CanvasGroupRenameDialog mode="rename" initialName="原名称" onSave={onSave} onClose={onClose} />);
    const name = screen.getByRole("textbox", { name: t("canvas.group.name") });
    fireEvent.change(name, { target: { value: "待重试的名称" } });
    await clickControl(screen.getByRole("button", { name: t("canvas.group.save") }));
    expect(await screen.findByRole("alert")).toHaveTextContent("synthetic unavailable");
    editor.rerender(<CanvasGroupRenameDialog mode="rename" initialName="原名称" retrying onSave={onSave} onClose={onClose} />);
    expect(name).toHaveValue("待重试的名称");
    expect(name).toBeDisabled();
    expect(screen.getByRole("button", { name: t("common.cancel") })).toBeEnabled();
    expect(screen.queryByRole("button", { name: t("canvas.group.save") })).not.toBeInTheDocument();
    await clickControl(screen.getByRole("button", { name: t("canvas.edit.retry") }));
    expect(onSave).toHaveBeenNthCalledWith(2, "待重试的名称");
    await waitFor(() => expect(screen.getByRole("button", { name: t("common.cancel") })).toBeEnabled());
    await clickControl(screen.getByRole("button", { name: t("common.cancel") }));
    expect(onClose).toHaveBeenCalledOnce();
  });
});

const nodeTypes = { canvasGroup: CanvasGroupNode };
function renderGroup(data: Partial<CanvasGroupNodeData> = {}) {
  const callbacks = { onAdd: vi.fn(), onRename: vi.fn(), onToggle: vi.fn(), onDuplicate: vi.fn(), onUngroup: vi.fn() };
  const node: CanvasGroupFlowNode = { id: canvasGroupNodeId("group-1"), type: "canvasGroup", position: { x: 0, y: 0 },
    width: 400, height: 300, selected: true, data: { groupId: "group-1", name: "镜头组", memberCount: 3, collapsed: false,
      ...callbacks, ...data } };
  render(<ReactFlowProvider><div style={{ width: 1000, height: 800 }}>
    <ReactFlow nodes={[node]} nodeTypes={nodeTypes} /></div></ReactFlowProvider>);
  return callbacks;
}

describe("canvas group node", () => {
  it("provides accessible collapse and menu commands using the persistent group id", async () => {
    const callbacks = renderGroup();
    await clickControl(await screen.findByRole("button", { name: t("canvas.group.collapse") }));
    expect(callbacks.onToggle).toHaveBeenCalledWith("group-1");
    expect(screen.getByLabelText(t("canvas.group.memberCount", { count: 3 }))).toBeInTheDocument();
    const add = screen.getByRole("button", { name: t("canvas.group.addNode") });
    await clickControl(add);
    expect(callbacks.onAdd).toHaveBeenCalledWith("group-1", add);
    for (const [key, callback] of [["canvas.group.rename", callbacks.onRename], ["canvas.group.duplicate", callbacks.onDuplicate],
      ["canvas.group.ungroup", callbacks.onUngroup]] as const) {
      await clickControl(screen.getByRole("button", { name: t("canvas.group.actions") }));
      await clickControl(await screen.findByRole("menuitem", { name: t(key) }));
      expect(callback).toHaveBeenCalledWith("group-1");
    }
  });

  it("exposes non-connectable fixed handles while collapsed and disables edits while busy", async () => {
    renderGroup({ collapsed: true, disabled: true });
    expect(await screen.findByRole("button", { name: t("canvas.group.expand") })).toBeDisabled();
    expect(screen.getByRole("button", { name: t("canvas.group.actions") })).toBeDisabled();
    expect(screen.getByRole("button", { name: t("canvas.group.addNode") })).toBeDisabled();
    expect(document.querySelector(`[data-handleid='${CANVAS_GROUP_INPUT_HANDLE}']`)).toBeInTheDocument();
    expect(document.querySelector(`[data-handleid='${CANVAS_GROUP_OUTPUT_HANDLE}']`)).toBeInTheDocument();
    expect(document.querySelectorAll(".canvas-group__relation-handle.connectable")).toHaveLength(0);
    expect(updateNodeInternals).toHaveBeenCalledWith(canvasGroupNodeId("group-1"));
  });
});
