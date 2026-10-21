import { fireEvent,render,screen,within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe,expect,it,vi } from "vitest";
import { CanvasToolMenu } from "./CanvasToolMenu";

describe("CanvasToolMenu", () => {
  it("offers both tools directly and preserves add and settings actions", () => {
    const onToolChange = vi.fn();
    const onAdd = vi.fn();
    render(<CanvasToolMenu tool="select" spaceHeld={false} onToolChange={onToolChange} onAdd={onAdd}>
      <button type="button">画布设置</button>
    </CanvasToolMenu>);
    const tools = screen.getByRole("radiogroup", { name: "画布工具模式" });
    expect(within(tools).getByRole("radio", { name: "选择工具" })).toBeChecked();
    expect(within(tools).getByRole("radio", { name: "手形工具" })).not.toBeChecked();
    fireEvent.click(within(tools).getByRole("radio", { name: "手形工具" }));
    expect(onToolChange).toHaveBeenCalledWith("hand");
    fireEvent.click(screen.getByRole("button", { name: "添加卡片" }));
    expect(onAdd).toHaveBeenCalledOnce();
    expect(screen.getByRole("button", { name: "画布设置" })).toBeVisible();
  });

  it("keeps a tool selected when its active item is clicked again", () => {
    const onToolChange = vi.fn();
    render(<CanvasToolMenu tool="select" spaceHeld={false} onToolChange={onToolChange} onAdd={vi.fn()} />);
    fireEvent.click(screen.getByRole("radio", { name: "选择工具" }));
    expect(onToolChange).not.toHaveBeenCalled();
    expect(screen.getByRole("radio", { name: "选择工具" })).toBeChecked();
  });

  it("reflects temporary Space panning without changing the chosen tool", () => {
    const onToolChange = vi.fn();
    const props = { tool: "select" as const, onToolChange, onAdd: vi.fn() };
    const { rerender } = render(<CanvasToolMenu {...props} spaceHeld={false} />);
    rerender(<CanvasToolMenu {...props} spaceHeld />);
    expect(screen.getByRole("radio", { name: "手形工具" })).toBeChecked();
    expect(screen.getByRole("radio", { name: "选择工具" })).not.toBeChecked();
    rerender(<CanvasToolMenu {...props} spaceHeld={false} />);
    expect(screen.getByRole("radio", { name: "选择工具" })).toBeChecked();
    expect(onToolChange).not.toHaveBeenCalled();
  });

  it("supports arrow-key navigation and keyboard tool activation", async () => {
    const user = userEvent.setup();
    const onToolChange = vi.fn();
    render(<CanvasToolMenu tool="select" spaceHeld={false} onToolChange={onToolChange} onAdd={vi.fn()} />);
    await user.tab();
    expect(screen.getByRole("button", { name: "添加卡片" })).toHaveFocus();
    await user.tab();
    expect(screen.getByRole("radio", { name: "选择工具" })).toHaveFocus();
    await user.keyboard("{ArrowDown}");
    expect(screen.getByRole("radio", { name: "手形工具" })).toHaveFocus();
    await user.keyboard("{Enter}");
    expect(onToolChange).toHaveBeenCalledWith("hand");
  });
});
