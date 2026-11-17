import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useRef } from "react";
import { ApiError } from "../../shared/api/client";
import {
  getCharacterLibraryCommand,
  retryCharacterLibraryCommand,
  type CharacterLibraryCommand,
} from "../../shared/api/characterLibrary";
import { t, useLocale } from "../../shared/i18n";
import { Alert, AlertDescription } from "../../shared/ui/primitives/alert";
import { Button } from "../../shared/ui/primitives/button";
import { cl } from "./characterLibraryMessages";
export const uncertainFailure = (error: unknown) =>
  !(error instanceof ApiError) || error.status >= 500 || error.status === 408;
const NON_RETRYABLE_CODES = new Set([
  "VERSION_CONFLICT",
  "CHARACTER_PREVIEW_CONFLICT",
  "CHARACTER_ARCHIVED",
  "CHARACTER_LIBRARY_ARCHIVED",
  "RESOURCE_NOT_FOUND",
  "VALIDATION_ERROR",
  "PROJECT_ARCHIVED",
  "PROJECT_DELETED",
  "IDEMPOTENCY_CONFLICT",
]);
const terminal = (command?: CharacterLibraryCommand) => command?.status === "SUCCEEDED" || command?.status === "FAILED";
/** Keep the original payload and command identity until its outcome is known. No automatic resubmission. */
export function useCharacterLibraryCommand<T extends { commandId: string }>(
  submit: (request: T) => Promise<CharacterLibraryCommand>,
) {
  const request = useRef<T | null>(null);
  const lock = useRef(false);
  const client = useQueryClient();
  const send = useMutation({
    retry: false,
    mutationFn: async (input: T) => {
      lock.current = true;
      request.current = input;
      try {
        return await submit(input);
      } catch (error) {
        if (!uncertainFailure(error)) request.current = null;
        throw error;
      } finally {
        lock.current = false;
      }
    },
  });
  const commandId = send.data?.id;
  const command = useQuery({
    queryKey: ["character-library-command", commandId],
    queryFn: () => getCharacterLibraryCommand(commandId!),
    enabled: Boolean(commandId),
    initialData: send.data,
    retry: false,
    refetchInterval: query => (terminal(query.state.data) ? false : 1000),
  });
  const retry = useMutation({
    retry: false,
    mutationFn: async (id: string) => {
      try {
        return await retryCharacterLibraryCommand(id);
      } finally {
        lock.current = false;
      }
    },
    onSuccess: async result => {
      await client.cancelQueries({ queryKey: ["character-library-command", result.id] });
      client.setQueryData<CharacterLibraryCommand>(["character-library-command", result.id], known =>
        known?.status === "SUCCEEDED" ? known : result,
      );
    },
  });
  const data = command.data ?? send.data;
  const busy = send.isPending || retry.isPending || Boolean(data && !terminal(data));
  const unknown = Boolean(send.error && request.current);
  const unresolved = unknown || Boolean(retry.error) || Boolean(command.error && !terminal(data));
  return {
    data,
    unresolved,
    error: send.error ?? retry.error ?? command.error,
    busy,
    frozen: Boolean(request.current),
    unknown,
    start: (input: T) => {
      if (!lock.current && !request.current) {
        lock.current = true;
        request.current = input;
        send.mutate(input);
      }
    },
    replay: () => {
      if (!lock.current && request.current) {
        lock.current = true;
        send.mutate(request.current);
      }
    },
    verify: () => {
      void command.refetch().then(result => {
        if (result.isSuccess) retry.reset();
      });
    },
    retry: () => {
      if (data && !lock.current && !retry.isPending) {
        lock.current = true;
        retry.mutate(data.id);
      }
    },
    reset: () => {
      if (!busy && !unresolved) {
        request.current = null;
        send.reset();
        retry.reset();
      }
    },
  };
}
export function CharacterCommandState({
  command,
  success,
}: {
  command: Pick<
    ReturnType<typeof useCharacterLibraryCommand>,
    "data" | "error" | "busy" | "unknown" | "verify" | "replay" | "retry"
  >;
  success?: string;
}) {
  useLocale();
  const error = command.error;
  return (
    <>
      {error ? (
        <Alert variant="destructive">
          <AlertDescription>
            {t("common.inputPreserved", { "0": error.message })}
            {command.unknown ? (
              <>
                <p>{cl("unknown")}</p>
                <Button type="button" variant="outline" disabled={command.busy} onClick={command.replay}>
                  {cl("verify")}
                </Button>
              </>
            ) : command.data ? (
              <Button type="button" variant="outline" onClick={command.verify}>
                {cl("verifyStatus")}
              </Button>
            ) : null}
          </AlertDescription>
        </Alert>
      ) : null}
      {command.data?.status === "FAILED" ? (
        <Alert variant="destructive">
          <AlertDescription>
            {command.data.errorDetail ?? cl("transferFailed")}
            {NON_RETRYABLE_CODES.has(command.data.errorCode ?? "") ? (
              <p>{cl("conflict")}</p>
            ) : !error ? (
              <Button type="button" variant="outline" disabled={command.busy} onClick={command.retry}>
                {cl("retry")}
              </Button>
            ) : null}
          </AlertDescription>
        </Alert>
      ) : null}
      {command.data?.status === "SUCCEEDED" ? (
        <p role="status">{success ?? t("common.succeeded")}</p>
      ) : command.busy ? (
        <p role="status">{cl("transferring")}</p>
      ) : null}
    </>
  );
}
