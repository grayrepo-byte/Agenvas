import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Navigate } from "react-router";
import { t, useLocale } from "../../shared/i18n";
import { ApiError, getCallLogRetentionSettings, updateCallLogRetentionSettings } from "../../shared/api/client";
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
  const save = useMutation({ mutationFn: updateCallLogRetentionSettings,
    onSuccess: (value) => { client.setQueryData(SETTINGS_KEY, value); setDraft(null); },
  });
  const error = save.error ?? settings.error;
  if (error instanceof ApiError && error.status === UNAUTHORIZED) return <Navigate to="/login" replace />;
  const forbidden = error instanceof ApiError && error.status === FORBIDDEN;
  const conflict = error instanceof ApiError && error.status === CONFLICT;
  const value = draft ?? fromDays(settings.data?.retentionDays ?? null);
  const days = value.mode === "forever" ? null : value.mode === "custom" ? Number(value.custom) : Number(value.mode);
  const valid = days === null || ((value.mode !== "custom" || value.custom.trim() !== "") && Number.isInteger(days) && days >= MIN_DAYS && days <= MAX_DAYS);
  const disabled = !settings.data || settings.isError || settings.isFetching || forbidden || save.isPending;
  const change = (next: Draft) => { setDraft(next); save.reset(); };
  return <Panel title={t("调用日志保留时长")} description={t("默认永久保留。选择有限时长并保存后，系统会自动清理到期记录。") }>
    <div className="ui-stack">
      <Notice tone="warning" title={t("清理范围与风险")}>
        <p>{t("到期后，将一起删除整条调用日志、debug 正文、模型回合、工具执行与 Provider 提交账本，无法恢复。旧对话中的执行明细也会消失。")}</p>
        <p>{t("仅清理已结束且超过保留时长的完整执行；运行中、待处理和结果未知的执行继续保留。业务任务、画布内容、生成版本和媒体文件不会删除。")}</p>
        <p>{t("保存较短时长也会清理已有历史；之后延长时长或改为永久保留，不能恢复已删除记录。系统每 5 分钟分批清理。")}</p>
      </Notice>
      {settings.isPending && enabled ? <LoadingState compact label={t("正在读取日志保留设置")} /> : null}
      {error ? <Notice tone="danger" title={forbidden ? t("无权修改日志保留设置") : conflict ? t("日志保留设置已变化") : save.isError ? t("保存日志保留设置失败") : t("读取日志保留设置失败")}>
        <p>{conflict ? t("其他操作已修改设置。请重新读取后再保存，你的选择会保留。") : t("请确认当前会话和服务状态后重试，你的选择会保留。")}</p>
        {!forbidden ? <button className="secondary-button" type="button" disabled={settings.isFetching || save.isPending}
          onClick={() => { save.reset(); void settings.refetch(); }}>{t("重新读取设置")}</button> : null}
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
      <div className="ui-form-actions"><button className="primary-button" type="button"
        disabled={disabled || conflict || !valid || days === settings.data?.retentionDays}
        onClick={() => { if (settings.data && valid) save.mutate({ retentionDays: days, expectedVersion: settings.data.version }); }}>
        {save.isPending ? t("正在保存…") : t("保存保留设置")}
      </button></div>
    </div>
  </Panel>;
}
