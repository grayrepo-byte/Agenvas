import { QueryClient, type QueryKey } from "@tanstack/react-query";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { ProjectEvent, ProjectSnapshot } from "../../shared/api/client";
import { projectCacheCallbacks } from "./projectCache";
import { runAssistantStreamKey, type AssistantTurnStream } from "./agentRunStream";

const projectId = "project-1";
const createdAt = "2026-09-24T00:00:00Z";
const historyPrefixes = [
  "run-history", "agent-conversations", "conversation-runs", "run-history-tasks", "run-actions", "run-media-approvals",
];
type CacheCall = readonly [operation: "invalidate" | "write", key: QueryKey | undefined];

function event(type: string, payload: ProjectEvent["payload"] = {}): ProjectEvent {
  return { projectId, type, payload, seq: 1, eventId: "event-1", schemaVersion: 1,
    aggregateId: "aggregate-1", aggregateVersion: 0, occurredAt: createdAt };
}

function snapshot(activeRunId: string | null = null): ProjectSnapshot {
  return {
    project: { id: projectId, name: "Recovered", aspectRatio: "LANDSCAPE_16_9",
      status: "ACTIVE", version: 0, createdAt, updatedAt: createdAt },
    canvas: { items: [] }, connections: [], director: null, activeTasks: [], unknownTasks: [], snapshotSeq: 42,
    activeRun: activeRunId ? {
      id: activeRunId, projectId, agentInstanceId: "agent-1", conversationId: "conversation-1",
      conversationTurn: 1, status: "RUNNING", instruction: "Create", profileVersion: 1,
      nextStepIndex: 0, version: 0, createdAt, updatedAt: createdAt,
      contextSnapshot: { agentId: "agent-1", agentVersion: 0, profileKey: "creator",
        profileVersion: 1,  },
      policySnapshot: { schemaVersion: 7, toolPolicyVersion: 6, systemPromptVersion: 14, allowedTools: ["read_canvas"], modelConfigSource: "MOCK", modelConfigVersion: 0,
        maxModelTurns: null, maxToolExecutions: null },
    } : null,
  };
}

function invalidations(...prefixes: string[]): CacheCall[] {
  return prefixes.map((prefix) => ["invalidate", [prefix, projectId]]);
}

/** Keep the real QueryClient so scoping assertions verify the affected cached views. */
function trackCache(activeRunId: string | null = null) {
  const queryClient = new QueryClient();
  queryClient.setQueryData(["snapshot", projectId], snapshot(activeRunId));
  const invalidate = vi.spyOn(queryClient, "invalidateQueries");
  const write = vi.spyOn(queryClient, "setQueryData");
  return {
    queryClient,
    callbacks: projectCacheCallbacks(queryClient, projectId),
    write, invalidate,
    clear: () => { invalidate.mockClear(); write.mockClear(); },
    calls: (): CacheCall[] => [
      ...invalidate.mock.calls.map(([filters]): CacheCall => ["invalidate", filters?.queryKey]),
      ...write.mock.calls.map(([key]): CacheCall => ["write", key]),
    ],
  };
}

function expectCacheEffects(tracked: ReturnType<typeof trackCache>, expected: CacheCall[]) {
  const keys = (calls: CacheCall[]) => new Set(calls.map((call) => JSON.stringify(call)));
  expect(keys(tracked.calls())).toEqual(keys(expected));
}

afterEach(() => vi.restoreAllMocks());

describe("project cache callbacks", () => {
  it.each([
    { type: "artifact.created", prefixes: ["canvas", "media-draft"] },
    { type: "artifact.version.created", prefixes: ["canvas", "media-draft", "project-characters"] },
    { type: "artifact.resource_default_version.changed", prefixes: ["canvas", "media-draft"] },
    { type: "canvas.items.changed", prefixes: ["canvas-media-versions", "canvas-editor", "canvas", "canvas-connections", "media-draft", "project-characters"] },
    { type: "canvas.connection.created", prefixes: ["canvas-media-versions", "canvas-editor", "canvas", "canvas-connections", "media-draft", "project-characters"] },
    { type: "canvas.connection.deleted", prefixes: ["canvas-media-versions", "canvas-editor", "canvas", "canvas-connections", "media-draft", "project-characters"] },
    { type: "agent.instance.changed", prefixes: ["canvas", "snapshot"] },
    { type: "agent.conversation.changed", prefixes: ["snapshot", ...historyPrefixes] },
    { type: "agent.run.changed", prefixes: ["snapshot", ...historyPrefixes] },
    { type: "project.changed", prefixes: ["projects"] },
    { type: "media.draft.changed", prefixes: ["media-draft", "canvas-connections", "project-characters"] },
    { type: "character.profile.changed", prefixes: ["project-characters"] },
    { type: "character.bindings.changed", prefixes: ["project-characters"] },
    { type: "usage.changed", prefixes: ["project-usage"] },
    { type: "task.changed", prefixes: [] },
    { type: "asset.ready", prefixes: [] },
    { type: "llm.turn.requested", prefixes: ["snapshot", "run-history-tasks", "run-actions", "run-tasks"] },
    { type: "llm.turn.recorded", prefixes: ["snapshot", "run-history-tasks", "run-actions", "run-tasks"] },
    { type: "agent.media.approval.changed", prefixes: ["storyboard-media-approvals", "run-media-approvals", "snapshot", "run-history-tasks", "run-actions", "run-tasks"] },
    { type: "storyboard.changed", prefixes: ["storyboard", "storyboard-revisions", "storyboard-media-approvals"] },
  ])("refreshes only the expected views for $type", ({ type, prefixes }) => {
    const tracked = trackCache();
    tracked.callbacks.onChange(event(type));
    expectCacheEffects(tracked, invalidations(...prefixes));
  });

  it("refreshes group metadata and restored copy drafts without invalidating another project", () => {
    const tracked = trackCache();
    tracked.queryClient.setQueryData(["canvas-editor", "other-project"], { groups: [] });
    tracked.clear();
    tracked.callbacks.onChange(event("canvas.edited", { operation: "DUPLICATE", state: "APPLIED" }));
    expectCacheEffects(tracked, [
      ...invalidations("canvas-media-versions", "canvas-editor", "canvas-connections", "media-draft", "artifacts", "canvas"),
      ...invalidations("project-characters"),
    ]);
    expect(tracked.queryClient.getQueryState(["canvas-editor", "other-project"])?.isInvalidated).toBe(false);
  });

  it.each([
    { payload: { canvasItemId: "card-1", artifactId: "artifact-1" },
      scopedKeys: [["media-draft", projectId, "card-1"], ["artifact-versions", projectId, "artifact-1"]] },
    { payload: { canvasItemId: "card-1" }, scopedKeys: [["media-draft", projectId, "card-1"]] },
    { payload: { canvasItemId: "card-1", artifactId: 7 }, scopedKeys: [["media-draft", projectId, "card-1"]] },
    { payload: { canvasItemId: 7, artifactId: "artifact-1" }, scopedKeys: [] },
  ])("scopes selected-version refreshes to string IDs: $payload", ({ payload, scopedKeys }) => {
    const tracked = trackCache();
    const cardDraftKey = ["media-draft", projectId, "card-1"];
    const artifactVersionsKey = ["artifact-versions", projectId, "artifact-1"];
    const unrelatedKeys = [
      ["media-draft", projectId, "card-2"], ["artifact-versions", projectId, "artifact-2"],
      ["media-draft", "project-2", "card-1"],
    ];
    for (const key of [cardDraftKey, artifactVersionsKey, ...unrelatedKeys]) {
      tracked.queryClient.setQueryData(key, { previous: true });
    }
    tracked.clear();
    tracked.callbacks.onChange(event("canvas.item.selected_version.changed", payload));
    expectCacheEffects(tracked, [
      ...invalidations("canvas-media-versions", "canvas-editor", "canvas"),
      ...scopedKeys.map((key): CacheCall => ["invalidate", key]), ...invalidations("project-characters"),
    ]);
    expect(tracked.queryClient.getQueryState(cardDraftKey)?.isInvalidated).toBe(scopedKeys.length > 0);
    expect(tracked.queryClient.getQueryState(artifactVersionsKey)?.isInvalidated).toBe(scopedKeys.length === 2);
    for (const key of unrelatedKeys) expect(tracked.queryClient.getQueryState(key)?.isInvalidated).toBe(false);
  });

  it.each([
    { activeRunId: null, artifactId: undefined },
    { activeRunId: null, artifactId: "artifact-1" },
    { activeRunId: "active-run", artifactId: undefined },
    { activeRunId: "active-run", artifactId: "artifact-1" },
  ])("refreshes task status with active Run $activeRunId and artifact $artifactId", ({ activeRunId, artifactId }) => {
    const tracked = trackCache(activeRunId);
    tracked.callbacks.onChange(event("task.status.changed", { artifactId, runId: "other-run" }));
    expectCacheEffects(tracked, [
      ...invalidations("canvas-media-versions", "snapshot", ...historyPrefixes),
      ...invalidations(...(artifactId ? ["canvas", "media-draft"] : [])),
      ...(activeRunId ? [["invalidate", ["run-tasks", projectId, activeRunId]] satisfies CacheCall] : []),
      ...invalidations("direct-media-tasks", "direct-media-queue", "storyboard-media-approvals"),
    ]);
  });

  it("refreshes task details for the cached active Run without status-only views", () => {
    const tracked = trackCache("active-run");
    tracked.callbacks.onChange(event("task.changed", { artifactId: "artifact-1", runId: "other-run" }));
    expectCacheEffects(tracked, [["invalidate", ["run-tasks", projectId, "active-run"]]]);
  });

  it("writes the four snapshot projections before refreshing missing history and auxiliary views", () => {
    const tracked = trackCache("old-run");
    const refreshedPrefixes = [
      ...historyPrefixes, "run-tasks", "direct-media-tasks", "media-draft", "canvas-media-versions", "project-usage",
      "storyboard", "storyboard-revisions", "storyboard-media-approvals", "project-characters", "canvas-editor",
    ];
    const refreshedKeys = refreshedPrefixes.map((prefix) => [prefix, projectId, "scope-1"]);
    const unchangedKeys = [
      ["direct-media-queue", projectId], ["artifact-versions", projectId, "artifact-1"],
      ["artifacts", projectId], ["run-history", "project-2", "scope-1"],
    ];
    for (const key of [...refreshedKeys, ...unchangedKeys]) tracked.queryClient.setQueryData(key, { previous: true });
    tracked.clear();
    const fresh = snapshot();
    tracked.callbacks.onSnapshot(fresh);
    expectCacheEffects(tracked, [
      ["write", ["snapshot", projectId]], ["write", ["projects", projectId]],
      ["write", ["canvas", projectId]], ["write", ["canvas-connections", projectId]],
      ...invalidations(...refreshedPrefixes),
    ]);
    // A recovery publishes every snapshot projection before refetching historical views.
    expect(Math.max(...tracked.write.mock.invocationCallOrder))
      .toBeLessThan(Math.min(...tracked.invalidate.mock.invocationCallOrder));
    for (const key of refreshedKeys) expect(tracked.queryClient.getQueryState(key)?.isInvalidated).toBe(true);
    for (const key of unchangedKeys) expect(tracked.queryClient.getQueryState(key)?.isInvalidated).toBe(false);
    expect(tracked.queryClient.getQueryData(["snapshot", projectId])).toEqual(fresh);
    expect(tracked.queryClient.getQueryData(["projects", projectId])).toEqual(fresh.project);
    expect(tracked.queryClient.getQueryData(["canvas", projectId])).toEqual(fresh.canvas);
    expect(tracked.queryClient.getQueryData(["canvas-connections", projectId])).toEqual({ items: fresh.connections });
  });

  it.each(["agent.media.approval.changed", "llm.turn.requested", "llm.turn.recorded"])(
    "scopes public Run refreshes to the event Run for %s", (type) => {
      const tracked = trackCache();
      const ownKeys = [["run-history-tasks", projectId, "run-1"], ["run-actions", projectId, "run-1"],
        ["run-tasks", projectId, "run-1"], ["run-media-approvals", projectId, "run-1"]];
      const unrelatedKeys = [["run-history-tasks", projectId, "run-2"], ["run-actions", projectId, "run-2"],
        ["run-media-approvals", projectId, "run-2"]];
      for (const key of [...ownKeys, ...unrelatedKeys]) tracked.queryClient.setQueryData(key, []);
      tracked.callbacks.onChange(event(type, { runId: "run-1" }));
      for (const key of ownKeys.slice(0, type === "agent.media.approval.changed" ? ownKeys.length : ownKeys.length - 1)) {
        expect(tracked.queryClient.getQueryState(key)?.isInvalidated).toBe(true);
      }
      for (const key of unrelatedKeys) expect(tracked.queryClient.getQueryState(key)?.isInvalidated).toBe(false);
    });

  it("updates contiguous streamed text without invalidating queries per delta, then refreshes on a chunk gap", () => {
    const tracked = trackCache();
    const invalidate = vi.spyOn(tracked.queryClient, "invalidateQueries");
    const payload = { runId: "run-1", taskId: "task-1", stepIndex: 0, streamEpoch: 1, chunkIndex: 0 };
    tracked.callbacks.onChange(event("agent.turn.stream.started", payload));
    invalidate.mockClear();
    tracked.callbacks.onChange(event("agent.turn.stream.delta", { ...payload, chunkIndex: 1, textDelta: "Hello" }));
    expect(invalidate).not.toHaveBeenCalled();
    expect(tracked.queryClient.getQueryData<AssistantTurnStream[]>(runAssistantStreamKey(projectId, "run-1"))?.[0]?.text).toBe("Hello");
    tracked.callbacks.onChange(event("agent.turn.stream.delta", { ...payload, chunkIndex: 3, textDelta: "Gap" }));
    expect(new Set(invalidate.mock.calls.map(([filters]) => JSON.stringify(filters?.queryKey)))).toEqual(new Set([
      ["snapshot", projectId], ["run-history-tasks", projectId, "run-1"],
      ["run-actions", projectId, "run-1"], ["run-tasks", projectId, "run-1"],
    ].map((key) => JSON.stringify(key))));
    expect(tracked.queryClient.getQueryData<AssistantTurnStream[]>(runAssistantStreamKey(projectId, "run-1"))?.[0]?.text).toBe("Hello");
  });

  it.each(["agent.turn.stream.completed", "agent.turn.stream.interrupted"])(
    "refreshes durable final text and public action state after %s", (type) => {
      const tracked = trackCache();
      const invalidate = vi.spyOn(tracked.queryClient, "invalidateQueries");
      tracked.callbacks.onChange(event(type, { runId: "run-1", taskId: "task-1", stepIndex: 0,
        streamEpoch: 1, chunkIndex: 0 }));
      expect(new Set(invalidate.mock.calls.map(([filters]) => JSON.stringify(filters?.queryKey)))).toEqual(new Set([
        ["run-history-tasks", projectId, "run-1"], ["run-actions", projectId, "run-1"], ["run-tasks", projectId, "run-1"],
      ].map((key) => JSON.stringify(key))));
    });
});
