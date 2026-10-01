import { t, useLocale } from "../../shared/i18n";
import { useEffect } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { importLibraryEntry, type ImportLibraryRequest } from "../../shared/api/client";
import { LibraryBrowser } from "./LibraryBrowser";
import { TransferState, useLibraryTransfer } from "./useLibraryTransfer";
export function LibraryCanvasPicker({ projectId, position }: { projectId: string; position: () => { x: number; y: number } }) {
  useLocale();
  const client = useQueryClient();
  const transfer = useLibraryTransfer<Omit<ImportLibraryRequest, "commandKey">>((request) => importLibraryEntry(projectId, request));
  useEffect(() => {
    if (transfer.data?.status !== "SUCCEEDED") return;
    void client.invalidateQueries({ queryKey: ["canvas", projectId] });
    void client.invalidateQueries({ queryKey: ["snapshot", projectId] });
  }, [transfer.data, client, projectId]);
  return <><TransferState transfer={transfer} success={t("资产已放到画布")} /><LibraryBrowser disabled={transfer.working || transfer.frozen}
    onPick={(entry) => transfer.start({ entryId: entry.id, expectedVersion: entry.version, ...position() })} />
    {transfer.data?.status === "SUCCEEDED" ? <button type="button" onClick={transfer.reset}>{t("继续添加其他资产")}</button> : null}</>;
}
