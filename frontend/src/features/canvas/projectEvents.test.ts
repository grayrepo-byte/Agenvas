import { describe, expect, it, vi } from "vitest";
import type { ProjectEvent, ProjectSnapshot } from "../../shared/api/client";
import { subscribeProjectEvents, type EventSyncStatus } from "./projectEvents";

type Listener = (event: MessageEvent<string>) => void;

/** Deterministic EventSource stand-in that exercises cursor and reconnect decisions. */
class FakeStream {
  private readonly listeners = new Map<string, Listener>();
  private opened: () => void = () => {};
  private failed: () => void = () => {};
  closed = false;

  addListener(type: string, listener: Listener) {
    this.listeners.set(type, listener);
  }

  onOpen(listener: () => void) {
    this.opened = listener;
  }

  onError(listener: () => void) {
    this.failed = listener;
  }

  close() {
    this.closed = true;
  }

  emit(event: ProjectEvent) {
    this.listeners.get(event.type)?.(new MessageEvent(event.type, {
      data: JSON.stringify(event),
      lastEventId: String(event.seq),
    }));
  }

  expire() {
    this.listeners.get("cursor-expired")?.(new MessageEvent("cursor-expired"));
  }

  reconnect() {
    this.opened();
  }

  disconnect() {
    this.failed();
  }
}

const projectId = "00000000-0000-0000-0000-000000000001";
const aggregateId = "00000000-0000-0000-0000-000000000002";

function event(seq: number, aggregateVersion: number): ProjectEvent {
  return {
    projectId,
    seq,
    eventId: crypto.randomUUID(),
    type: "artifact.version.created",
    schemaVersion: 1,
    aggregateId,
    aggregateVersion,
    payload: { artifactId: aggregateId },
    occurredAt: "2026-09-23T00:00:00Z",
  };
}

function snapshot(seq: number): ProjectSnapshot {
  return {
    project: {
      id: projectId,
      name: "Recovery",
      aspectRatio: "LANDSCAPE_16_9",
      status: "ACTIVE",
      version: 0,
      createdAt: "2026-09-23T00:00:00Z",
      updatedAt: "2026-09-23T00:00:00Z",
      archivedAt: null,
    },
    canvas: { items: [] },
    agents: [],
    activeRun: null,
    activeTasks: [],
    unknownTasks: [],
    snapshotSeq: seq,
  };
}

describe("project event subscription", () => {
  it("delivers conversation selection events at the same aggregate version without opening another stream", () => {
    const stream = new FakeStream();
    const changes: ProjectEvent[] = [];
    const open = vi.fn(() => stream);
    const stop = subscribeProjectEvents(projectId, 0, {
      onChange: (value) => changes.push(value), onSnapshot: () => {}, onStatus: () => {},
    }, {
      open, loadSnapshot: async () => snapshot(2),
      schedule: (callback) => setTimeout(callback, 5_000), clearSchedule: clearTimeout,
    });
    stream.emit({ ...event(1, 0), type: "agent.conversation.changed",
      payload: { agentId: "agent-1", conversationId: aggregateId, currentConversationId: aggregateId } });
    stream.emit({ ...event(2, 0), type: "agent.conversation.changed",
      payload: { agentId: "agent-1", conversationId: aggregateId, currentConversationId: aggregateId } });
    expect(changes.map((value) => value.seq)).toEqual([1, 2]);
    expect(open).toHaveBeenCalledOnce();
    stop();
  });

  it("delivers each immutable usage entry even when the Run has a newer version", () => {
    const stream = new FakeStream();
    const changes: ProjectEvent[] = [];
    const stop = subscribeProjectEvents(projectId, 0, {
      onChange: (value) => changes.push(value),
      onSnapshot: () => {},
      onStatus: () => {},
    }, {
      open: () => stream,
      loadSnapshot: async () => snapshot(1),
      schedule: (callback) => setTimeout(callback, 5_000),
      clearSchedule: clearTimeout,
    });
    stream.emit({ ...event(1, 10), type: "agent.run.changed" });
    stream.emit({ ...event(2, 1), type: "usage.changed",
      aggregateId: crypto.randomUUID(), payload: { operationKey: "llm:one:0:settle" } });
    expect(changes.map((change) => change.type)).toEqual(["agent.run.changed", "usage.changed"]);
    stop();
  });

  it("delivers UNKNOWN task status events so the workspace can refresh its cost warning", () => {
    const stream = new FakeStream();
    const changes: ProjectEvent[] = [];
    const stop = subscribeProjectEvents(projectId, 0, {
      onChange: (value) => changes.push(value),
      onSnapshot: () => {},
      onStatus: () => {},
    }, {
      open: () => stream,
      loadSnapshot: async () => snapshot(1),
      schedule: (callback) => setTimeout(callback, 5_000),
      clearSchedule: clearTimeout,
    });
    stream.emit({ ...event(1, 1), type: "task.status.changed", payload: { status: "UNKNOWN" } });
    expect(changes).toHaveLength(1);
    stop();
  });

  it("delivers keyframe selection events through the same project cursor", () => {
    const stream = new FakeStream();
    const changes: ProjectEvent[] = [];
    const stop = subscribeProjectEvents(projectId, 0, {
      onChange: (value) => changes.push(value),
      onSnapshot: () => {},
      onStatus: () => {},
    }, {
      open: () => stream,
      loadSnapshot: async () => snapshot(1),
      schedule: (callback) => setTimeout(callback, 5_000),
      clearSchedule: clearTimeout,
    });
    stream.emit({ ...event(1, 0), type: "shot.keyframe.selected",
      payload: { shotArtifactId: aggregateId } });
    expect(changes).toHaveLength(1);
    stop();
  });

  it("delivers export proposal changes without leaving a silent cursor gap", () => {
    const stream = new FakeStream();
    const changes: ProjectEvent[] = [];
    const stop = subscribeProjectEvents(projectId, 0, {
      onChange: (value) => changes.push(value),
      onSnapshot: () => {},
      onStatus: () => {},
    }, {
      open: () => stream,
      loadSnapshot: async () => snapshot(2),
      schedule: (callback) => setTimeout(callback, 5_000),
      clearSchedule: clearTimeout,
    });
    stream.emit({ ...event(1, 0), type: "export.proposal.changed",
      payload: { proposalId: aggregateId, status: "PENDING" } });
    stream.emit({ ...event(2, 1), type: "agent.run.changed" });
    expect(changes.map((value) => value.seq)).toEqual([1, 2]);
    stop();
  });

  it("advances across media-ready events before the following Task update", () => {
    const stream = new FakeStream();
    const changes: ProjectEvent[] = [];
    const stop = subscribeProjectEvents(projectId, 0, {
      onChange: (value) => changes.push(value),
      onSnapshot: () => {},
      onStatus: () => {},
    }, {
      open: () => stream,
      loadSnapshot: async () => snapshot(2),
      schedule: (callback) => setTimeout(callback, 5_000),
      clearSchedule: clearTimeout,
    });
    stream.emit({ ...event(1, 0), type: "asset.ready", payload: { assetId: aggregateId } });
    stream.emit({ ...event(2, 1), type: "task.status.changed" });
    expect(changes.map((value) => value.seq)).toEqual([1, 2]);
    stop();
  });

  it("advances the project cursor across private model checkpoint events", () => {
    const stream = new FakeStream();
    const changes: ProjectEvent[] = [];
    const stop = subscribeProjectEvents(projectId, 0, {
      onChange: (value) => changes.push(value),
      onSnapshot: () => {},
      onStatus: () => {},
    }, {
      open: () => stream,
      loadSnapshot: async () => snapshot(2),
      schedule: (callback) => setTimeout(callback, 5_000),
      clearSchedule: clearTimeout,
    });
    stream.emit({ ...event(1, 1), type: "llm.turn.requested", payload: { stepIndex: 0 } });
    stream.emit({ ...event(2, 1), type: "llm.turn.recorded", payload: { stepIndex: 0 } });
    expect(changes.map((value) => value.seq)).toEqual([1, 2]);
    stop();
  });

  it("deduplicates sequences, ignores old aggregate versions, and resnapshots on a gap", async () => {
    const streams: FakeStream[] = [];
    const changes: ProjectEvent[] = [];
    const snapshots: ProjectSnapshot[] = [];
    const statuses: EventSyncStatus[] = [];
    const loadSnapshot = vi.fn(async () => snapshot(10));
    const stop = subscribeProjectEvents(projectId, 0, {
      onChange: (value) => changes.push(value),
      onSnapshot: (value) => snapshots.push(value),
      onStatus: (value) => statuses.push(value),
    }, {
      open: () => {
        const stream = new FakeStream();
        streams.push(stream);
        return stream;
      },
      loadSnapshot,
      schedule: (callback) => setTimeout(callback, 5_000),
      clearSchedule: clearTimeout,
    });

    const first = streams[0];
    expect(first).toBeDefined();
    first?.emit(event(1, 2));
    first?.emit(event(1, 2));
    first?.emit(event(2, 1));
    first?.emit(event(3, 3));
    expect(changes.map((value) => value.seq)).toEqual([1, 3]);
    first?.emit(event(5, 4));
    await vi.waitFor(() => expect(streams).toHaveLength(2));
    expect(first?.closed).toBe(true);
    expect(snapshots.map((value) => value.snapshotSeq)).toEqual([10]);
    expect(loadSnapshot).toHaveBeenCalledOnce();
    expect(statuses).toContain("recovering");
    streams[1]?.emit(event(11, 4));
    expect(changes.map((value) => value.seq)).toEqual([1, 3, 11]);
    stop();
    expect(streams[1]?.closed).toBe(true);
  });

  it("allows native reconnect and resnapshots when the server expires the cursor", async () => {
    const streams: FakeStream[] = [];
    let scheduled: (() => void) | undefined;
    const loadSnapshot = vi.fn(async () => snapshot(20));
    const stop = subscribeProjectEvents(projectId, 4, {
      onChange: () => {},
      onSnapshot: () => {},
      onStatus: () => {},
    }, {
      open: () => {
        const stream = new FakeStream();
        streams.push(stream);
        return stream;
      },
      loadSnapshot,
      schedule: (callback) => {
        scheduled = callback;
        return 1 as unknown as ReturnType<typeof setTimeout>;
      },
      clearSchedule: () => { scheduled = undefined; },
    });

    streams[0]?.disconnect();
    expect(scheduled).toBeDefined();
    streams[0]?.reconnect();
    expect(scheduled).toBeUndefined();
    expect(loadSnapshot).not.toHaveBeenCalled();
    streams[0]?.expire();
    await vi.waitFor(() => expect(streams).toHaveLength(2));
    expect(loadSnapshot).toHaveBeenCalledOnce();
    stop();
  });
});
