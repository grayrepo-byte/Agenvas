import { Field, FieldGroup, FieldLabel, FieldSet, FieldLegend } from "../../shared/ui/primitives/field";
import { useQuery,useQueryClient } from "@tanstack/react-query";
import { useState,type FormEvent } from "react";
import { Navigate } from "react-router";
import {
HTTP_STATUS,ApiError,activateMediaRelayProfile,activateStorageProfile,createStorageProfile,deleteStorageProfile,getCurrentUser,getStorageSettings,rotateStorageCredentials,updateStorageProfile,
type MediaRelaySettingsRequest,type CreateStorageProfileRequest,type StorageProfile,type StorageProvider,type StorageSettings
} from "../../shared/api/client";
import { t,useLocale } from "../../shared/i18n";
import { LoadingState } from "../../shared/ui/LoadingState";
import { Notice,Panel,StatusBadge } from "../../shared/ui/PagePrimitives";
import { PageShell } from "../../shared/ui/PageShell";
import { Button } from "../../shared/ui/primitives/button";
import { Checkbox } from "../../shared/ui/primitives/checkbox";
import { Input } from "../../shared/ui/primitives/input";
import { Select } from "../../shared/ui/Select";
import { Dialog } from "../../shared/ui/Dialog";
import "./SettingsPages.css";

const CONNECTION_NAME_MAX_LENGTH = 120;
const LOCATION_FIELDS = ["provider", "endpoint", "region", "bucket", "keyPrefix", "pathStyle"] as const;
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
  const [relayDraft, setRelayDraft] = useState<MediaRelaySettingsRequest | null>(null);
  const [rotation, setRotation] = useState<{ id: string; version: number; accessKeyId: string; secret: string } | null>(null);
  const [editing, setEditing] = useState<StorageProfile | null>(null);
  const [deleting, setDeleting] = useState<{ profile: StorageProfile; version: number } | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const snapshot = query.data;
  const relayCurrent = relayDraft ?? (snapshot ? { expectedVersion: snapshot.version, profileId: snapshot.relayProfileId,
    llmRelayEnabled: snapshot.llmRelayEnabled, imageRelayEnabled: snapshot.imageRelayEnabled } : null);
  const relayStale = !!relayDraft && relayDraft.expectedVersion !== snapshot?.version;
  const current = draft ?? empty(snapshot?.version ?? 0);
  const remoteUpdate = !!snapshot && (draft?.expectedVersion ?? rotation?.version ?? snapshot.version) !== snapshot.version;
  const deleteStale = !!deleting && deleting.version !== snapshot?.version;
  const locationLocked = !!editing && (snapshot?.profiles.find((profile) => profile.id === editing.id)?.inUse ?? editing.inUse);
  const locationDraftConflict = !!editing && locationLocked && LOCATION_FIELDS.some((field) => current[field] !== editing[field]);
  const deleteInUse = !!deleting && (snapshot?.profiles.find((profile) => profile.id === deleting.profile.id)?.inUse ?? deleting.profile.inUse);

  function startEditing(profile: StorageProfile) {
    setEditing(profile);
    setDraft({ expectedVersion: snapshot?.version ?? 0, name: profile.name, provider: profile.provider, endpoint: profile.endpoint,
      region: profile.region, bucket: profile.bucket, keyPrefix: profile.keyPrefix, pathStyle: profile.pathStyle,
      accessKeyId: "", secretAccessKey: "" });
    setRotation(null); setError(""); setMessage("");
  }

  function edit<K extends keyof CreateStorageProfileRequest>(field: K, value: CreateStorageProfileRequest[K]) {
    setDraft({ ...current, [field]: value }); setMessage("");
  }
  async function write(action: () => Promise<StorageSettings>, success: string, clearDraft = false, clearRotation = false) {
    if (busy) return false;
    setBusy(true); setError(""); setMessage("");
    try {
      const result = await action(); client.setQueryData(["settings", "storage"], result);
      if (clearDraft) { setDraft(null); setEditing(null); }
      if (clearRotation) setRotation(null);
      setMessage(success);
      return true;
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : t("settings.storage.saveFailed"));
      if (cause instanceof ApiError && cause.status === HTTP_STATUS.CONFLICT) await query.refetch();
      return false;
    } finally {
      setBusy(false);
      // Request secrets never enter Query cache, persistent browser storage, or rendered status messages.
      setDraft((value) => value ? { ...value, accessKeyId: "", secretAccessKey: "" } : null);
      setRotation((value) => value ? { ...value, accessKeyId: "", secret: "" } : null);
    }
  }
  async function saveRelay(changes: Partial<MediaRelaySettingsRequest> = {}) {
    if (!relayCurrent || busy || relayStale) return;
    const requested = { ...relayCurrent, ...changes };
    setRelayDraft(requested);
    if (await write(() => activateMediaRelayProfile(requested), t("settings.storage.relaySaved"))) setRelayDraft(null);
  }
  async function refreshRelay() {
    if (busy) return;
    setBusy(true);
    try {
      const result = await query.refetch();
      if (result.isSuccess) { setRelayDraft(null); setError(""); }
    } finally { setBusy(false); }
  }
  function submit(event: FormEvent) {
    event.preventDefault();
    if (remoteUpdate || locationDraftConflict) return;
    const input = { ...current, name: current.name.trim(), endpoint: current.endpoint.trim(),
      region: current.region.trim(), bucket: current.bucket.trim(), keyPrefix: current.keyPrefix.trim() };
    void write(() => editing ? updateStorageProfile(editing.id, { ...input,
      accessKeyId: input.accessKeyId || undefined, secretAccessKey: input.secretAccessKey || undefined }) : createStorageProfile(input),
    t(editing ? "settings.storage.connectionUpdated" : "settings.storage.connectionSaved"), true);
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
      <Button variant="outline" disabled={busy} onClick={() => {
        const latest = snapshot?.profiles.find((profile) => profile.id === editing?.id);
        if (latest) startEditing(latest); else { setDraft(null); setEditing(null); }
        setRotation(null); setError("");
      }}>{t("settings.shared.refreshConfig")}</Button></Notice> : null}
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
                <Button variant="outline" disabled={busy} onClick={() => startEditing(profile)}>{t("settings.storage.editNamed", { "0": profile.name })}</Button>
                <Button variant="ghost" disabled={busy} onClick={() => { setDeleting({ profile, version: snapshot.version }); setError(""); setMessage(""); }}>{t("settings.storage.deleteNamed", { "0": profile.name })}</Button>
              </div></div>)}
          </div>
          {snapshot.profiles.length === 0 ? <p className="ui-muted">{t("settings.storage.connectionsEmpty")}</p> : null}
        </Panel>
        <Panel title={t("settings.storage.relayTitle")} description={t("settings.storage.relayDescription")}>
          <Field><FieldLabel>{t("settings.storage.relayConnection")}</FieldLabel>
            <Select aria-label={t("settings.storage.relayConnection")} disabled={busy || relayStale} value={relayCurrent?.profileId ?? ""}
              onChange={(event) => void saveRelay({ profileId: event.target.value || null })}>
              <option value="">{t("settings.storage.relayDisabled")}</option>
              {snapshot.profiles.map((profile) => <option key={profile.id} value={profile.id}>{profile.name}</option>)}
            </Select>
          </Field>
          <FieldSet disabled={busy || relayStale || relayCurrent?.profileId === null}>
            <FieldLegend>{t("settings.storage.relayFunctions")}</FieldLegend>
            <FieldGroup>
              <Field orientation="horizontal">
                <Checkbox id="llm-relay" disabled={busy || relayStale || relayCurrent?.profileId === null} checked={relayCurrent?.llmRelayEnabled ?? false}
                  onCheckedChange={(checked) => void saveRelay({ llmRelayEnabled: checked === true })} />
                <FieldLabel htmlFor="llm-relay">{t("settings.storage.relayLlm")}</FieldLabel>
              </Field>
              <Field orientation="horizontal">
                <Checkbox id="image-relay" disabled={busy || relayStale || relayCurrent?.profileId === null} checked={relayCurrent?.imageRelayEnabled ?? false}
                  onCheckedChange={(checked) => void saveRelay({ imageRelayEnabled: checked === true })} />
                <FieldLabel htmlFor="image-relay">{t("settings.storage.relayImages")}</FieldLabel>
              </Field>
            </FieldGroup>
          </FieldSet>
          {relayDraft && !busy ? <div className="ui-form-actions">
            {relayStale ? <Notice tone="warning">{t("settings.storage.versionChanged")}</Notice> : null}
            <Button variant="outline" disabled={relayStale} onClick={() => void saveRelay()}>{t("settings.storage.relayRetry")}</Button>
            <Button variant="outline" disabled={busy || query.isFetching} onClick={() => void refreshRelay()}>{t("settings.shared.refreshConfig")}</Button>
          </div> : null}
          <p className="ui-muted">{t("settings.storage.relayFunctionHint")}</p>
          <p className="ui-muted">{t("settings.storage.relayPolicy")}</p>
        </Panel>
        {rotation ? <Panel title={t("settings.storage.updateCredentials")} description={t("settings.storage.credentialUpdateHint")}>
          <form className="ui-form" onSubmit={(event) => { event.preventDefault(); void write(() => rotateStorageCredentials(rotation.id,
            { expectedVersion: rotation.version, accessKeyId: rotation.accessKeyId, secretAccessKey: rotation.secret }), t("settings.storage.credentialsUpdated"), false, true); }}>
            <FieldGroup>
            <Field><FieldLabel className="ui-field block">{t("settings.storage.newAccessKeyId")}<Input required autoComplete="off" disabled={busy} value={rotation.accessKeyId}
              onChange={(event) => setRotation({ ...rotation, accessKeyId: event.target.value })} /></FieldLabel></Field>
            <Field><FieldLabel className="ui-field block">{t("settings.storage.newAccessKeySecret")}<Input required type="password" autoComplete="new-password" disabled={busy} value={rotation.secret}
              onChange={(event) => setRotation({ ...rotation, secret: event.target.value })} /></FieldLabel></Field>
            <div className="ui-form-actions"><Button variant="default"  disabled={busy}>{t("settings.storage.saveCredentials")}</Button>
              <Button variant="outline" type="button"  disabled={busy} onClick={() => setRotation(null)}>{t("settings.storage.cancelUpdate")}</Button></div>
            </FieldGroup>
          </form>
        </Panel> : null}
      </div>
      <Panel title={t(editing ? "settings.storage.editConnection" : "settings.storage.addConnection")} description={t("settings.storage.immutableLocationHint")}>
        <form className="ui-form" onSubmit={submit} aria-busy={busy}>
          <FieldGroup>
          {locationLocked ? <Notice tone="warning">{t("settings.storage.locationLocked")}
            {locationDraftConflict ? <Button variant="outline" type="button" disabled={busy} onClick={() => {
              const latest = snapshot?.profiles.find((profile) => profile.id === editing?.id);
              if (latest) startEditing(latest);
            }}>{t("settings.shared.refreshConfig")}</Button> : null}
          </Notice> : null}
          <Field><FieldLabel className="ui-field block">{t("settings.shared.connectionName")}<Input required maxLength={CONNECTION_NAME_MAX_LENGTH} disabled={busy} value={current.name} onChange={(e) => edit("name", e.target.value)} /></FieldLabel></Field>
          <Field><FieldLabel className="ui-field block">{t("settings.storage.storageType")}<Select disabled={busy || locationLocked} value={current.provider} onChange={(e) => {
            const provider = e.target.value as StorageProvider; setDraft({ ...current, provider, pathStyle: false }); setMessage("");
          }}>{Object.entries(LABELS).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</Select></FieldLabel></Field>
          <Field><FieldLabel className="ui-field block">Endpoint<Input required type="url" autoComplete="off" disabled={busy || locationLocked} value={current.endpoint} placeholder={EXAMPLES[current.provider].endpoint} onChange={(e) => edit("endpoint", e.target.value)} /></FieldLabel></Field>
          <p className="ui-muted">{t("settings.storage.endpointHint")}</p>
          <Field><FieldLabel className="ui-field block">Region<Input required disabled={busy || locationLocked} value={current.region} placeholder={EXAMPLES[current.provider].region} onChange={(e) => edit("region", e.target.value)} /></FieldLabel></Field>
          {current.provider === "ALIYUN_OSS" ? <p className="ui-muted">{t("settings.storage.ossRegionHint")}</p> : null}
          <Field><FieldLabel className="ui-field block">Bucket<Input required disabled={busy || locationLocked} value={current.bucket} placeholder={EXAMPLES[current.provider].bucket} onChange={(e) => edit("bucket", e.target.value)} /></FieldLabel></Field>
          <Field><FieldLabel className="ui-field block">{t("settings.storage.objectPrefix")}<Input disabled={busy || locationLocked} value={current.keyPrefix} onChange={(e) => edit("keyPrefix", e.target.value)} /></FieldLabel></Field>
          {current.provider === "S3" ? <label className="settings-consent"><Checkbox disabled={busy || locationLocked} checked={current.pathStyle} onCheckedChange={(e) => edit("pathStyle", e === true)} />{t("settings.storage.pathStyle")}</label> : null}
          <Field><FieldLabel className="ui-field block">AccessKey ID<Input required={!editing || !!current.secretAccessKey} autoComplete="off" disabled={busy} value={current.accessKeyId} onChange={(e) => edit("accessKeyId", e.target.value)} /></FieldLabel></Field>
          <Field><FieldLabel className="ui-field block">AccessKey Secret<Input required={!editing || !!current.accessKeyId} type="password" autoComplete="new-password" disabled={busy} value={current.secretAccessKey} onChange={(e) => edit("secretAccessKey", e.target.value)} /></FieldLabel></Field>
          {editing ? <p className="ui-muted">{t("settings.storage.keepCredentialsHint")}</p> : null}
          <p className="ui-muted">{t("settings.storage.credentialPrivacyHint")}</p>
          <div className="ui-form-actions settings-save-bar"><Button variant="default" disabled={busy || remoteUpdate || locationDraftConflict}>{busy ? t("common.savingProgress") : t(editing ? "settings.storage.saveChanges" : "settings.shared.saveConnection")}</Button>
            <Button variant="outline" type="button" disabled={busy || !draft} onClick={() => { setDraft(null); setEditing(null); setError(""); }}>{t(editing ? "common.cancel" : "settings.storage.revert")}</Button></div>
          </FieldGroup>
        </form>
      </Panel>
    </div> : null}
    {deleting ? <Dialog compact title={t("settings.storage.deleteNamed", { "0": deleting.profile.name })}
      busy={busy} onClose={() => { setDeleting(null); setError(""); }}
      onSubmit={(event) => {
        event.preventDefault();
        if (deleteStale || deleteInUse) return;
        void write(() => deleteStorageProfile(deleting.profile.id, deleting.version), t("settings.storage.connectionDeleted"))
          .then((saved) => {
            if (saved) {
              setDeleting(null);
              if (editing?.id === deleting.profile.id) { setEditing(null); setDraft(null); }
              if (rotation?.id === deleting.profile.id) setRotation(null);
            }
          });
      }} footer={<>
        <Button variant="outline" type="button" disabled={busy} onClick={() => { setDeleting(null); setError(""); }}>{t("common.cancel")}</Button>
        <Button variant="destructive" type="submit" disabled={busy || deleteStale || deleteInUse}>{t(busy ? "settings.storage.deleting" : "settings.storage.confirmDelete")}</Button>
      </>}>
      <p>{t("settings.storage.deleteHint")}</p>
      {deleteInUse ? <Notice tone="warning">{t("settings.storage.locationLocked")}</Notice> : null}
      {deleteStale ? <Notice tone="warning" title={t("settings.storage.versionChanged")}>
        <Button variant="outline" type="button" disabled={busy} onClick={() => {
          const latest = snapshot?.profiles.find((profile) => profile.id === deleting.profile.id);
          setDeleting(latest && snapshot ? { profile: latest, version: snapshot.version } : null); setError("");
        }}>{t("settings.shared.refreshConfig")}</Button>
      </Notice> : null}
      {error ? <Notice tone="danger">{error}</Notice> : null}
    </Dialog> : null}
  </PageShell>;
}
