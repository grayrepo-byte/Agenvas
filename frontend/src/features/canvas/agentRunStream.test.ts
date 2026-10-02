import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, renderHook } from "@testing-library/react";
import { createElement, type ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";
import type { ProjectEvent, Task } from "../../shared/api/client";
import {
  applyAssistantStreamEvent, mergeAssistantStreamTasks, reduceAssistantStreamEvent,
  restoreAssistantStreams, runAssistantStreamKey, useRunAssistantStream,
  type AssistantTurnStream,
} from "./agentRunStream";

const PROJECT_ID = "project-1";
const RUN_ID = "run-1";
const TASK_ID = "task-1";
const NOW = "2026-10-02T00:00:00Z";

function event(type: string, payload: Partial<ProjectEvent["payload"]> = {}): ProjectEvent {
  return { projectId: PROJECT_ID, seq: 1, eventId: "event-1", type, schemaVersion: 1,
    aggregateId: RUN_ID, aggregateVersion: 1, occurredAt: NOW,
    payload: { runId: RUN_ID, taskId: TASK_ID, stepIndex: 0, streamEpoch: 1, chunkIndex: 0, ...payload } };
}

function stream(overrides: Partial<AssistantTurnStream> = {}): AssistantTurnStream {
  return { taskId: TASK_ID, stepIndex: 0, streamEpoch: 1, chunkIndex: 2,
    text: "Public text", status: "STREAMING", ...overrides };
}

function task(overrides: Partial<Task> = {}): Task {
  return { id: TASK_ID, projectId: PROJECT_ID, runId: RUN_ID, stepKey: "agent-turn-0", kind: "AGENT_TURN",
    status: "RUNNING", cancelRequested: false, input: { stepIndex: 0 }, output: {},
    attemptNo: 1, nextActionAt: NOW, version: 1, createdAt: NOW, updatedAt: NOW, ...overrides };
}

describe("public assistant stream", () => {
  it("appends contiguous real deltas and ignores duplicates and old chunks", () => {
    let streams = reduceAssistantStreamEvent([], event("agent.turn.stream.started")).streams;
    streams = reduceAssistantStreamEvent(streams, event("agent.turn.stream.delta", { chunkIndex: 1, textDelta: "Hello" })).streams;
    streams = reduceAssistantStreamEvent(streams, event("agent.turn.stream.delta", { chunkIndex: 2, textDelta: " world" })).streams;
    expect(streams[0]?.text).toBe("Hello world");
    expect(reduceAssistantStreamEvent(streams, event("agent.turn.stream.delta", { chunkIndex: 2, textDelta: " duplicate" })).streams).toBe(streams);
    expect(reduceAssistantStreamEvent(streams, event("agent.turn.stream.delta", { chunkIndex: 1, textDelta: " old" })).streams).toBe(streams);
  });

  it("resets a higher lease epoch and rejects the older worker's later text", () => {
    const previous = [stream()];
    const reset = reduceAssistantStreamEvent(previous, event("agent.turn.stream.started", { streamEpoch: 2 })).streams;
    expect(reset[0]).toMatchObject({ streamEpoch: 2, chunkIndex: 0, text: "", status: "STREAMING" });
    expect(reduceAssistantStreamEvent(reset, event("agent.turn.stream.delta", { chunkIndex: 3, textDelta: "OLD_WORKER" })).streams).toBe(reset);
    expect(reduceAssistantStreamEvent(reset, event("agent.turn.stream.delta", { streamEpoch: 2, chunkIndex: 1, textDelta: "New" })).streams[0]?.text).toBe("New");
  });

  it("requests recovery without appending text across a missing chunk", () => {
    const previous = [stream()];
    const reduced = reduceAssistantStreamEvent(previous, event("agent.turn.stream.delta", { chunkIndex: 4, textDelta: "Missing prefix" }));
    expect(reduced.needsRecovery).toBe(true);
    expect(reduced.streams).toBe(previous);
    expect(reduceAssistantStreamEvent([], event("agent.turn.stream.delta", { chunkIndex: 3, textDelta: "Missing prefix" })).needsRecovery).toBe(true);
  });

  it("discards the old epoch text while recovering a newer epoch with missing chunks", () => {
    const reduced = reduceAssistantStreamEvent([stream()], event("agent.turn.stream.delta", {
      streamEpoch: 2, chunkIndex: 4, textDelta: "Missing prefix",
    }));
    expect(reduced.needsRecovery).toBe(true);
    expect(reduced.streams[0]).toMatchObject({ streamEpoch: 2, chunkIndex: 0, text: "" });
  });

  it("accepts a first delta after the starting snapshot and same-index terminal events", () => {
    const first = reduceAssistantStreamEvent([], event("agent.turn.stream.delta", { chunkIndex: 1, textDelta: "One" })).streams;
    const completed = reduceAssistantStreamEvent(first, event("agent.turn.stream.completed", { chunkIndex: 1 })).streams;
    expect(completed[0]).toMatchObject({ text: "One", status: "COMPLETED", chunkIndex: 1 });
    expect(reduceAssistantStreamEvent(completed, event("agent.turn.stream.delta", { chunkIndex: 2, textDelta: "Late" })).streams).toBe(completed);
    const interrupted = reduceAssistantStreamEvent(first, event("agent.turn.stream.interrupted", { chunkIndex: 1 })).streams;
    expect(interrupted[0]?.status).toBe("INTERRUPTED");
  });

  it("rejects malformed public payloads and mismatched steps", () => {
    expect(reduceAssistantStreamEvent([], event("agent.turn.stream.delta", { chunkIndex: 1, textDelta: 8 })).needsRecovery).toBe(true);
    expect(reduceAssistantStreamEvent([], event("agent.turn.stream.started", { streamEpoch: -1 })).needsRecovery).toBe(true);
    expect(reduceAssistantStreamEvent([stream()], event("agent.turn.stream.delta", { stepIndex: 1, chunkIndex: 3, textDelta: "Wrong" })).needsRecovery).toBe(true);
  });

  it("restores cumulative task output without exposing private metadata", () => {
    const restored = mergeAssistantStreamTasks([], [task({ input: { stepIndex: 0, privatePrompt: "PRIVATE_PROMPT" },
      output: { assistantStream: { streamEpoch: 2, chunkIndex: 8, text: "Persisted reply", status: "STREAMING" },
        reasoning: "PRIVATE_REASONING", rawResponse: "PRIVATE_RESPONSE" } })]);
    expect(restored).toEqual([stream({ streamEpoch: 2, chunkIndex: 8, text: "Persisted reply" })]);
    expect(JSON.stringify(restored)).not.toContain("PRIVATE");
  });

  it("keeps newer live epochs/chunks and corrects text from a committed reply", () => {
    const live = [stream({ streamEpoch: 2, chunkIndex: 3, text: "Partial" })];
    const stale = task({ output: { assistantStream: { streamEpoch: 1, chunkIndex: 8, text: "Stale", status: "COMPLETED" } } });
    expect(mergeAssistantStreamTasks(live, [stale])).toBe(live);
    const committed = task({ status: "SUCCEEDED", output: { stepIndex: 0, assistantText: "Corrected final reply",
      assistantStream: { streamEpoch: 2, chunkIndex: 3, text: "Partial", status: "COMPLETED" } } });
    const final = mergeAssistantStreamTasks(live, [committed]);
    expect(final[0]).toMatchObject({ text: "Corrected final reply", status: "COMPLETED" });
    expect(mergeAssistantStreamTasks(final, [task({ output: { assistantStream: {
      streamEpoch: 2, chunkIndex: 3, text: "Partial", status: "STREAMING" } } })])).toBe(final);
  });

  it("uses a persisted baseline before processing a delta received after refresh", () => {
    const client = new QueryClient();
    client.setQueryData(["run-history-tasks", PROJECT_ID, RUN_ID], [task({ output: {
      assistantStream: { streamEpoch: 1, chunkIndex: 2, text: "Saved", status: "STREAMING" } } })]);
    expect(applyAssistantStreamEvent(client, PROJECT_ID, event("agent.turn.stream.delta", { chunkIndex: 3, textDelta: " live" }))).toBe(false);
    expect(client.getQueryData<AssistantTurnStream[]>(runAssistantStreamKey(PROJECT_ID, RUN_ID))?.[0]?.text).toBe("Saved live");
    client.clear();
  });

  it("clears stale overlays on snapshot recovery and restores persistent active turns", () => {
    const client = new QueryClient();
    client.setQueryData(runAssistantStreamKey(PROJECT_ID, "old-run"), [stream({ text: "Old overlay" })]);
    client.setQueryData(runAssistantStreamKey("other-project", RUN_ID), [stream({ text: "Other project" })]);
    restoreAssistantStreams(client, PROJECT_ID, [task({ output: {
      assistantStream: { streamEpoch: 3, chunkIndex: 4, text: "Recovered", status: "STREAMING" } } })]);
    expect(client.getQueryData(runAssistantStreamKey(PROJECT_ID, "old-run"))).toEqual([]);
    expect(client.getQueryData<AssistantTurnStream[]>(runAssistantStreamKey(PROJECT_ID, RUN_ID))?.[0]?.text).toBe("Recovered");
    expect(client.getQueryData<AssistantTurnStream[]>(runAssistantStreamKey("other-project", RUN_ID))?.[0]?.text).toBe("Other project");
    client.clear();
  });

  it("subscribes to shared QueryClient updates and renders durable final corrections without fetching", () => {
    const client = new QueryClient();
    const fetch = vi.spyOn(globalThis, "fetch");
    const wrapper = ({ children }: { children: ReactNode }) => createElement(QueryClientProvider, { client }, children);
    const mounted = renderHook(({ tasks }: { tasks: Task[] }) => useRunAssistantStream(PROJECT_ID, RUN_ID, tasks), {
      wrapper, initialProps: { tasks: [] as Task[] },
    });
    act(() => client.setQueryData(runAssistantStreamKey(PROJECT_ID, RUN_ID), [stream()]));
    mounted.rerender({ tasks: [task({ status: "SUCCEEDED", output: { stepIndex: 0, assistantText: "Final text" } })] });
    expect(mounted.result.current[0]?.text).toBe("Final text");
    expect(fetch).not.toHaveBeenCalled();
    mounted.unmount();
    fetch.mockRestore();
    client.clear();
  });
});
