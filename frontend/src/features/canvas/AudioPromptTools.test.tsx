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
  it("provides audio prompt editing tools without a template entry", () => {
    tools();
    expect(screen.queryByRole("button", { name: /模板/ })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "音频提示词助手" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "翻译音频提示词" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "展开音频提示词" })).toBeInTheDocument();
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
