import type { QueryClient } from "@tanstack/react-query";
import type { ProjectEvent, ProjectSnapshot } from "../../shared/api/client";

const HISTORY_QUERY_PREFIXES = [
  "run-history", "agent-conversations", "conversation-runs", "run-history-tasks", "run-actions",
] as const;

/** Updates the workspace cache after validated project events or snapshot recovery. */
export function projectCacheCallbacks(queryClient: QueryClient, projectId: string) {
  function invalidate(...prefixes: string[]) {
    for (const prefix of prefixes) {
      void queryClient.invalidateQueries({ queryKey: [prefix, projectId] });
    }
  }

  return {
    onChange: (event: ProjectEvent) => {
      if (event.type.startsWith("canvas.") || event.type === "task.status.changed") {
        invalidate("canvas-media-versions");
      }
      if (event.type.startsWith("artifact.") || event.type.startsWith("canvas.") ||
          event.type === "agent.instance.changed") {
        invalidate("canvas");
        if (event.type.startsWith("canvas.connection.") || event.type === "canvas.items.changed") {
          invalidate("canvas-connections", "media-draft");
        }
        if (event.type === "agent.instance.changed") invalidate("snapshot");
        if (event.type.startsWith("artifact.")) invalidate("media-draft");
        if (event.type === "canvas.item.selected_version.changed"
            && typeof event.payload.canvasItemId === "string") {
          void queryClient.invalidateQueries({
            queryKey: ["media-draft", projectId, event.payload.canvasItemId],
          });
          if (typeof event.payload.artifactId === "string") {
            void queryClient.invalidateQueries({
              queryKey: ["artifact-versions", projectId, event.payload.artifactId],
            });
          }
        }
      }
      if (event.type === "project.changed") invalidate("projects");
      if (event.type === "task.status.changed" || event.type === "agent.run.changed" || event.type === "agent.conversation.changed") {
        invalidate("snapshot", ...HISTORY_QUERY_PREFIXES);
      }
      const activeRunId = queryClient.getQueryData<ProjectSnapshot>(["snapshot", projectId])?.activeRun?.id;
      if (event.type === "task.status.changed" && event.payload.artifactId) {
        invalidate("canvas", "media-draft");
      }
      if (event.type.startsWith("task.") && activeRunId) {
        void queryClient.invalidateQueries({ queryKey: ["run-tasks", projectId, activeRunId] });
      }
      if (event.type === "task.status.changed") {
        invalidate("direct-media-tasks", "direct-media-queue");
      }
      if (event.type === "media.draft.changed") invalidate("media-draft", "canvas-connections");
      if (event.type === "usage.changed") invalidate("project-usage");
    },
    onSnapshot: (fresh: ProjectSnapshot) => {
      queryClient.setQueryData(["snapshot", projectId], fresh);
      queryClient.setQueryData(["projects", projectId], fresh.project);
      queryClient.setQueryData(["canvas", projectId], fresh.canvas);
      queryClient.setQueryData(["canvas-connections", projectId], { items: fresh.connections });
      // The snapshot contains the current workspace, but not historical panels or lists.
      // A missed event may have changed any of them while the stream was unavailable.
      invalidate(...HISTORY_QUERY_PREFIXES, "run-tasks", "direct-media-tasks", "media-draft",
        "canvas-media-versions", "project-usage");
    },
  };
}
