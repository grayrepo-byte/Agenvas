import { QueryClientProvider } from "@tanstack/react-query";
import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it, vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import { setLocale, t } from "../../shared/i18n";
import { server } from "../../test/server";
import { AudioPromptTools } from "./AudioPromptTools";
function tools(prompt = "", hasMentions = false) {
  const onApply = vi.fn();
  const client = createQueryClient();
  const props = { projectId: "project-1", canvasItemId: "audio-card", prompt, hasMentions, onApply };
  const view = render(
    <QueryClientProvider client={client}>
      <AudioPromptTools {...props} />
    </QueryClientProvider>,
  );
  return {
    onApply,
    changed: (next: string) =>
      view.rerender(
        <QueryClientProvider client={client}>
          <AudioPromptTools {...props} prompt={next} />
        </QueryClientProvider>,
      ),
  };
}
describe("audio prompt tools", () => {
  it.each(["artifact", "generation"])(
    "recovers a lost %s response with the original request after changing interface language",
    async lostStep => {
      setLocale("en");
      const requests = {
        artifact: [] as { key: string | null; body: unknown }[],
        generation: [] as { key: string | null; body: unknown }[],
      };
      server.use(
        http.get("/api/v1/settings/llm", () => HttpResponse.json({ configured: false })),
        http.get("/api/v1/settings/diagnostics", () => HttpResponse.json({ llmMode: "MOCK" })),
        http.get("/api/v1/auth/csrf", () => HttpResponse.json({ token: "synthetic-csrf", headerName: "X-CSRF-TOKEN" })),
        http.post("/api/v1/projects/project-1/artifacts", async ({ request }) => {
          requests.artifact.push({ key: request.headers.get("Idempotency-Key"), body: await request.json() });
          if (lostStep === "artifact" && requests.artifact.length === 1) return HttpResponse.error();
          return HttpResponse.json({ id: "helper", version: 0, resourceDefaultVersionId: "empty-text" });
        }),
        http.get("/api/v1/projects/project-1/canvas/items", () => HttpResponse.json({ items: [] })),
        http.post("/api/v1/projects/project-1/canvas/commands", () => HttpResponse.json({ items: [] })),
        http.post("/api/v1/projects/project-1/artifacts/helper/text-generations", async ({ request }) => {
          requests.generation.push({ key: request.headers.get("Idempotency-Key"), body: await request.json() });
          if (lostStep === "generation" && requests.generation.length === 1) return HttpResponse.error();
          return HttpResponse.json({ id: "task", status: "READY" });
        }),
        http.get("/api/v1/projects/project-1/artifacts/helper/text-generations", () => HttpResponse.json([])),
      );
      tools("Keep this original user text.");
      const user = userEvent.setup();
      await user.click(screen.getByRole("button", { name: t("audio.promptTools.assistantTitle") }));
      await user.click(await screen.findByRole("button", { name: t("audio.promptTools.generate") }));
      await screen.findByRole("button", { name: t("audio.promptTools.retry") });
      act(() => {
        setLocale("ja");
      });
      await user.click(screen.getByRole("button", { name: t("audio.promptTools.retry") }));
      await waitFor(() => expect(screen.getByRole("button", { name: t("audio.promptTools.generate") })).toBeDisabled());
      const attempts = requests[lostStep as keyof typeof requests];
      expect(attempts).toHaveLength(2);
      expect(attempts[1]).toEqual(attempts[0]);
      expect(requests.generation[0]?.body).toMatchObject({
        prompt: expect.stringContaining("Keep this original user text."),
      });
    },
  );
  it.each(["assist", "translate"])(
    "uses an English built-in %s instruction while preserving the user's text",
    async mode => {
      setLocale("en");
      const prompts: string[] = [];
      server.use(
        http.get("/api/v1/settings/llm", () => HttpResponse.json({ configured: false })),
        http.get("/api/v1/settings/diagnostics", () => HttpResponse.json({ llmMode: "MOCK" })),
        http.get("/api/v1/auth/csrf", () => HttpResponse.json({ token: "synthetic-csrf", headerName: "X-CSRF-TOKEN" })),
        http.post("/api/v1/projects/project-1/artifacts", () =>
          HttpResponse.json({ id: "helper", version: 0, resourceDefaultVersionId: "empty-text" }),
        ),
        http.get("/api/v1/projects/project-1/canvas/items", () => HttpResponse.json({ items: [] })),
        http.post("/api/v1/projects/project-1/canvas/commands", () => HttpResponse.json({ items: [] })),
        http.post("/api/v1/projects/project-1/artifacts/helper/text-generations", async ({ request }) => {
          prompts.push(((await request.json()) as { prompt: string }).prompt);
          return HttpResponse.json({ id: "task", status: "READY" });
        }),
        http.get("/api/v1/projects/project-1/artifacts/helper/text-generations", () => HttpResponse.json([])),
      );
      const text = "用户的中文原文 {0}";
      tools(text);
      const user = userEvent.setup();
      await user.click(
        screen.getByRole("button", {
          name: t(mode === "assist" ? "audio.promptTools.assistantTitle" : "audio.promptTools.translateTitle"),
        }),
      );
      await user.click(await screen.findByRole("button", { name: t("audio.promptTools.generate") }));
      await waitFor(() => expect(prompts).toHaveLength(1));
      const [instruction, content] = prompts[0]!.split("\n\n");
      expect(instruction).not.toMatch(/\p{Script=Han}/u);
      expect(instruction).toContain("3000");
      expect(content).toBe(text);
    },
  );
  it("provides audio prompt editing tools without a template entry", () => {
    tools();
    expect(screen.queryByRole("button", { name: /模板/ })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "音频提示词助手" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "翻译音频提示词" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "展开音频提示词" })).toBeInTheDocument();
  });
  it("protects a prompt changed while the expanded editor is open", async () => {
    const user = userEvent.setup();
    const { changed, onApply } = tools("开始");
    await user.click(screen.getByRole("button", { name: "展开音频提示词" }));
    await user.clear(screen.getByRole("textbox", { name: "完整音频提示词" }));
    await user.type(screen.getByRole("textbox", { name: "完整音频提示词" }), "新的声音描述");
    changed("另一用户修改");
    expect(screen.getByRole("button", { name: "应用提示词" })).toBeDisabled();
    expect(onApply).not.toHaveBeenCalled();
  });
  it("keeps structured media mentions in the original editor", async () => {
    const user = userEvent.setup();
    tools("引用 \uFFFC", true);
    await user.click(screen.getByRole("button", { name: "展开音频提示词" }));
    expect(screen.getByRole("button", { name: "应用提示词" })).toBeDisabled();
    expect(screen.getByRole("alert")).toHaveTextContent("保留精确引用");
  });
});
