import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Navigate } from "react-router";
import { t, useLocale } from "../../shared/i18n";
import { ApiError, cleanupCallLogs, getCallLogRetentionSettings, updateCallLogRetentionSettings } from "../../shared/api/client";
import { Dialog } from "../../shared/ui/Dialog";
import { Select } from "../../shared/ui/Select";
import { LoadingState } from "../../shared/ui/LoadingState";
import { Notice, Panel } from "../../shared/ui/PagePrimitives";

const SETTINGS_KEY = ["settings", "call-log-retention"] as const;
const MIN_DAYS = 1;
const MAX_DAYS = 3650;
const MONTH_DAYS = 30;
const QUARTER_DAYS = 90;
const UNAUTHORIZED = 401;
const FORBIDDEN = 403;
const CONFLICT = 409;
type Draft = { mode: "forever" | "30" | "90" | "custom"; custom: string };
function fromDays(days: number | null): Draft {
  return { mode: days === null ? "forever" : days === MONTH_DAYS ? "30" : days === QUARTER_DAYS ? "90" : "custom", custom: String(days ?? MONTH_DAYS) };
}

/** Settings stay server-owned; a separate draft survives failures, conflicts and tab changes. */
export function CallLogRetentionSection({ enabled }: { enabled: boolean }) {
  useLocale();
  const client = useQueryClient();
  const settings = useQuery({ queryKey: SETTINGS_KEY, queryFn: getCallLogRetentionSettings, enabled, retry: false });
  const [draft, setDraft] = useState<Draft | null>(null);
  const [confirmation, setConfirmation] = useState<{ days: number; version: number } | null>(null);
  const save = useMutation({ mutationFn: updateCallLogRetentionSettings,
    onSuccess: (value) => { client.setQueryData(SETTINGS_KEY, value); setDraft(null); },
  });
  const cleanup = useMutation({ mutationFn: cleanupCallLogs, retry: false,
    onSuccess: () => { void client.invalidateQueries({ queryKey: ["call-logs"] }); },
    onSettled: () => setConfirmation(null),
  });
  const error = cleanup.error ?? save.error ?? settings.error;
  if (error instanceof ApiError && error.status === UNAUTHORIZED) return <Navigate to="/login" replace />;
  const forbidden = error instanceof ApiError && error.status === FORBIDDEN;
  const conflict = error instanceof ApiError && error.status === CONFLICT;
  const value = draft ?? fromDays(settings.data?.retentionDays ?? null);
  const days = value.mode === "forever" ? null : value.mode === "custom" ? Number(value.custom) : Number(value.mode);
  const valid = days === null || ((value.mode !== "custom" || value.custom.trim() !== "") && Number.isInteger(days) && days >= MIN_DAYS && days <= MAX_DAYS);
  const disabled = !settings.data || settings.isError || settings.isFetching || forbidden || save.isPending || cleanup.isPending;
  const change = (next: Draft) => { setDraft(next); save.reset(); cleanup.reset(); };
  return <Panel title={t("调用日志保留时长")} description={t("默认永久保留。保存时长只更新规则，点击“立即清理”才会删除到期记录。") }>
    <div className="ui-stack">
      <Notice tone="warning" title={t("清理范围与风险")}>
        <p>{t("手动清理时，将一起删除到期的整条调用日志、debug 正文、模型回合、工具执行与 Provider 提交账本，无法恢复。旧对话中的执行明细也会消失。")}</p>
        <p>{t("清理范围包含已结束、未结束和结果未知的过期执行。未结束任务会先停止本地执行，再一起删除日志与账本；任务身份和已有创作结果保留。")}</p>
        <p>{t("清理未结束或结果未知的执行后，将无法继续恢复或核对原请求；外部请求可能仍在执行或计费，不承诺停止或退款。")}</p>
        <p>{t("不使用定时任务。请先保存保留时长，再手动清理；已有历史也在清理范围内，已删除记录无法恢复。")}</p>
      </Notice>
      {settings.isPending && enabled ? <LoadingState compact label={t("正在读取日志保留设置")} /> : null}
      {error ? <Notice tone="danger" title={forbidden ? t("无权修改日志保留设置") : conflict ? t("日志保留设置已变化") : cleanup.isError ? t("清理调用日志失败") : save.isError ? t("保存日志保留设置失败") : t("读取日志保留设置失败")}>
        <p>{conflict ? t("其他操作已修改设置。请重新读取后再保存，你的选择会保留。") : t("请确认当前会话和服务状态后重试，你的选择会保留。")}</p>
        {cleanup.isError ? <p>{t("未确认本次清理结果。已成功清理的批次无法恢复；重新读取设置后，可手动清理剩余记录。")}</p> : null}
        {!forbidden ? <button className="secondary-button" type="button" disabled={settings.isFetching || save.isPending || cleanup.isPending}
          onClick={() => { save.reset(); cleanup.reset(); void settings.refetch(); }}>{t("重新读取设置")}</button> : null}
      </Notice> : null}
      <label className="ui-field call-log-retention-field"><span>{t("保留时长")}</span>
        <Select value={value.mode} disabled={disabled} onChange={(event) => {
          const mode = event.target.value;
          if (mode === "forever" || mode === "30" || mode === "90" || mode === "custom") change({ ...value, mode });
        }}>
          <option value="forever">{t("永久保留")}</option>
          <option value="30">{t("30 天")}</option><option value="90">{t("90 天")}</option>
          <option value="custom">{t("自定义天数")}</option>
        </Select>
      </label>
      {value.mode === "custom" ? <label className="ui-field call-log-retention-field"><span>{t("自定义天数")}</span>
        <input type="number" min={MIN_DAYS} max={MAX_DAYS} step={MIN_DAYS} value={value.custom} disabled={disabled}
          aria-invalid={!valid} onChange={(event) => change({ ...value, custom: event.target.value })} />
        <small>{t("请输入 1 至 3650 的整数天数。")}</small>
      </label> : null}
      {save.isSuccess && draft === null ? <p role="status">{t("日志保留设置已保存。")}</p> : null}
      {cleanup.isSuccess ? <p role="status">{t("本次已清理 {0} 个执行记录（含调用日志与账本）。", { "0": cleanup.data.cleanedExecutions })}</p> : null}
      {cleanup.isSuccess && cleanup.data.batchLimitReached ? <Notice tone="warning" title={t("已达到本次清理上限")}><p>{t("可能还有到期记录，请再次手动执行清理。")}</p></Notice> : null}
      <div className="ui-form-actions"><button className="primary-button" type="button"
        disabled={disabled || conflict || !valid || days === settings.data?.retentionDays}
        onClick={() => { if (settings.data && valid) save.mutate({ retentionDays: days, expectedVersion: settings.data.version }); }}>
        {save.isPending ? t("正在保存…") : t("保存保留设置")}
      </button>
        <button className="secondary-button" type="button" disabled={disabled || conflict || !valid || days !== settings.data?.retentionDays || settings.data?.retentionDays == null}
          onClick={() => { if (settings.data?.retentionDays != null) {
            cleanup.reset(); setConfirmation({ days: settings.data.retentionDays, version: settings.data.version });
          } }}>{cleanup.isPending ? t("正在清理…") : t("立即清理")}</button>
      </div>
      <p className="ui-muted">{settings.data?.retentionDays == null ? t("永久保留时不执行清理。") : days !== settings.data.retentionDays ? t("请先保存时长，再按已保存的规则清理。") : t("仅在你确认后执行；每次最多清理 10000 个执行记录。")}</p>
    </div>
    {confirmation ? <Dialog title={t("确认清理到期日志")} description={t("将按已保存的 {0} 天保留策略执行清理。", { "0": confirmation.days })}
      busy={cleanup.isPending} onClose={() => setConfirmation(null)}
      onSubmit={(event) => { event.preventDefault(); if (!cleanup.isPending) cleanup.mutate({ expectedVersion: confirmation.version }); }}
      footer={<><button className="secondary-button" type="button" disabled={cleanup.isPending} onClick={() => setConfirmation(null)}>{t("取消")}</button>
        <button className="primary-button" type="submit" disabled={cleanup.isPending}>{cleanup.isPending ? t("正在清理…") : t("确认清理")}</button></>}>
      <Notice tone="warning" title={t("清理范围与风险")}><p>{t("将清理所有超过保留时长的执行日志与账本，包括未结束和结果未知的执行；未结束任务会停止本地执行。删除无法恢复，外部请求可能仍继续或计费，任务身份与已有创作结果保留。")}</p></Notice>
    </Dialog> : null}
  </Panel>;
}
