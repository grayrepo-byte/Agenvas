import { QueryClient, type QueryKey } from "@tanstack/react-query";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { ProjectEvent, ProjectSnapshot } from "../../shared/api/client";
import { projectCacheCallbacks } from "./projectCache";

const projectId = "project-1";
const createdAt = "2026-09-24T00:00:00Z";
const historyPrefixes = [
  "run-history", "agent-conversations", "conversation-runs", "run-history-tasks", "run-actions",
];
type CacheCall = readonly [operation: "invalidate" | "read" | "write", key: QueryKey | undefined];
const readSnapshot: CacheCall = ["read", ["snapshot", projectId]];

function event(type: string, payload: ProjectEvent["payload"] = {}): ProjectEvent {
  return { projectId, type, payload, seq: 1, eventId: "event-1", schemaVersion: 1,
    aggregateId: "aggregate-1", aggregateVersion: 0, occurredAt: createdAt };
}

function snapshot(activeRunId: string | null = null): ProjectSnapshot {
  return {
    project: { id: projectId, name: "Recovered", aspectRatio: "LANDSCAPE_16_9",
      status: "ACTIVE", version: 0, createdAt, updatedAt: createdAt },
    canvas: { items: [] }, connections: [], agents: [], activeTasks: [], unknownTasks: [], snapshotSeq: 42,
    activeRun: activeRunId ? {
      id: activeRunId, projectId, agentInstanceId: "agent-1", conversationId: "conversation-1",
      conversationTurn: 1, status: "RUNNING", instruction: "Create", profileVersion: 1,
      nextStepIndex: 0, version: 0, createdAt, updatedAt: createdAt,
      contextSnapshot: { agentId: "agent-1", agentVersion: 0, profileKey: "creator",
        profileVersion: 1, outputGroupId: "output-1", bindings: [] },
      policySnapshot: { schemaVersion: 2, modelConfigSource: "MOCK", modelConfigVersion: 0,
        maxModelTurns: 1, maxToolExecutions: 1 },
    } : null,
  };
}

function invalidations(...prefixes: string[]): CacheCall[] {
  return prefixes.map((prefix) => ["invalidate", [prefix, projectId]]);
}

/** Records real QueryClient operations, including reads between ordered invalidations. */
function trackCache(activeRunId: string | null = null) {
  const queryClient = new QueryClient();
  queryClient.setQueryData(["snapshot", projectId], snapshot(activeRunId));
  const invalidate = vi.spyOn(queryClient, "invalidateQueries");
  const read = vi.spyOn(queryClient, "getQueryData");
  const write = vi.spyOn(queryClient, "setQueryData");
  return {
    queryClient,
    callbacks: projectCacheCallbacks(queryClient, projectId),
    write,
    clear: () => { invalidate.mockClear(); read.mockClear(); write.mockClear(); },
    calls: (): CacheCall[] => [
      ...invalidate.mock.calls.map(([filters], index) => ({
        order: invalidate.mock.invocationCallOrder[index]!, call: ["invalidate", filters?.queryKey] as CacheCall,
      })),
      ...read.mock.calls.map(([key], index) => ({
        order: read.mock.invocationCallOrder[index]!, call: ["read", key] as CacheCall,
      })),
      ...write.mock.calls.map(([key], index) => ({
        order: write.mock.invocationCallOrder[index]!, call: ["write", key] as CacheCall,
      })),
    ].sort((left, right) => left.order - right.order).map(({ call }) => call),
  };
}

afterEach(() => vi.restoreAllMocks());

describe("project cache callbacks", () => {
  it.each([
    { type: "artifact.created", beforeRead: ["canvas", "media-draft"], afterRead: [] },
    { type: "artifact.version.created", beforeRead: ["canvas", "media-draft"], afterRead: [] },
    { type: "artifact.resource_default_version.changed", beforeRead: ["canvas", "media-draft"], afterRead: [] },
    { type: "canvas.items.changed", beforeRead: ["canvas-media-versions", "canvas", "canvas-connections", "media-draft"], afterRead: [] },
    { type: "canvas.connection.created", beforeRead: ["canvas-media-versions", "canvas", "canvas-connections", "media-draft"], afterRead: [] },
    { type: "canvas.connection.deleted", beforeRead: ["canvas-media-versions", "canvas", "canvas-connections", "media-draft"], afterRead: [] },
    { type: "agent.instance.changed", beforeRead: ["canvas", "snapshot"], afterRead: [] },
    { type: "agent.conversation.changed", beforeRead: ["snapshot", ...historyPrefixes], afterRead: [] },
    { type: "agent.run.changed", beforeRead: ["snapshot", ...historyPrefixes], afterRead: [] },
    { type: "project.changed", beforeRead: ["projects"], afterRead: [] },
    { type: "media.draft.changed", beforeRead: [], afterRead: ["media-draft", "canvas-connections"] },
    { type: "usage.changed", beforeRead: [], afterRead: ["project-usage"] },
    { type: "task.changed", beforeRead: [], afterRead: [] },
    { type: "asset.ready", beforeRead: [], afterRead: [] },
    { type: "llm.turn.requested", beforeRead: [], afterRead: [] },
    { type: "llm.turn.recorded", beforeRead: [], afterRead: [] },
  ])("refreshes only the expected views for $type in order", ({ type, beforeRead, afterRead }) => {
    const tracked = trackCache();
    tracked.callbacks.onChange(event(type));
    expect(tracked.calls()).toEqual([
      ...invalidations(...beforeRead), readSnapshot, ...invalidations(...afterRead),
    ]);
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
    expect(tracked.calls()).toEqual([
      ...invalidations("canvas-media-versions", "canvas"),
      ...scopedKeys.map((key): CacheCall => ["invalidate", key]), readSnapshot,
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
    expect(tracked.calls()).toEqual([
      ...invalidations("canvas-media-versions", "snapshot", ...historyPrefixes), readSnapshot,
      ...invalidations(...(artifactId ? ["canvas", "media-draft"] : [])),
      ...(activeRunId ? [["invalidate", ["run-tasks", projectId, activeRunId]]] : []),
      ...invalidations("direct-media-tasks", "direct-media-queue"),
    ]);
  });

  it("refreshes task details for the cached active Run without status-only views", () => {
    const tracked = trackCache("active-run");
    tracked.callbacks.onChange(event("task.changed", { artifactId: "artifact-1", runId: "other-run" }));
    expect(tracked.calls()).toEqual([readSnapshot, ["invalidate", ["run-tasks", projectId, "active-run"]]]);
  });

  it("writes the four snapshot projections before refreshing missing history and auxiliary views", () => {
    const tracked = trackCache("old-run");
    const refreshedPrefixes = [
      ...historyPrefixes, "run-tasks", "direct-media-tasks", "media-draft", "canvas-media-versions", "project-usage",
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
    expect(tracked.write.mock.calls).toEqual([
      [["snapshot", projectId], fresh], [["projects", projectId], fresh.project],
      [["canvas", projectId], fresh.canvas], [["canvas-connections", projectId], { items: fresh.connections }],
    ]);
    expect(tracked.calls()).toEqual([
      ["write", ["snapshot", projectId]], ["write", ["projects", projectId]],
      ["write", ["canvas", projectId]], ["write", ["canvas-connections", projectId]],
      ...invalidations(...refreshedPrefixes),
    ]);
    for (const key of refreshedKeys) expect(tracked.queryClient.getQueryState(key)?.isInvalidated).toBe(true);
    for (const key of unchangedKeys) expect(tracked.queryClient.getQueryState(key)?.isInvalidated).toBe(false);
    expect(tracked.queryClient.getQueryData(["snapshot", projectId])).toEqual(fresh);
    expect(tracked.queryClient.getQueryData(["projects", projectId])).toEqual(fresh.project);
    expect(tracked.queryClient.getQueryData(["canvas", projectId])).toEqual(fresh.canvas);
    expect(tracked.queryClient.getQueryData(["canvas-connections", projectId])).toEqual({ items: fresh.connections });
  });
});
