import { fireEvent, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import type { CallDebug } from "../../shared/api/client";
import { setLocale } from "../../shared/i18n";
import { FormattedCallExchange } from "./FormattedCallExchange";
import { RAW_PAGE_CHARS } from "./callLogFormatting";

const request = { model: "fixture-model", stream: false, messages: [
  { role: "system", content: "You write <script>safe text</script>." },
  { role: "user", content: [{ type: "text", text: "Describe the scene." }, { type: "image_url", image_url: { url: "https://media.invalid/p.png" } }] },
  { role: "assistant", tool_calls: [{ id: "call-a", function: { name: "readCanvas", arguments: '{"itemId":"card-1"}' } }] },
  { role: "tool", tool_call_id: "call-a", content: '{"result":"A quiet sea"}' },
] };
const response = { id: "gen-123", model: "fixture-model", choices: [{ message: { role: "assistant", content: "The waves drift ashore." }, finish_reason: "stop" }], usage: { prompt_tokens: 28, completion_tokens: 9, total_tokens: 37, cost: "0.001" } };
const exchange: CallDebug["exchanges"][number] = { method: "POST", url: "https://provider.invalid/v1/chat/completions", responseStatus: 200,
  requestBody: { content: JSON.stringify(request), encoding: "UTF8", truncated: false },
  responseBody: { content: JSON.stringify(response), encoding: "UTF8", truncated: false } };

describe("FormattedCallExchange", () => {
  it("shows LLM facts and actual usage; raw mode preserves captured JSON", () => {
    const { rerender } = render(<FormattedCallExchange exchange={exchange} mode="formatted" llm />);
    expect(screen.getByText("gen-123")).toBeInTheDocument();
    expect(screen.getByText("37 tokens")).toBeInTheDocument();
    expect(screen.getByText("0.001")).toBeInTheDocument();
    expect(screen.getByText(/4 条消息/)).toBeInTheDocument();
    rerender(<FormattedCallExchange exchange={exchange} mode="raw" llm />);
    expect(screen.getByText(JSON.stringify(request))).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "展开 Prompt" })).not.toBeInTheDocument();
    expect(document.querySelector("script")).toBeNull();
  });

  it("browses, filters, navigates, copies and switches message JSON with accessible focus restoration", async () => {
    const user = userEvent.setup();
    const copy = vi.spyOn(navigator.clipboard, "writeText");
    render(<FormattedCallExchange exchange={exchange} mode="formatted" llm />);
    const expand = screen.getByRole("button", { name: "展开 Prompt" });
    await user.click(expand);
    const dialog = screen.getByRole("dialog", { name: "Prompt" });
    expect(within(dialog).getByText("第 1 / 4 条")).toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: "下一条消息" }));
    expect(within(dialog).getByText("第 2 / 4 条")).toBeInTheDocument();
    expect(dialog.querySelector("img")).toBeNull();
    await user.type(within(dialog).getByRole("searchbox"), "card-1");
    expect(within(dialog).getByText("工具调用 · readCanvas")).toBeInTheDocument();
    expect(within(dialog).getByText('{ "itemId": "card-1" }')).toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: "复制" }));
    expect(copy).toHaveBeenLastCalledWith('readCanvas\n{\n  "itemId": "card-1"\n}');
    expect(within(dialog).getByRole("status")).toHaveTextContent("已复制");
    await user.click(within(dialog).getByRole("button", { name: "查看消息 JSON" }));
    expect(within(dialog).getByText(/"tool_calls"/)).toBeInTheDocument();
    await user.clear(within(dialog).getByRole("searchbox"));
    await user.selectOptions(within(dialog).getByRole("combobox", { name: "消息角色" }), "tool");
    expect(within(dialog).getByText("第 1 / 1 条")).toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: "查看正文" }));
    expect(within(dialog).getByText('{ "result": "A quiet sea" }')).toBeInTheDocument();
    await user.type(within(dialog).getByRole("searchbox"), "no matches");
    expect(within(dialog).getByRole("status")).toHaveTextContent("没有匹配的消息");
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(expand).toHaveFocus();
  });

  it("reports copy failure and absent response without faking completion", async () => {
    const user = userEvent.setup();
    vi.spyOn(navigator.clipboard, "writeText").mockRejectedValueOnce(new Error("blocked"));
    render(<FormattedCallExchange exchange={{ ...exchange, responseBody: null, responseStatus: null }} mode="formatted" llm />);
    expect(screen.getByRole("button", { name: "展开 Completion" })).toBeDisabled();
    expect(screen.getAllByText("未记录").length).toBeGreaterThan(0);
    await user.click(screen.getByRole("button", { name: "展开 Prompt" }));
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: "复制" }));
    expect(screen.getByRole("status")).toHaveTextContent("复制失败");
  });

  it("formats other JSON but retains unsupported and incomplete raw content", () => {
    const { rerender } = render(<FormattedCallExchange exchange={{ ...exchange, requestBody: { content: '{"size":1}', encoding: "UTF8", truncated: false }, responseBody: { content: "YWJj", encoding: "BASE64", truncated: true } }} mode="formatted" llm={false} />);
    expect(screen.getByText('{ "size": 1 }')).toBeInTheDocument();
    expect(screen.getByText("YWJj")).toBeInTheDocument();
    expect(screen.getByText(/这里只包含已采集部分/)).toBeInTheDocument();
    const content = "x".repeat(RAW_PAGE_CHARS) + "END";
    rerender(<FormattedCallExchange exchange={{ ...exchange, requestBody: { content, encoding: "UTF8", truncated: false }, responseBody: null }} mode="raw" llm={false} />);
    expect(screen.queryByText(/END/)).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /加载更多正文/ }));
    expect(screen.getByText(/END/)).toBeInTheDocument();
  });

  it("localizes display and explorer controls in English", async () => {
    setLocale("en");
    render(<FormattedCallExchange exchange={exchange} mode="formatted" llm />);
    expect(screen.getByText("Finish reason")).toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole("button", { name: "Expand Prompt" }));
    expect(screen.getByRole("searchbox", { name: "Search messages" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "View message JSON" })).toBeInTheDocument();
  });

  it("distinguishes missing tool arguments from JSON null and preserves malformed arguments for copying", async () => {
    const user = userEvent.setup();
    const copy = vi.spyOn(navigator.clipboard, "writeText");
    const body = { messages: [{ role: "assistant", tool_calls: [
      { function: { name: "missing" } },
      { function: { name: "explicitNull", arguments: "null" } },
      { function: { name: "malformed", arguments: "broken{" } },
    ] }] };
    render(<FormattedCallExchange exchange={{ ...exchange, requestBody: { content: JSON.stringify(body), encoding: "UTF8", truncated: false } }} mode="formatted" llm />);
    await user.click(screen.getByRole("button", { name: "展开 Prompt" }));
    const dialog = screen.getByRole("dialog");
    expect(within(dialog).getByText("参数未记录")).toBeInTheDocument();
    expect(within(dialog).getByText("null")).toBeInTheDocument();
    expect(within(dialog).getByText("broken{")).toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: "复制" }));
    expect(copy).toHaveBeenLastCalledWith("missing\n参数未记录\n\nexplicitNull\nnull\n\nmalformed\nbroken{");
  });
});
