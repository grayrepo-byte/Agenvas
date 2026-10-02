import { useQuery, useQueryClient, type QueryClient } from "@tanstack/react-query";
import { useEffect } from "react";
import type {
  AgentTurnStreamEventPayload, AssistantTurnStreamProjection, ProjectEvent, ProjectSnapshot, Task,
} from "../../shared/api/client";

export const ASSISTANT_STREAM_EVENT_TYPES = [
  "agent.turn.stream.started", "agent.turn.stream.delta",
  "agent.turn.stream.completed", "agent.turn.stream.interrupted",
] as const;
type AssistantStreamEventType = typeof ASSISTANT_STREAM_EVENT_TYPES[number];
export type AssistantStreamStatus = AssistantTurnStreamProjection["status"];

/** Public assistant text only; tool arguments and model reasoning never enter this cache. */
export type AssistantTurnStream = AssistantTurnStreamProjection & {
  taskId: string;
  stepIndex: number;
};
type StreamPayload = AgentTurnStreamEventPayload;
const EMPTY_STREAMS: readonly AssistantTurnStream[] = [];
const EMPTY_TASKS: readonly Task[] = [];
const FIRST_CHUNK_INDEX = 0;
const INITIAL_STREAM_EPOCH = 0;

export function runAssistantStreamKey(projectId: string, runId: string) {
  return ["run-assistant-stream", projectId, runId] as const;
}

export function isAssistantStreamEvent(type: string): type is AssistantStreamEventType {
  return ASSISTANT_STREAM_EVENT_TYPES.some((candidate) => candidate === type);
}

function integer(value: unknown): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0;
}

function streamPayload(payload: ProjectEvent["payload"]): StreamPayload | null {
  if (typeof payload.runId !== "string" || typeof payload.taskId !== "string" ||
      !integer(payload.stepIndex) || !integer(payload.streamEpoch) || !integer(payload.chunkIndex) ||
      payload.textDelta !== undefined && typeof payload.textDelta !== "string") return null;
  return { runId: payload.runId, taskId: payload.taskId, stepIndex: payload.stepIndex,
    streamEpoch: payload.streamEpoch, chunkIndex: payload.chunkIndex, textDelta: payload.textDelta };
}

function sameStream(left: AssistantTurnStream, right: AssistantTurnStream) {
  return left.taskId === right.taskId && left.stepIndex === right.stepIndex &&
    left.streamEpoch === right.streamEpoch && left.chunkIndex === right.chunkIndex &&
    left.text === right.text && left.status === right.status;
}

function emptyStream(payload: StreamPayload): AssistantTurnStream {
  return { taskId: payload.taskId, stepIndex: payload.stepIndex, streamEpoch: payload.streamEpoch,
    chunkIndex: FIRST_CHUNK_INDEX, text: "", status: "STREAMING" };
}

function replaceStream(streams: readonly AssistantTurnStream[], next: AssistantTurnStream) {
  const previous = streams.find((stream) => stream.taskId === next.taskId);
  if (previous && sameStream(previous, next)) return streams;
  return [...streams.filter((stream) => stream.taskId !== next.taskId), next]
    .sort((left, right) => left.stepIndex - right.stepIndex || left.taskId.localeCompare(right.taskId));
}

/** Same-index terminal notifications are legal; repeated deltas never append text twice. */
export function reduceAssistantStreamEvent(streams: readonly AssistantTurnStream[], event: ProjectEvent): {
  streams: readonly AssistantTurnStream[]; needsRecovery: boolean;
} {
  if (!isAssistantStreamEvent(event.type)) return { streams, needsRecovery: false };
  const payload = streamPayload(event.payload);
  if (!payload) return { streams, needsRecovery: true };
  const previous = streams.find((stream) => stream.taskId === payload.taskId);
  if (previous && payload.streamEpoch < previous.streamEpoch) return { streams, needsRecovery: false };
  const sameEpoch = previous?.streamEpoch === payload.streamEpoch;
  if (sameEpoch && previous.stepIndex !== payload.stepIndex) return { streams, needsRecovery: true };
  if (event.type === "agent.turn.stream.started") {
    if (payload.chunkIndex !== FIRST_CHUNK_INDEX) return { streams, needsRecovery: true };
    if (sameEpoch) return { streams, needsRecovery: false };
    return { streams: replaceStream(streams, emptyStream(payload)), needsRecovery: false };
  }
  // A stream can start before the initial snapshot. Its first delta is sufficient to
  // establish an empty baseline; a later delta needs the persisted cumulative text.
  const current = sameEpoch ? previous : emptyStream(payload);
  const baseline = previous && !sameEpoch ? replaceStream(streams, current) : streams;
  if (event.type === "agent.turn.stream.delta") {
    if (payload.textDelta === undefined) return { streams: baseline, needsRecovery: true };
    if (payload.chunkIndex <= current.chunkIndex || current.status !== "STREAMING") {
      return { streams, needsRecovery: false };
    }
    if (payload.chunkIndex !== current.chunkIndex + 1) return { streams: baseline, needsRecovery: true };
    return { streams: replaceStream(streams, { ...current, chunkIndex: payload.chunkIndex,
      text: current.text + payload.textDelta }), needsRecovery: false };
  }
  if (payload.chunkIndex < current.chunkIndex) return { streams, needsRecovery: false };
  if (payload.chunkIndex !== current.chunkIndex) return { streams: baseline, needsRecovery: true };
  if (current.status !== "STREAMING") return { streams, needsRecovery: false };
  return { streams: replaceStream(streams, { ...current,
    status: event.type === "agent.turn.stream.completed" ? "COMPLETED" : "INTERRUPTED" }), needsRecovery: false };
}

function taskStream(task: Task, previous?: AssistantTurnStream): AssistantTurnStream | null {
  if (task.kind !== "AGENT_TURN") return null;
  const raw = task.output?.assistantStream;
  const stepIndex = integer(task.output?.stepIndex) ? task.output.stepIndex : task.input.stepIndex;
  if (!integer(stepIndex)) return null;
  if (typeof raw === "object" && raw !== null && !Array.isArray(raw)) {
    const stream = raw as Record<string, unknown>;
    if (integer(stream.streamEpoch) && integer(stream.chunkIndex) && typeof stream.text === "string" &&
        (stream.status === "STREAMING" || stream.status === "COMPLETED" || stream.status === "INTERRUPTED")) {
      return { taskId: task.id, stepIndex, streamEpoch: stream.streamEpoch, chunkIndex: stream.chunkIndex,
        text: task.status === "SUCCEEDED" && typeof task.output?.assistantText === "string"
          ? task.output.assistantText : stream.text,
        status: task.status === "SUCCEEDED" ? "COMPLETED" : stream.status };
    }
  }
  if (task.status === "SUCCEEDED" && typeof task.output?.assistantText === "string") {
    return { taskId: task.id, stepIndex, streamEpoch: previous?.streamEpoch ?? INITIAL_STREAM_EPOCH,
      chunkIndex: previous?.chunkIndex ?? FIRST_CHUNK_INDEX, text: task.output.assistantText, status: "COMPLETED" };
  }
  return null;
}

/** A fresh Task snapshot fills missing chunks and replaces partial text with the committed reply. */
export function mergeAssistantStreamTasks(streams: readonly AssistantTurnStream[], tasks: readonly Task[]) {
  let next = streams;
  for (const task of tasks) {
    const previous = next.find((stream) => stream.taskId === task.id);
    const snapshot = taskStream(task, previous);
    if (!snapshot || previous && (snapshot.streamEpoch < previous.streamEpoch ||
        snapshot.streamEpoch === previous.streamEpoch && (snapshot.chunkIndex < previous.chunkIndex ||
          previous.status !== "STREAMING" && snapshot.status === "STREAMING"))) continue;
    next = replaceStream(next, snapshot);
  }
  return next;
}

/** Apply one project event without fetching per token; true requests a durable snapshot refresh. */
export function applyAssistantStreamEvent(queryClient: QueryClient, projectId: string, event: ProjectEvent) {
  const payload = streamPayload(event.payload);
  if (!payload) return true;
  const snapshot = queryClient.getQueryData<ProjectSnapshot>(["snapshot", projectId]);
  const tasks = queryClient.getQueryData<readonly Task[]>(["run-history-tasks", projectId, payload.runId]) ?? EMPTY_TASKS;
  const key = runAssistantStreamKey(projectId, payload.runId);
  const baseline = mergeAssistantStreamTasks(queryClient.getQueryData<readonly AssistantTurnStream[]>(key) ?? EMPTY_STREAMS,
    [...(snapshot?.activeTasks ?? EMPTY_TASKS).filter((task) => task.runId === payload.runId), ...tasks]);
  const reduced = reduceAssistantStreamEvent(baseline, event);
  if (reduced.streams !== queryClient.getQueryData(key)) queryClient.setQueryData(key, reduced.streams);
  return reduced.needsRecovery;
}

/** Snapshot recovery discards stale overlays, then restores only persisted public text. */
export function restoreAssistantStreams(queryClient: QueryClient, projectId: string, tasks: readonly Task[]) {
  queryClient.setQueriesData<readonly AssistantTurnStream[]>({ queryKey: ["run-assistant-stream", projectId] }, EMPTY_STREAMS);
  for (const runId of new Set(tasks.map((task) => task.runId).filter((id): id is string => id !== null))) {
    const streams = mergeAssistantStreamTasks(EMPTY_STREAMS, tasks.filter((task) => task.runId === runId));
    if (streams.length) queryClient.setQueryData(runAssistantStreamKey(projectId, runId), streams);
  }
}

/** Subscribe to the shared project stream; caller supplies its existing durable Task query. */
export function useRunAssistantStream(projectId: string, runId: string, tasks: readonly Task[] = EMPTY_TASKS): readonly AssistantTurnStream[] {
  const queryClient = useQueryClient();
  const key = runAssistantStreamKey(projectId, runId);
  const live = useQuery({ queryKey: key, queryFn: () => EMPTY_STREAMS, initialData: EMPTY_STREAMS, enabled: false });
  useEffect(() => {
    const baseline = queryClient.getQueryData<readonly AssistantTurnStream[]>(key) ?? EMPTY_STREAMS;
    const next = mergeAssistantStreamTasks(baseline, tasks.filter((task) => task.runId === runId));
    if (next !== baseline) queryClient.setQueryData(key, next);
  }, [projectId, runId, tasks, queryClient]);
  return mergeAssistantStreamTasks(live.data, tasks.filter((task) => task.runId === runId));
}
