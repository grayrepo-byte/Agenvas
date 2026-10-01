import { t, useLocale } from "../../shared/i18n";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useRef } from "react";
import { ApiError, getLibraryCommand, retryLibraryCommand, type LibraryCommand } from "../../shared/api/client";

const POLL_INTERVAL_MS = 1000;
/** A lost HTTP response replays the fixed payload and key, never a fresh copy operation. */
export function useLibraryTransfer<T extends object>(submit: (input: T & { commandKey: string }) => Promise<LibraryCommand>) {
  const intent = useRef<(T & { commandKey: string }) | null>(null);
  const client = useQueryClient();
  const send = useMutation({ mutationFn: (input: T) => {
    intent.current ??= { ...input, commandKey: crypto.randomUUID() };
    return submit(intent.current);
  }, onError: (error) => { if (error instanceof ApiError && error.status < 500) intent.current = null; } });
  const id = send.data?.id;
  const command = useQuery({ queryKey: ["library-command", id], queryFn: () => getLibraryCommand(id!),
    enabled: Boolean(id), initialData: send.data, retry: false, staleTime: 0,
    refetchInterval: (query) => query.state.data?.status === "SUCCEEDED" || query.state.data?.status === "FAILED" ? false : POLL_INTERVAL_MS });
  const retry = useMutation({ mutationFn: () => retryLibraryCommand(id!), onSuccess: (saved) => client.setQueryData(["library-command", id], saved) });
  const working = send.isPending || command.data?.status === "ACCEPTED" || command.data?.status === "ARCHIVING";
  return { start: send.mutate, working, frozen: Boolean(intent.current), data: command.data,
    error: send.error ?? command.error ?? retry.error,
    retry: () => { if (command.data?.status === "FAILED") retry.mutate(); else void command.refetch(); },
    retrying: retry.isPending,
    resubmit: intent.current ? () => { if (intent.current) send.mutate(intent.current); } : undefined,
    reset: () => { intent.current = null; send.reset(); } };
}
export function TransferState({ transfer, success }: { transfer: { working: boolean; data?: LibraryCommand; error: Error | null; retry: () => void; retrying: boolean; resubmit?: () => void }; success?: string }) {
  useLocale();
  if (transfer.error) return <div role="alert">{t("{0} 输入已保留。", { "0": transfer.error.message })}{transfer.data ? <button type="button" onClick={transfer.retry}>{t("核对转存结果")}</button> : transfer.resubmit ? <button type="button" onClick={transfer.resubmit}>{t("使用原命令重试提交")}</button> : null}</div>;
  if (transfer.data?.status === "FAILED") return <div role="alert">{transfer.data.errorDetail}{["VERSION_CONFLICT", "PROVIDER_UNSUPPORTED_INPUT", "VALIDATION_ERROR", "LIBRARY_REFERENCE_INVALID", "ARTIFACT_ORIGIN_INVALID"].includes(transfer.data.errorCode ?? "")
    ? <p>{t("请关闭窗口，核对最新内容后重新选择。当前草稿已保留。")}</p> : <button type="button" disabled={transfer.retrying} onClick={transfer.retry}>{t("重试本地转存")}</button>}</div>;
  if (transfer.data?.status === "SUCCEEDED") return <p role="status">{success ?? t("已完成")}</p>;
  return transfer.working ? <p role="status">{t("正在转存，请稍候…")}</p> : null;
}
