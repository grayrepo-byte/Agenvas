import { useCallback } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { ApiError, applyCanvasCommands, HTTP_STATUS, type Canvas, type CanvasCommand, type CanvasItem } from "../../shared/api/client";
import { useCanvasStore, type LayoutDraft } from "./canvasStore";
import { persistableNodeSize } from "./imageNodeLayout";

/** The existing canvas command API accepts at most 100 commands per transaction. */
const CANVAS_LAYOUT_BATCH_SIZE = 100;

type LayoutPatch = Pick<CanvasItem, "x" | "y" | "width" | "height">;
type LayoutUpdate = { item: CanvasItem; patch: Partial<LayoutPatch> };
type LayoutInput = LayoutUpdate | LayoutUpdate[];
type NodeSize = Pick<CanvasItem, "width" | "height">;
type LayoutSubmission = { input: LayoutInput; commands: CanvasCommand[];
  submitted: { itemId: string; draft: LayoutDraft | undefined }[] };

/** Save frozen layout commands; later edits remain drafts until their own request is acknowledged. */
export function useCanvasLayoutSave(projectId: string,
  effectiveNodeSize: (item: CanvasItem, patch: Partial<LayoutPatch>) => NodeSize) {
  const queryClient = useQueryClient();
  const prepare = useCallback((input: LayoutInput): LayoutSubmission => {
    const updates = Array.isArray(input) ? input : [input];
    // Freeze the command and its acknowledgement token before React Query awaits onMutate.
    // updateDraft replaces objects, so identity also distinguishes later edits with equal values.
    const submitted = updates.map(({ item }) => ({ itemId: item.id,
      draft: useCanvasStore.getState().drafts[item.id] }));
    const commands = updates.map(({ item, patch }, index): CanvasCommand => {
      const draft = submitted[index]?.draft;
      return {
        type: "UPDATE_LAYOUT", itemId: item.id, expectedVersion: item.version,
        x: patch.x ?? draft?.x ?? item.x, y: patch.y ?? draft?.y ?? item.y,
        ...persistableNodeSize(effectiveNodeSize(item, patch)),
        zIndex: item.zIndex, groupId: item.groupId,
      };
    });
    return { input, commands, submitted };
  }, [effectiveNodeSize]);
  const mutation = useMutation<Canvas, Error, LayoutSubmission>({
    retry: false,
    mutationFn: ({ commands }) => applyCanvasCommands(projectId, commands),
    onMutate: () => useCanvasStore.getState().setSaveState("saving"),
    onError: (error) => useCanvasStore.getState().setSaveState(
      error instanceof ApiError && error.status === HTTP_STATUS.CONFLICT ? "conflict" : "failed"),
    onSuccess: (saved, { submitted }) => {
      queryClient.setQueryData<Canvas>(["canvas", projectId], (current) => {
        if (!current) return saved;
        const cached = new Map(current.items.map((item) => [item.id, item]));
        return { ...saved, items: saved.items.map((item) => {
          const newer = cached.get(item.id);
          return newer && newer.version > item.version ? newer : item;
        }) };
      });
      for (const { itemId, draft } of submitted) {
        useCanvasStore.getState().clearDraftIfUnchanged(itemId, draft);
      }
      // A newer draft can already have failed with 409 while this older response was delayed.
      // Keep its status as well as its input instead of declaring it saved by the old response.
      if (Object.keys(useCanvasStore.getState().drafts).length === 0) {
        useCanvasStore.getState().setSaveState("saved");
      }
    },
  });
  const { mutate: submit, mutateAsync: submitAsync } = mutation;
  const mutate = useCallback((input: LayoutInput) => submit(prepare(input)), [prepare, submit]);
  const mutateAsync = useCallback((input: LayoutInput) => submitAsync(prepare(input)), [prepare, submitAsync]);
  const saveInBatches = useCallback(async (updates: LayoutUpdate[]) => {
    // Freeze the entire arrangement before the first request yields, including later batches.
    // Each successful transaction acknowledges only its own drafts; a failure leaves the rest.
    const frozen = prepare(updates);
    for (let start = 0; start < updates.length; start += CANVAS_LAYOUT_BATCH_SIZE) {
      const end = start + CANVAS_LAYOUT_BATCH_SIZE;
      await submitAsync({ input: updates.slice(start, end), commands: frozen.commands.slice(start, end),
        submitted: frozen.submitted.slice(start, end) });
    }
  }, [prepare, submitAsync]);
  return { isPending: mutation.isPending, error: mutation.error,
    variables: mutation.variables?.input, mutate, mutateAsync, saveInBatches };
}
