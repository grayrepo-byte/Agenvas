import { Field, FieldLabel } from "../../shared/ui/primitives/field";
import { useQuery,useQueryClient } from "@tanstack/react-query";
import { useState,type FormEvent } from "react";
import { Navigate } from "react-router";
import {
HTTP_STATUS,ApiError,activateStorageProfile,createStorageProfile,getCurrentUser,getStorageSettings,rotateStorageCredentials,
type CreateStorageProfileRequest,type StorageProvider,type StorageSettings
} from "../../shared/api/client";
import { t,useLocale } from "../../shared/i18n";
import { LoadingState } from "../../shared/ui/LoadingState";
import { Notice,Panel,StatusBadge } from "../../shared/ui/PagePrimitives";
import { PageShell } from "../../shared/ui/PageShell";
import { Button } from "../../shared/ui/primitives/button";
import { Checkbox } from "../../shared/ui/primitives/checkbox";
import { Input } from "../../shared/ui/primitives/input";
import { Select } from "../../shared/ui/Select";
import "./SettingsPages.css";

const CONNECTION_NAME_MAX_LENGTH = 120;
const LABELS: Record<StorageProvider, string> = { get ALIYUN_OSS() { return t("settings.storage.oss"); }, get TENCENT_COS() { return t("settings.storage.cos"); }, get S3() { return t("settings.storage.s3"); } };
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
      setError(cause instanceof ApiError ? cause.message : t("settings.storage.saveFailed"));
      if (cause instanceof ApiError && cause.status === HTTP_STATUS.CONFLICT) await query.refetch();
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
    t("settings.storage.connectionSaved"), true);
  }
  if (session.error instanceof ApiError && session.error.status === HTTP_STATUS.UNAUTHORIZED
      || query.error instanceof ApiError && query.error.status === HTTP_STATUS.UNAUTHORIZED) return <Navigate to="/login" replace />;
  return <PageShell title={t("settings.storage.title")} description={t("settings.storage.description")}>
    {query.isPending ? <LoadingState label={t("settings.storage.loading")} /> : null}
    {query.isError ? <Notice tone="danger" title={t("api.errors.storageSettingsUnavailable")}><p>{query.error.message}</p>
      <Button variant="outline"  disabled={query.isFetching || busy} onClick={() => void query.refetch()}>{t("common.refresh")}</Button></Notice> : null}
    {error ? <Notice tone="danger"><p>{error}</p><p>{t("settings.storage.credentialsCleared")}</p></Notice> : null}
    {message ? <Notice tone="success">{message}</Notice> : null}
    {remoteUpdate ? <Notice tone="warning" title={t("settings.storage.versionChanged")}><p>{t("settings.storage.refreshDraftHint")}</p>
      <Button variant="outline"  disabled={busy} onClick={() => { setDraft(null); setRotation(null); setError(""); }}>{t("settings.shared.refreshConfig")}</Button></Notice> : null}
    {snapshot ? <div className="settings-page-layout">
      <div className="ui-stack">
        <Panel title={t("settings.storage.defaultStorage")} description={t("settings.storage.saveBeforeDefaultHint")}>
          <div className="storage-destinations">
            <div className="storage-destination"><div><strong>{t("settings.storage.local")}</strong><p className="ui-muted">{t("settings.storage.localDirectoryHint")}</p></div>
              {snapshot.activeProfileId === null ? <StatusBadge tone="success">{t("settings.storage.currentDefault")}</StatusBadge> :
                <Button variant="outline"  disabled={busy} onClick={() => void write(() => activateStorageProfile({ expectedVersion: snapshot.version, profileId: null }), t("settings.storage.localDefaultHint"))}>{t("settings.storage.switchLocal")}</Button>}
            </div>
            {snapshot.profiles.map((profile) => <div className="storage-destination" key={profile.id}><div>
              <strong>{profile.name}</strong><p className="ui-muted">{LABELS[profile.provider]} · {profile.bucket} · {profile.region}</p>
              <p className="ui-muted">{t("settings.storage.connectionSummary", { "0": profile.endpoint, "1": profile.accessKeyMask })}</p></div>
              <div className="ui-form-actions">{profile.id === snapshot.activeProfileId ? <StatusBadge tone="success">{t("settings.storage.currentDefault")}</StatusBadge> :
                <Button variant="outline"  disabled={busy} onClick={() => void write(() => activateStorageProfile({ expectedVersion: snapshot.version, profileId: profile.id }), t("settings.storage.cloudDefaultHint"))}>{t("settings.storage.setDefaultNamed", { "0": profile.name })}</Button>}
                <Button variant="outline"  disabled={busy} onClick={() => { setRotation({ id: profile.id, version: snapshot.version, accessKeyId: "", secret: "" }); setMessage(""); }}>{t("settings.storage.updateCredentialsNamed", { "0": profile.name })}</Button>
              </div></div>)}
          </div>
          {snapshot.profiles.length === 0 ? <p className="ui-muted">{t("settings.storage.connectionsEmpty")}</p> : null}
        </Panel>
        {rotation ? <Panel title={t("settings.storage.updateCredentials")} description={t("settings.storage.credentialUpdateHint")}>
          <form className="ui-form" onSubmit={(event) => { event.preventDefault(); void write(() => rotateStorageCredentials(rotation.id,
            { expectedVersion: rotation.version, accessKeyId: rotation.accessKeyId, secretAccessKey: rotation.secret }), t("settings.storage.credentialsUpdated"), false, true); }}>
            <Field><FieldLabel className="ui-field block">{t("settings.storage.newAccessKeyId")}<Input required autoComplete="off" disabled={busy} value={rotation.accessKeyId}
              onChange={(event) => setRotation({ ...rotation, accessKeyId: event.target.value })} /></FieldLabel></Field>
            <Field><FieldLabel className="ui-field block">{t("settings.storage.newAccessKeySecret")}<Input required type="password" autoComplete="new-password" disabled={busy} value={rotation.secret}
              onChange={(event) => setRotation({ ...rotation, secret: event.target.value })} /></FieldLabel></Field>
            <div className="ui-form-actions"><Button variant="default"  disabled={busy}>{t("settings.storage.saveCredentials")}</Button>
              <Button variant="outline" type="button"  disabled={busy} onClick={() => setRotation(null)}>{t("settings.storage.cancelUpdate")}</Button></div>
          </form>
        </Panel> : null}
      </div>
      <Panel title={t("settings.storage.addConnection")} description={t("settings.storage.immutableLocationHint")}>
        <form className="ui-form" onSubmit={submit} aria-busy={busy}>
          <Field><FieldLabel className="ui-field block">{t("settings.shared.connectionName")}<Input required maxLength={CONNECTION_NAME_MAX_LENGTH} disabled={busy} value={current.name} onChange={(e) => edit("name", e.target.value)} /></FieldLabel></Field>
          <Field><FieldLabel className="ui-field block">{t("settings.storage.storageType")}<Select disabled={busy} value={current.provider} onChange={(e) => {
            const provider = e.target.value as StorageProvider; setDraft({ ...current, provider, pathStyle: false }); setMessage("");
          }}>{Object.entries(LABELS).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</Select></FieldLabel></Field>
          <Field><FieldLabel className="ui-field block">Endpoint<Input required type="url" autoComplete="off" disabled={busy} value={current.endpoint} placeholder={EXAMPLES[current.provider].endpoint} onChange={(e) => edit("endpoint", e.target.value)} /></FieldLabel></Field>
          <p className="ui-muted">{t("settings.storage.endpointHint")}</p>
          <Field><FieldLabel className="ui-field block">Region<Input required disabled={busy} value={current.region} placeholder={EXAMPLES[current.provider].region} onChange={(e) => edit("region", e.target.value)} /></FieldLabel></Field>
          <Field><FieldLabel className="ui-field block">Bucket<Input required disabled={busy} value={current.bucket} placeholder={EXAMPLES[current.provider].bucket} onChange={(e) => edit("bucket", e.target.value)} /></FieldLabel></Field>
          <Field><FieldLabel className="ui-field block">{t("settings.storage.objectPrefix")}<Input disabled={busy} value={current.keyPrefix} onChange={(e) => edit("keyPrefix", e.target.value)} /></FieldLabel></Field>
          {current.provider === "S3" ? <label className="settings-consent"><Checkbox  disabled={busy} checked={current.pathStyle} onCheckedChange={(e) => edit("pathStyle", e === true)} />{t("settings.storage.pathStyle")}</label> : null}
          <Field><FieldLabel className="ui-field block">AccessKey ID<Input required autoComplete="off" disabled={busy} value={current.accessKeyId} onChange={(e) => edit("accessKeyId", e.target.value)} /></FieldLabel></Field>
          <Field><FieldLabel className="ui-field block">AccessKey Secret<Input required type="password" autoComplete="new-password" disabled={busy} value={current.secretAccessKey} onChange={(e) => edit("secretAccessKey", e.target.value)} /></FieldLabel></Field>
          <p className="ui-muted">{t("settings.storage.credentialPrivacyHint")}</p>
          <div className="ui-form-actions settings-save-bar"><Button variant="default"  disabled={busy}>{busy ? t("common.savingProgress") : t("settings.shared.saveConnection")}</Button>
            <Button variant="outline"  type="button" disabled={busy || !draft} onClick={() => { setDraft(null); setError(""); }}>{t("settings.storage.revert")}</Button></div>
        </form>
      </Panel>
    </div> : null}
  </PageShell>;
}
