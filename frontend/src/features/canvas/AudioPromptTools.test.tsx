import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import { AudioPromptTools } from "./AudioPromptTools";
function tools(prompt = "", hasMentions = false) {
  const onApply = vi.fn();
  const client = createQueryClient();
  const props = { projectId: "project-1", canvasItemId: "audio-card", prompt, hasMentions, onApply };
  const view = render(<QueryClientProvider client={client}><AudioPromptTools {...props} /></QueryClientProvider>);
  return { onApply, changed: (next: string) => view.rerender(<QueryClientProvider client={client}><AudioPromptTools {...props} prompt={next} /></QueryClientProvider>) };
}
describe("audio prompt tools", () => {
  it("previews a sound template and only applies it when requested", async () => {
    const user = userEvent.setup(); const { onApply } = tools();
    await user.click(screen.getByRole("button", { name: "音频提示词模板" }));
    await user.click(screen.getByRole("button", { name: "环境音" }));
    expect(onApply).not.toHaveBeenCalled();
    expect((screen.getByRole("textbox", { name: "完整音频提示词" }) as HTMLTextAreaElement).value).toContain("清晨的森林");
    await user.click(screen.getByRole("button", { name: "应用提示词" }));
    expect(onApply).toHaveBeenCalledWith(expect.stringContaining("没有对白"));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });
  it("protects a prompt changed while the expanded editor is open", async () => {
    const user = userEvent.setup(); const { changed, onApply } = tools("开始");
    await user.click(screen.getByRole("button", { name: "展开音频提示词" }));
    await user.clear(screen.getByRole("textbox", { name: "完整音频提示词" }));
    await user.type(screen.getByRole("textbox", { name: "完整音频提示词" }), "新的声音描述");
    changed("另一用户修改");
    expect(screen.getByRole("button", { name: "应用提示词" })).toBeDisabled();
    expect(onApply).not.toHaveBeenCalled();
  });
  it("keeps structured media mentions in the original editor", async () => {
    const user = userEvent.setup(); tools("引用 \uFFFC", true);
    await user.click(screen.getByRole("button", { name: "展开音频提示词" }));
    expect(screen.getByRole("button", { name: "应用提示词" })).toBeDisabled();
    expect(screen.getByRole("alert")).toHaveTextContent("保留精确引用");
  });
});
