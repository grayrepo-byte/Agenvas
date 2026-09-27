import { getProjectSnapshot, type ProjectEvent, type ProjectSnapshot } from "../../shared/api/client";

const supportedTypes = [
  "artifact.created",
  "artifact.version.created",
  "artifact.current_version.changed",
  "media.draft.changed",
  "asset.ready",
  "canvas.items.changed",
  "agent.instance.changed",
  "agent.conversation.changed",
  "agent.run.changed",
  "llm.turn.requested",
  "llm.turn.recorded",
  "usage.changed",
  "task.changed",
  "task.status.changed",
  "project.changed",
] as const;

export type EventSyncStatus = "connecting" | "live" | "reconnecting" | "recovering" | "failed";

type EventStream = {
  addListener: (type: string, listener: (event: MessageEvent<string>) => void) => void;
  onOpen: (listener: () => void) => void;
  onError: (listener: () => void) => void;
  close: () => void;
};

type EventDependencies = {
  open: (url: string) => EventStream;
  loadSnapshot: (projectId: string) => Promise<ProjectSnapshot>;
  schedule: (callback: () => void, delayMillis: number) => ReturnType<typeof setTimeout>;
  clearSchedule: (handle: ReturnType<typeof setTimeout>) => void;
};

type EventCallbacks = {
  onChange: (event: ProjectEvent) => void;
  onSnapshot: (snapshot: ProjectSnapshot) => void;
  onStatus: (status: EventSyncStatus) => void;
};

const defaultDependencies: EventDependencies = {
  open: (url) => {
    const source = new EventSource(url);
    return {
      addListener: (type, listener) => source.addEventListener(type, listener as EventListener),
      onOpen: (listener) => { source.onopen = listener; },
      onError: (listener) => { source.onerror = listener; },
      close: () => source.close(),
    };
  },
  loadSnapshot: getProjectSnapshot,
  schedule: (callback, delayMillis) => setTimeout(callback, delayMillis),
  clearSchedule: (handle) => clearTimeout(handle),
};

/** Maintains one project SSE connection and restores a consistent snapshot after any gap. */
export function subscribeProjectEvents(
  projectId: string,
  initialSequence: number,
  callbacks: EventCallbacks,
  dependencies: EventDependencies = defaultDependencies,
): () => void {
  let cursor = initialSequence;
  let disposed = false;
  let recovering = false;
  let stream: EventStream | undefined;
  let retryTimer: ReturnType<typeof setTimeout> | undefined;
  const versions = new Map<string, number>();

  function clearRetry() {
    if (retryTimer !== undefined) {
      dependencies.clearSchedule(retryTimer);
      retryTimer = undefined;
    }
  }

  function scheduleRecovery(delayMillis: number) {
    clearRetry();
    retryTimer = dependencies.schedule(() => {
      retryTimer = undefined;
      void recover();
    }, delayMillis);
  }

  async function recover() {
    if (disposed || recovering) return;
    recovering = true;
    clearRetry();
    stream?.close();
    stream = undefined;
    callbacks.onStatus("recovering");
    try {
      const snapshot = await dependencies.loadSnapshot(projectId);
      if (disposed) return;
      if (!Number.isSafeInteger(snapshot.snapshotSeq) || snapshot.snapshotSeq < 0) {
        throw new Error("Invalid project snapshot sequence");
      }
      cursor = snapshot.snapshotSeq;
      versions.clear();
      callbacks.onSnapshot(snapshot);
      open();
    } catch {
      if (!disposed) {
        callbacks.onStatus("failed");
        scheduleRecovery(5_000);
      }
    } finally {
      recovering = false;
    }
  }

  function handleEvent(message: MessageEvent<string>) {
    if (disposed || recovering) return;
    let parsed: unknown;
    try {
      parsed = JSON.parse(message.data) as unknown;
    } catch {
      void recover();
      return;
    }
    if (!isProjectEvent(parsed, projectId) || message.lastEventId !== String(parsed.seq)) {
      void recover();
      return;
    }
    if (parsed.seq <= cursor) return;
    if (parsed.seq !== cursor + 1) {
      void recover();
      return;
    }
    cursor = parsed.seq;
    clearRetry();
    callbacks.onStatus("live");
    const previousVersion = versions.get(parsed.aggregateId);
    if (previousVersion !== undefined && parsed.aggregateVersion < previousVersion) return;
    versions.set(parsed.aggregateId, parsed.aggregateVersion);
    callbacks.onChange(parsed);
  }

  function open() {
    if (disposed) return;
    callbacks.onStatus("connecting");
    const current = dependencies.open(
      `/api/v1/projects/${projectId}/events?after=${encodeURIComponent(String(cursor))}`,
    );
    stream = current;
    for (const type of supportedTypes) {
      current.addListener(type, (event) => {
        if (stream === current) handleEvent(event);
      });
    }
    current.addListener("cursor-expired", () => {
      if (stream === current) void recover();
    });
    current.onOpen(() => {
      if (stream !== current) return;
      clearRetry();
      callbacks.onStatus("live");
    });
    current.onError(() => {
      if (stream !== current) return;
      callbacks.onStatus("reconnecting");
      scheduleRecovery(5_000);
    });
  }

  open();
  return () => {
    disposed = true;
    clearRetry();
    stream?.close();
    stream = undefined;
  };
}

/** Validates the public schema before a received event can advance the local cursor. */
function isProjectEvent(value: unknown, projectId: string): value is ProjectEvent {
  if (typeof value !== "object" || value === null) return false;
  const event = value as Record<string, unknown>;
  return event.projectId === projectId &&
    typeof event.eventId === "string" &&
    Number.isSafeInteger(event.seq) && Number(event.seq) > 0 &&
    typeof event.type === "string" &&
    supportedTypes.some((type) => type === event.type) &&
    event.schemaVersion === 1 &&
    typeof event.aggregateId === "string" &&
    Number.isSafeInteger(event.aggregateVersion) && Number(event.aggregateVersion) >= 0 &&
    typeof event.payload === "object" && event.payload !== null &&
    !Array.isArray(event.payload) &&
    typeof event.occurredAt === "string";
}
