import { t, useLocale } from "../../shared/i18n";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useState, type FormEvent } from "react";
import { Navigate } from "react-router";
import { ApiError, activateStorageProfile, createStorageProfile, getCurrentUser, getStorageSettings, rotateStorageCredentials,
  type CreateStorageProfileRequest, type StorageProvider, type StorageSettings } from "../../shared/api/client";
import { LoadingState } from "../../shared/ui/LoadingState";
import { Notice, Panel, StatusBadge } from "../../shared/ui/PagePrimitives";
import { Select } from "../../shared/ui/Select";
import { PageShell } from "../../shared/ui/PageShell";
import "./SettingsPages.css";

const UNAUTHORIZED_STATUS = 401;
const CONFLICT_STATUS = 409;
const CONNECTION_NAME_MAX_LENGTH = 120;
const LABELS: Record<StorageProvider, string> = { get ALIYUN_OSS() { return t("阿里云 OSS"); }, get TENCENT_COS() { return t("腾讯云 COS"); }, get S3() { return t("Amazon S3 / S3 兼容"); } };
const EXAMPLES: Record<StorageProvider, { endpoint: string; region: string; bucket: string }> = {
  ALIYUN_OSS: { endpoint: "https://oss-cn-hangzhou.aliyuncs.com", region: "cn-hangzhou", bucket: "my-bucket" },
  TENCENT_COS: { endpoint: "https://cos.ap-guangzhou.myqcloud.com", region: "ap-guangzhou", bucket: "my-bucket-1250000000" },
  S3: { endpoint: "https://s3.us-east-1.amazonaws.com", region: "us-east-1", bucket: "my-bucket" },
};
function empty(version: number): CreateStorageProfileRequest {
  return { expectedVersion: version, name: "", provider: "ALIYUN_OSS", endpoint: "", region: "", bucket: "",
    keyPrefix: "agenvas", pathStyle: false, accessKeyId: "", secretAccessKey: "" };
}

/** Location drafts pin their edit version; background reads never overwrite typing or silently move assets. */
export function StorageSettingsPage() {
  useLocale();
  const client = useQueryClient();
  const session = useQuery({ queryKey: ["auth", "me"], queryFn: getCurrentUser, retry: false });
  const query = useQuery({ queryKey: ["settings", "storage"], queryFn: getStorageSettings, enabled: session.isSuccess, retry: false });
  const [draft, setDraft] = useState<CreateStorageProfileRequest | null>(null);
  const [rotation, setRotation] = useState<{ id: string; version: number; accessKeyId: string; secret: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const snapshot = query.data;
  const current = draft ?? empty(snapshot?.version ?? 0);
  const remoteUpdate = !!snapshot && (draft?.expectedVersion ?? rotation?.version ?? snapshot.version) !== snapshot.version;

  function edit<K extends keyof CreateStorageProfileRequest>(field: K, value: CreateStorageProfileRequest[K]) {
    setDraft({ ...current, [field]: value }); setMessage("");
  }
  async function write(action: () => Promise<StorageSettings>, success: string, clearDraft = false, clearRotation = false) {
    if (busy) return;
    setBusy(true); setError(""); setMessage("");
    try {
      const result = await action(); client.setQueryData(["settings", "storage"], result);
      if (clearDraft) setDraft(null);
      if (clearRotation) setRotation(null);
      setMessage(success);
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : t("存储配置保存失败，请重试。"));
      if (cause instanceof ApiError && cause.status === CONFLICT_STATUS) await query.refetch();
    } finally {
      setBusy(false);
      // Request secrets never enter Query cache, persistent browser storage, or rendered status messages.
      setDraft((value) => value ? { ...value, accessKeyId: "", secretAccessKey: "" } : null);
      setRotation((value) => value ? { ...value, accessKeyId: "", secret: "" } : null);
    }
  }
  function submit(event: FormEvent) {
    event.preventDefault();
    void write(() => createStorageProfile({ ...current, name: current.name.trim(), endpoint: current.endpoint.trim(),
      region: current.region.trim(), bucket: current.bucket.trim(), keyPrefix: current.keyPrefix.trim() }),
    t("连接已加密保存。默认存储未切换。"), true);
  }
  if (session.error instanceof ApiError && session.error.status === UNAUTHORIZED_STATUS
      || query.error instanceof ApiError && query.error.status === UNAUTHORIZED_STATUS) return <Navigate to="/login" replace />;
  return <PageShell title={t("资源存储")} description={t("默认使用本地存储。云存储适用于磁盘容量不足等需求；切换仅影响之后归档的资源，历史文件继续从原位置读取。")}>
    {query.isPending ? <LoadingState label={t("正在读取存储配置")} /> : null}
    {query.isError ? <Notice tone="danger" title={t("无法读取存储配置")}><p>{query.error.message}</p>
      <button className="secondary-button" disabled={query.isFetching || busy} onClick={() => void query.refetch()}>{t("重新读取")}</button></Notice> : null}
    {error ? <Notice tone="danger"><p>{error}</p><p>{t("凭证输入已清空，重试前请重新输入。")}</p></Notice> : null}
    {message ? <Notice tone="success">{message}</Notice> : null}
    {remoteUpdate ? <Notice tone="warning" title={t("配置版本已更新")}><p>{t("当前输入已保留。载入最新配置会清空当前草稿与凭证。")}</p>
      <button className="secondary-button" disabled={busy} onClick={() => { setDraft(null); setRotation(null); setError(""); }}>{t("载入最新配置")}</button></Notice> : null}
    {snapshot ? <div className="settings-page-layout">
      <div className="ui-stack">
        <Panel title={t("默认存储")} description={t("保存连接后，点击设为默认才会启用。历史资源与已开始归档的任务保持原存储。")}>
          <div className="storage-destinations">
            <div className="storage-destination"><div><strong>{t("本地存储")}</strong><p className="ui-muted">{t("使用部署配置中的私有持久目录。")}</p></div>
              {snapshot.activeProfileId === null ? <StatusBadge tone="success">{t("当前默认")}</StatusBadge> :
                <button className="secondary-button" disabled={busy} onClick={() => void write(() => activateStorageProfile({ expectedVersion: snapshot.version, profileId: null }), t("后续资源使用本地存储。历史文件位置保持不变。"))}>{t("切换到本地")}</button>}
            </div>
            {snapshot.profiles.map((profile) => <div className="storage-destination" key={profile.id}><div>
              <strong>{profile.name}</strong><p className="ui-muted">{LABELS[profile.provider]} · {profile.bucket} · {profile.region}</p>
              <p className="ui-muted">{t("{0} · 凭证 {1}", { "0": profile.endpoint, "1": profile.accessKeyMask })}</p></div>
              <div className="ui-form-actions">{profile.id === snapshot.activeProfileId ? <StatusBadge tone="success">{t("当前默认")}</StatusBadge> :
                <button className="secondary-button" disabled={busy} onClick={() => void write(() => activateStorageProfile({ expectedVersion: snapshot.version, profileId: profile.id }), t("后续资源使用所选云存储。历史文件位置保持不变。"))}>{t("设为默认：{0}", { "0": profile.name })}</button>}
                <button className="secondary-button" disabled={busy} onClick={() => { setRotation({ id: profile.id, version: snapshot.version, accessKeyId: "", secret: "" }); setMessage(""); }}>{t("更新凭证：{0}", { "0": profile.name })}</button>
              </div></div>)}
          </div>
          {snapshot.profiles.length === 0 ? <p className="ui-muted">{t("尚未添加云存储连接；当前资源使用本地归档。")}</p> : null}
        </Panel>
        {rotation ? <Panel title={t("更新存储凭证")} description={t("更新同一连接的凭证，保留 Endpoint、Bucket 与对象位置，历史资源使用新凭证读取。")}>
          <form className="ui-form" onSubmit={(event) => { event.preventDefault(); void write(() => rotateStorageCredentials(rotation.id,
            { expectedVersion: rotation.version, accessKeyId: rotation.accessKeyId, secretAccessKey: rotation.secret }), t("凭证已更新并清空输入。"), false, true); }}>
            <label className="ui-field">{t("新的 AccessKey ID")}<input required autoComplete="off" disabled={busy} value={rotation.accessKeyId}
              onChange={(event) => setRotation({ ...rotation, accessKeyId: event.target.value })} /></label>
            <label className="ui-field">{t("新的 AccessKey Secret")}<input required type="password" autoComplete="new-password" disabled={busy} value={rotation.secret}
              onChange={(event) => setRotation({ ...rotation, secret: event.target.value })} /></label>
            <div className="ui-form-actions"><button className="primary-button" disabled={busy}>{t("保存新凭证")}</button>
              <button type="button" className="secondary-button" disabled={busy} onClick={() => setRotation(null)}>{t("取消更新")}</button></div>
          </form>
        </Panel> : null}
      </div>
      <Panel title={t("添加云存储连接")} description={t("位置配置保存后固定。更换区域、Bucket 或 Endpoint 时添加新连接，保留原连接用于读取历史文件。")}>
        <form className="ui-form" onSubmit={submit} aria-busy={busy}>
          <label className="ui-field">{t("连接名称")}<input required maxLength={CONNECTION_NAME_MAX_LENGTH} disabled={busy} value={current.name} onChange={(e) => edit("name", e.target.value)} /></label>
          <label className="ui-field">{t("存储类型")}<Select disabled={busy} value={current.provider} onChange={(e) => {
            const provider = e.target.value as StorageProvider; setDraft({ ...current, provider, pathStyle: false }); setMessage("");
          }}>{Object.entries(LABELS).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</Select></label>
          <label className="ui-field">Endpoint<input required type="url" autoComplete="off" disabled={busy} value={current.endpoint} placeholder={EXAMPLES[current.provider].endpoint} onChange={(e) => edit("endpoint", e.target.value)} /></label>
          <p className="ui-muted">{t("填写 HTTPS 服务端点，不含 Bucket、目录或查询参数。自托管 S3 可以使用 HTTPS 私网域名。")}</p>
          <label className="ui-field">Region<input required disabled={busy} value={current.region} placeholder={EXAMPLES[current.provider].region} onChange={(e) => edit("region", e.target.value)} /></label>
          <label className="ui-field">Bucket<input required disabled={busy} value={current.bucket} placeholder={EXAMPLES[current.provider].bucket} onChange={(e) => edit("bucket", e.target.value)} /></label>
          <label className="ui-field">{t("对象前缀")}<input disabled={busy} value={current.keyPrefix} onChange={(e) => edit("keyPrefix", e.target.value)} /></label>
          {current.provider === "S3" ? <label className="settings-consent"><input type="checkbox" disabled={busy} checked={current.pathStyle} onChange={(e) => edit("pathStyle", e.target.checked)} />{t("使用 Path Style 寻址（适用于 MinIO 等 S3 兼容服务）")}</label> : null}
          <label className="ui-field">AccessKey ID<input required autoComplete="off" disabled={busy} value={current.accessKeyId} onChange={(e) => edit("accessKeyId", e.target.value)} /></label>
          <label className="ui-field">AccessKey Secret<input required type="password" autoComplete="new-password" disabled={busy} value={current.secretAccessKey} onChange={(e) => edit("secretAccessKey", e.target.value)} /></label>
          <p className="ui-muted">{t("使用私有 Bucket。凭证仅在服务端加密保存，浏览器不会保存凭证；上传校验与媒体处理仍需本地临时空间。")}</p>
          <div className="ui-form-actions settings-save-bar"><button className="primary-button" disabled={busy}>{busy ? t("正在保存…") : t("保存连接")}</button>
            <button className="secondary-button" type="button" disabled={busy || !draft} onClick={() => { setDraft(null); setError(""); }}>{t("撤销输入")}</button></div>
        </form>
      </Panel>
    </div> : null}
  </PageShell>;
}
