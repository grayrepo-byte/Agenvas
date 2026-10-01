import { t, useLocale } from "../../shared/i18n";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Link, Navigate } from "react-router";
import { ApiError, getDebugSettings, updateDebugSettings } from "../../shared/api/client";
import { LoadingState } from "../../shared/ui/LoadingState";
import { Notice, Panel, StatusBadge } from "../../shared/ui/PagePrimitives";

const SETTINGS_KEY = ["settings", "debug"] as const;
const UNAUTHORIZED_STATUS = 401;
const FORBIDDEN_STATUS = 403;
const CONFLICT_STATUS = 409;

export function DebugModeSection({ enabled }: { enabled: boolean }) {
  useLocale();
  const client = useQueryClient();
  const settings = useQuery({ queryKey: SETTINGS_KEY, queryFn: getDebugSettings, enabled, retry: false });
  const [draft, setDraft] = useState<boolean | null>(null);
  const save = useMutation({
    mutationFn: updateDebugSettings,
    onSuccess: (value) => { client.setQueryData(SETTINGS_KEY, value); setDraft(null); },
  });
  const error = save.error ?? settings.error;
  if (error instanceof ApiError && error.status === UNAUTHORIZED_STATUS) return <Navigate to="/login" replace />;
  const forbidden = error instanceof ApiError && error.status === FORBIDDEN_STATUS;
  const conflict = error instanceof ApiError && error.status === CONFLICT_STATUS;
  const checked = draft ?? settings.data?.debugMode ?? false;

  return <Panel title={t("Debug 模式")} description={t("默认关闭。仅影响之后开始的调用，设置会保存到系统中。")}
    actions={<StatusBadge tone={settings.data?.debugMode ? "warning" : "neutral"}>{settings.data ? settings.data.debugMode ? t("已开启") : t("已关闭") : t("未读取")}</StatusBadge>}>
    <div className="ui-stack">
      <Notice tone="warning" title={t("开启风险")}>
        <p>{t("开启后，调用日志会保存原始请求地址、请求内容和响应内容，可能包含完整提示词、素材、个人信息和业务数据，并显著增加数据库及备份体积。请仅在排查问题时开启，避免分享这些日志。")}</p>
        <p>{t("所有 header 均不保存；密钥、鉴权字段和模型私有推理始终脱敏。单个正文最多采集 64 MiB，超限或未读完会标注。关闭后停止新增正文记录，已经保存的历史记录仍保留；旧日志不会补录。")}</p>
      </Notice>
      {settings.isPending && enabled ? <LoadingState compact label={t("正在读取 debug 模式")} /> : null}
      {error ? <Notice tone="danger" title={forbidden ? t("无权修改 debug 模式") : conflict ? t("Debug 设置已变化") : save.isError ? t("保存 debug 模式失败") : t("读取 debug 模式失败")}>
        <p>{conflict ? t("其他操作已修改设置。请重新读取后再保存，你的选择会保留。") : t("请确认当前会话和服务状态后重试，你的选择会保留。")}</p>
        {!forbidden ? <button className="secondary-button" type="button" disabled={settings.isFetching || save.isPending}
          onClick={() => { save.reset(); void settings.refetch(); }}>{t("重新读取设置")}</button> : null}
      </Notice> : null}
      <label className="ui-checkbox"><input type="checkbox" checked={checked} disabled={!settings.data || settings.isError || forbidden || save.isPending}
        onChange={(event) => { setDraft(event.target.checked); save.reset(); }} />{t("开启 debug 模式")}</label>
      {save.isSuccess && draft === null ? <p role="status">{t("Debug 模式已{0}。", { "0": settings.data?.debugMode ? t("开启") : t("关闭") })}</p> : null}
      <div className="ui-form-actions">
        <Link className="secondary-button" to="/settings/calls">{t("查看调用日志")}</Link>
        <button className="primary-button" type="button" disabled={!settings.data || settings.isError || forbidden || conflict || save.isPending || checked === settings.data.debugMode}
          onClick={() => { if (settings.data) save.mutate({ debugMode: checked, expectedVersion: settings.data.version }); }}>{save.isPending ? t("正在保存…") : t("保存 debug 设置")}</button>
      </div>
    </div>
  </Panel>;
}
