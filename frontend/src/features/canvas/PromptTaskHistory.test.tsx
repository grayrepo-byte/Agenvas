import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import type { Task } from "../../shared/api/client";
import { server } from "../../test/server";
import { useImageReversePrompt } from "./useImageReversePrompt";
import { VideoPromptOptimizationDialog } from "./VideoPromptOptimizationDialog";

const configurations = [
  {
    kind: "image",
    path: "/api/v1/projects/p/artifacts/a/image-prompts",
    key: ["image-prompt-tasks", "p", "i"],
    start: "提示词反推",
    again: "排队中",
    result: "反推提示词",
  },
  {
    kind: "video",
    path: "/api/v1/projects/p/canvas-items/i/video-prompt-optimizations",
    key: ["video-prompt-tasks", "p", "i"],
    start: "开始优化",
    again: "重新优化",
    result: "优化结果",
  },
] as const;
type Configuration = (typeof configurations)[number];
function task(config: Configuration, changes: Partial<Task> = {}): Task {
  return {
    id: "task-one",
    projectId: "p",
    runId: null,
    stepKey: "synthetic-prompt",
    kind: "TEXT_GENERATION",
    status: "READY",
    cancelRequested: false,
    input:
      config.kind === "image"
        ? { sourceVersionId: "v", purpose: "IMAGE_REVERSE_PROMPT" }
        : { prompt: "Synthetic original", purpose: "VIDEO_PROMPT_OPTIMIZATION" },
    output: null,
    attemptNo: 1,
    version: 1,
    nextActionAt: "2026-11-04T00:00:00Z",
    createdAt: "2026-11-04T00:00:00Z",
    updatedAt: "2026-11-04T00:00:00Z",
    ...changes,
  };
}
function ImageAction() {
  const action = useImageReversePrompt({
    projectId: "p",
    artifactId: "a",
    canvasItemId: "i",
    request: { canvasItemId: "i", sourceVersionId: "v", expectedCanvasItemVersion: 1 },
    enabled: true,
  });
  return (
    <>
      {action.controls}
      {action.feedback}
    </>
  );
}
function show(config: Configuration) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: Infinity }, mutations: { retry: false } },
  });
  server.use(
    http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "synthetic" })),
    http.get("/api/v1/skills", () => HttpResponse.json({ items: [], nextCursor: null, total: 0 })),
  );
  render(
    <QueryClientProvider client={client}>
      {config.kind === "image" ? (
        <ImageAction />
      ) : (
        <VideoPromptOptimizationDialog
          projectId="p"
          canvasItemId="i"
          prompt="Synthetic original"
          maxLength={20000}
          onApply={() => true}
          onClose={() => {}}
        />
      )}
    </QueryClientProvider>,
  );
  return client;
}

describe.each(configurations)("$kind prompt task acknowledgements", config => {
  it("retains an accepted task when an older empty history read finishes later", async () => {
    let reads = 0,
      writes = 0,
      release = () => {};
    const gate = new Promise<void>(resolve => {
      release = resolve;
    });
    server.use(
      http.get(config.path, async () => {
        if (++reads > 1) await gate;
        return HttpResponse.json([]);
      }),
      http.post(config.path, () => {
        writes++;
        return HttpResponse.json(task(config));
      }),
    );
    const client = show(config);
    const start = await screen.findByRole("button", { name: config.start });
    await waitFor(() => expect(start).toBeEnabled());
    let read: Promise<void> = Promise.resolve();
    act(() => {
      read = client.invalidateQueries({ queryKey: config.key });
    });
    await waitFor(() => expect(reads).toBe(2));
    await userEvent.click(start);
    await waitFor(() => expect(screen.getByRole("button", { name: config.again })).toBeDisabled());
    await act(async () => {
      release();
      await read;
    });
    expect(client.getQueryData<Task[]>(config.key)?.map(entry => entry.id)).toContain("task-one");
    expect(screen.getByRole("button", { name: config.again })).toBeDisabled();
    expect(writes).toBe(1);
  });

  it("retains a newer completed version and edited text when acceptance arrives late", async () => {
    let writes = 0,
      release = () => {};
    const gate = new Promise<void>(resolve => {
      release = resolve;
    });
    server.use(
      http.get(config.path, () => HttpResponse.json([])),
      http.post(config.path, async () => {
        writes++;
        await gate;
        return HttpResponse.json(task(config));
      }),
    );
    const client = show(config);
    const start = await screen.findByRole("button", { name: config.start });
    await waitFor(() => expect(start).toBeEnabled());
    await userEvent.click(start);
    await waitFor(() => expect(writes).toBe(1));
    act(() => {
      client.setQueryData(config.key, [
        task(config, {
          status: "SUCCEEDED",
          version: 3,
          output: { prompt: "Completed synthetic result", mock: false },
        }),
      ]);
    });
    if (config.kind === "video") {
      const result = await screen.findByRole("textbox", { name: config.result });
      fireEvent.change(result, { target: { value: "User edited result" } });
    }
    await act(async () => release());
    await waitFor(() => expect(client.isMutating()).toBe(0));
    if (config.kind === "video")
      expect(screen.getByRole("textbox", { name: config.result })).toHaveValue("User edited result");
    expect(client.getQueryData<Task[]>(config.key)?.[0]?.version).toBe(3);
    expect(writes).toBe(1);
  });

  it.each([
    {
      name: "known historical task",
      oldTime: "2026-11-04T00:00:00Z",
      newTime: "2026-11-04T00:01:00Z",
      oldId: "task-one",
      newId: "task-two",
      includeOld: true,
    },
    {
      name: "historical task outside cached history",
      oldTime: "2026-11-04T00:00:00Z",
      newTime: "2026-11-04T00:01:00Z",
      oldId: "task-one",
      newId: "task-two",
      includeOld: false,
    },
    {
      name: "microseconds before UUID order",
      oldTime: "2026-11-04T00:00:00.000001Z",
      newTime: "2026-11-04T00:00:00.000002Z",
      oldId: "ffffffff-ffff-ffff-ffff-ffffffffffff",
      newId: "00000000-0000-0000-0000-000000000000",
      includeOld: true,
    },
    {
      name: "equal instant with different fractional precision",
      oldTime: "2026-11-04T00:00:00.1Z",
      newTime: "2026-11-04T00:00:00.100000Z",
      oldId: "00000000-0000-0000-0000-000000000000",
      newId: "ffffffff-ffff-ffff-ffff-ffffffffffff",
      includeOld: true,
    },
  ])("keeps server task order after replay: $name", async ({ oldTime, newTime, oldId, newId, includeOld }) => {
    let writes = 0,
      release = () => {};
    const gate = new Promise<void>(resolve => {
      release = resolve;
    });
    const old = task(config, {
      id: oldId,
      createdAt: oldTime,
      status: "SUCCEEDED",
      output: { prompt: "Older result", mock: false },
    });
    server.use(
      http.get(config.path, () => HttpResponse.json([])),
      http.post(config.path, async () => {
        writes++;
        await gate;
        return HttpResponse.json(old);
      }),
    );
    const client = show(config);
    const start = await screen.findByRole("button", { name: config.start });
    await waitFor(() => expect(start).toBeEnabled());
    await userEvent.click(start);
    await waitFor(() => expect(writes).toBe(1));
    const newer = task(config, {
      id: newId,
      status: "SUCCEEDED",
      version: 4,
      createdAt: newTime,
      output: { prompt: "Newer result", mock: false },
    });
    act(() => {
      client.setQueryData(config.key, includeOld ? [newer, old] : [newer]);
    });
    if (config.kind === "video") await screen.findByDisplayValue("Newer result");
    await act(async () => release());
    await waitFor(() => expect(client.isMutating()).toBe(0));
    if (config.kind === "video")
      expect(screen.getByRole("textbox", { name: config.result })).toHaveValue("Newer result");
    expect(client.getQueryData<Task[]>(config.key)?.map(entry => entry.id)).toEqual([newId, oldId]);
  });

  it("does not promote a delayed cancellation over a newer completed task when refresh fails", async () => {
    let reads = 0,
      cancels = 0,
      release = () => {};
    const gate = new Promise<void>(resolve => {
      release = resolve;
    });
    const canceled = task(config, { status: "CANCELED", cancelRequested: true, version: 2 });
    server.use(
      http.get(config.path, () =>
        ++reads === 1
          ? HttpResponse.json([task(config, { status: "RUNNING" })])
          : new HttpResponse(null, { status: 503 }),
      ),
      http.post(`${config.path}/task-one/cancel`, async () => {
        cancels++;
        await gate;
        return HttpResponse.json(canceled);
      }),
    );
    const client = show(config);
    await userEvent.click(await screen.findByRole("button", { name: "取消" }));
    await waitFor(() => expect(cancels).toBe(1));
    const newer = task(config, {
      id: "task-two",
      status: "SUCCEEDED",
      version: 3,
      createdAt: "2026-11-04T00:01:00Z",
      output: { prompt: "Newer completed task", mock: false },
    });
    act(() => {
      client.setQueryData(config.key, [newer, canceled]);
    });
    await act(async () => release());
    await waitFor(() => expect(client.isMutating()).toBe(0));
    await screen.findByRole("alert");
    if (config.kind === "video")
      expect(screen.getByRole("textbox", { name: config.result })).toHaveValue("Newer completed task");
    expect(client.getQueryData<Task[]>(config.key)?.map(entry => entry.id)).toEqual(["task-two", "task-one"]);
    expect(cancels).toBe(1);
  });
});
