import type { QueryClient } from "@tanstack/react-query";
import { applyCanvasCommands, type Canvas, type CanvasCommand, type CanvasEditorState } from "../../shared/api/client";

export const canvasEditorKey = (projectId: string) => ["canvas-editor", projectId] as const;

function mergeVersions<T extends { id: string; version: number }>(membership: T[], candidates: T[] = []) {
  const byId = new Map(candidates.map(item => [item.id, item]));
  return membership.map(item => {
    const candidate = byId.get(item.id);
    return candidate && candidate.version > item.version ? candidate : item;
  });
}

/** A full edit response can predate a read that completed while the command was in flight. */
export function captureCanvasAcknowledgement(client: QueryClient, projectId: string) {
  const canvasKey = ["canvas", projectId] as const;
  const editorKey = canvasEditorKey(projectId);
  const submittedCanvas = client.getQueryData<Canvas>(canvasKey);
  const submittedEditor = client.getQueryData<CanvasEditorState>(editorKey);

  return async (saved: Canvas & Partial<Pick<CanvasEditorState, "groups">>) => {
    const canvas = client.getQueryData<Canvas>(canvasKey);
    const editor = client.getQueryData<CanvasEditorState>(editorKey);
    const canvasChanged = canvas !== submittedCanvas;
    const editorChanged = editor !== submittedEditor;

    // Preserve observed membership after an intervening update and merge only newer versions of those entries.
    if (canvas || !canvasChanged) {
      client.setQueryData<Canvas>(canvasKey, {
        items:
          canvasChanged && canvas
            ? mergeVersions(canvas.items, saved.items)
            : mergeVersions(saved.items, canvas?.items),
      });
    }
    if (saved.groups && (editor || !editorChanged)) {
      client.setQueryData<CanvasEditorState>(editorKey, {
        items:
          editorChanged && editor
            ? mergeVersions(editor.items, saved.items)
            : mergeVersions(saved.items, editor?.items),
        groups:
          editorChanged && editor
            ? mergeVersions(editor.groups, saved.groups)
            : mergeVersions(saved.groups, editor?.groups),
      });
    }
    if (canvasChanged || editorChanged) {
      // Only a fresh read can settle competing additions or removals. A read error leaves the command acknowledged.
      await Promise.all([
        client.invalidateQueries({ queryKey: canvasKey }),
        client.invalidateQueries({ queryKey: editorKey }),
      ]);
    }
  };
}

export async function saveCanvasCommands(client: QueryClient, projectId: string, commands: CanvasCommand[]) {
  const acknowledge = captureCanvasAcknowledgement(client, projectId);
  const saved = await applyCanvasCommands(projectId, commands);
  await acknowledge(saved);
  return saved;
}
