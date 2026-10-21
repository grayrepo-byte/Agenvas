import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useState, type FormEvent } from "react";
import { Navigate } from "react-router";
import {
  HTTP_STATUS, ApiError, activateMediaRelayProfile, activateStorageProfile, createStorageProfile,
  deleteStorageProfile, getCurrentUser, getStorageSettings, rotateStorageCredentials, updateStorageProfile,
  type MediaRelaySettingsRequest, type CreateStorageProfileRequest, type StorageProfile, type StorageProvider, type StorageSettings,
} from "../../shared/api/client";
import { t, useLocale } from "../../shared/i18n";
import { Archive, GlobeSimple, HardDrives, Key, LockSimple, PencilSimple, Plus, ShieldCheck, Trash } from "../../shared/ui/icons";
import { LoadingState } from "../../shared/ui/LoadingState";
import { EmptyState, Notice, Panel, StatusBadge } from "../../shared/ui/PagePrimitives";
import { PageShell } from "../../shared/ui/PageShell";
import { Button } from "../../shared/ui/primitives/button";
import { Checkbox } from "../../shared/ui/primitives/checkbox";
import { Field, FieldDescription, FieldGroup, FieldLabel, FieldSet, FieldLegend } from "../../shared/ui/primitives/field";
import { Input } from "../../shared/ui/primitives/input";
import { Switch } from "../../shared/ui/primitives/switch";
import { Select } from "../../shared/ui/Select";
import { Dialog } from "../../shared/ui/Dialog";
import "./SettingsPages.css";
import "./StorageSettingsPage.css";

const CONNECTION_NAME_MAX_LENGTH = 120;
const NEW_CONNECTION = "new";
const LOCATION_FIELDS = ["provider", "endpoint", "region", "bucket", "keyPrefix", "pathStyle"] as const;
const LABELS: Record<StorageProvider, string> = {
  get ALIYUN_OSS() { return t("settings.storage.oss"); }, get TENCENT_COS() { return t("settings.storage.cos"); },
  get S3() { return t("settings.storage.s3"); }, SMB: "SMB", WEBDAV: "WebDAV",
};
const EXAMPLES: Record<StorageProvider, { endpoint: string; region: string; bucket: string }> = {
  ALIYUN_OSS: { endpoint: "https://oss-cn-hangzhou.aliyuncs.com", region: "cn-hangzhou", bucket: "my-bucket" },
  TENCENT_COS: { endpoint: "https://cos.ap-guangzhou.myqcloud.com", region: "ap-guangzhou", bucket: "my-bucket-1250000000" },
  SMB: { endpoint: "smb://nas.example.com/media/archives", region: "", bucket: "" },
  WEBDAV: { endpoint: "https://nas.example.com/dav/media", region: "", bucket: "" },
  S3: { endpoint: "https://s3.us-east-1.amazonaws.com", region: "us-east-1", bucket: "my-bucket" },
};
type ConnectionEditor = { key: string; profile: StorageProfile | null; version: number };
type ConnectionDraft = { profile: StorageProfile | null; value: CreateStorageProfileRequest };
type ErrorScope = "page" | "connection" | "credentials" | "delete";
function fileProtocol(provider: StorageProvider) { return provider === "SMB" || provider === "WEBDAV"; }
function fromProfile(profile: StorageProfile | null, version: number): CreateStorageProfileRequest {
  return profile ? { expectedVersion: version, name: profile.name, provider: profile.provider, endpoint: profile.endpoint,
    region: profile.region, bucket: profile.bucket, keyPrefix: profile.keyPrefix, pathStyle: profile.pathStyle,
    accessKeyId: "", secretAccessKey: "" } : { expectedVersion: version, name: "", provider: "ALIYUN_OSS", endpoint: "",
    region: "", bucket: "", keyPrefix: "agenvas", pathStyle: false, accessKeyId: "", secretAccessKey: "" };
}
function changed(value: CreateStorageProfileRequest, profile: StorageProfile | null) {
  const initial = fromProfile(profile, value.expectedVersion);
  const fields = ["name", ...LOCATION_FIELDS, "accessKeyId", "secretAccessKey"] as const;
  return fields.some((field) => value[field] !== initial[field]);
}

/** Each changed connection keeps its original CAS base. Merely opening a dialog creates no retained draft. */
export function StorageSettingsPage() {
  useLocale();
  const client = useQueryClient();
  const session = useQuery({ queryKey: ["auth", "me"], queryFn: getCurrentUser, retry: false });
  const query = useQuery({ queryKey: ["settings", "storage"], queryFn: getStorageSettings, enabled: session.isSuccess, retry: false });
  const [connectionEditor, setConnectionEditor] = useState<ConnectionEditor | null>(null);
  const [drafts, setDrafts] = useState<Record<string, ConnectionDraft>>({});
  const [relayDraft, setRelayDraft] = useState<MediaRelaySettingsRequest | null>(null);
  const [rotation, setRotation] = useState<{ id: string; name: string; provider: StorageProvider; version: number; accessKeyId: string; secret: string } | null>(null);
  const [deleting, setDeleting] = useState<{ profile: StorageProfile; version: number } | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<{ message: string; scope: ErrorScope } | null>(null);
  const [message, setMessage] = useState("");
  const snapshot = query.data;
  const editing = connectionEditor?.profile ?? null;
  const current = connectionEditor ? drafts[connectionEditor.key]?.value ?? fromProfile(editing, connectionEditor.version) : fromProfile(null, snapshot?.version ?? 0);
  const connectionDirty = changed(current, editing);
  const relayCurrent = relayDraft ?? (snapshot ? { expectedVersion: snapshot.version, profileId: snapshot.relayProfileId,
    llmRelayEnabled: snapshot.llmRelayEnabled, imageRelayEnabled: snapshot.imageRelayEnabled } : null);
  const relayStale = !!relayDraft && relayDraft.expectedVersion !== snapshot?.version;
  const remoteUpdate = !!connectionEditor && !!snapshot && current.expectedVersion !== snapshot.version;
  const rotationStale = !!rotation && rotation.version !== snapshot?.version;
  const deleteStale = !!deleting && deleting.version !== snapshot?.version;
  const isFile = fileProtocol(current.provider);
  const locationLocked = !!editing && (snapshot?.profiles.find((profile) => profile.id === editing.id)?.inUse ?? editing.inUse);
  const locationDraftConflict = !!editing && locationLocked && LOCATION_FIELDS.some((field) => current[field] !== editing[field]);
  const deleteInUse = !!deleting && (snapshot?.profiles.find((profile) => profile.id === deleting.profile.id)?.inUse ?? deleting.profile.inUse);
  const archiveProfile = snapshot?.profiles.find((profile) => profile.id === snapshot.activeProfileId);

  function removeDraft(key: string) {
    setDrafts((old) => { const next = { ...old }; delete next[key]; return next; });
  }
  function openConnection(profile: StorageProfile | null) {
    const key = profile?.id ?? NEW_CONNECTION;
    const savedDraft = drafts[key];
    setConnectionEditor({ key, profile: savedDraft?.profile ?? profile, version: savedDraft?.value.expectedVersion ?? snapshot?.version ?? 0 });
    setError(null); setMessage("");
  }
  function editConnection(value: CreateStorageProfileRequest) {
    if (!connectionEditor) return;
    const { key, profile } = connectionEditor;
    setDrafts((old) => {
      const next = { ...old };
      if (changed(value, profile)) next[key] = { profile, value }; else delete next[key];
      return next;
    });
    setMessage("");
  }
  function edit<K extends keyof CreateStorageProfileRequest>(field: K, value: CreateStorageProfileRequest[K]) {
    editConnection({ ...current, [field]: value });
  }
  function closeConnection(discard = false) {
    if (!connectionEditor) return;
    if (discard) removeDraft(connectionEditor.key);
    else editConnection({ ...current, accessKeyId: "", secretAccessKey: "" });
    setConnectionEditor(null); setError(null);
  }
  function reloadConnection() {
    if (!connectionEditor || !snapshot) return;
    const latest = snapshot.profiles.find((profile) => profile.id === editing?.id);
    removeDraft(connectionEditor.key);
    if (editing && !latest) setConnectionEditor(null);
    else setConnectionEditor({ key: connectionEditor.key, profile: latest ?? null, version: snapshot.version });
    setError(null);
  }
  async function write(action: () => Promise<StorageSettings>, success: string, scope: ErrorScope = "page") {
    if (busy) return false;
    setBusy(true); setError(null); setMessage("");
    try {
      const result = await action(); client.setQueryData(["settings", "storage"], result); setMessage(success);
      return true;
    } catch (cause) {
      setError({ message: cause instanceof ApiError ? cause.message : t("settings.storage.saveFailed"), scope });
      if (cause instanceof ApiError && cause.status === HTTP_STATUS.CONFLICT) await query.refetch();
      return false;
    } finally {
      setBusy(false);
      // Request credentials never enter Query cache or retained drafts after a request or dialog close.
      setDrafts((old) => Object.fromEntries(Object.entries(old).flatMap(([key, entry]) => {
        const value = { ...entry.value, accessKeyId: "", secretAccessKey: "" };
        return changed(value, entry.profile) ? [[key, { ...entry, value }]] : [];
      })));
      setRotation((value) => value ? { ...value, accessKeyId: "", secret: "" } : null);
    }
  }
  async function saveRelay(changes: Partial<MediaRelaySettingsRequest> = {}) {
    if (!relayCurrent || busy || relayStale) return;
    const requested = { ...relayCurrent, ...changes }; setRelayDraft(requested);
    if (await write(() => activateMediaRelayProfile(requested), t("settings.storage.relaySaved"))) setRelayDraft(null);
  }
  async function refreshRelay() {
    if (busy) return;
    setBusy(true);
    try { const result = await query.refetch(); if (result.isSuccess) { setRelayDraft(null); setError(null); } }
    finally { setBusy(false); }
  }
  function submit(event: FormEvent) {
    event.preventDefault();
    if (!connectionEditor || remoteUpdate || locationDraftConflict || busy) return;
    const key = connectionEditor.key;
    const input = { ...current, name: current.name.trim(), endpoint: current.endpoint.trim(),
      region: current.region.trim(), bucket: current.bucket.trim(), keyPrefix: current.keyPrefix.trim() };
    void write(() => editing ? updateStorageProfile(editing.id, { ...input,
      accessKeyId: input.accessKeyId || undefined, secretAccessKey: input.secretAccessKey || undefined }) : createStorageProfile(input),
    t(editing ? "settings.storage.connectionUpdated" : "settings.storage.connectionSaved"), "connection")
      .then((saved) => { if (saved) { removeDraft(key); setConnectionEditor(null); } });
  }
  function errorNotice(scope: ErrorScope, credentials = false) {
    return error?.scope === scope ? <Notice tone="danger"><p>{error.message}</p>{credentials ? <p>{t("settings.storage.credentialsCleared")}</p> : null}</Notice> : null;
  }
  if (session.error instanceof ApiError && session.error.status === HTTP_STATUS.UNAUTHORIZED
      || query.error instanceof ApiError && query.error.status === HTTP_STATUS.UNAUTHORIZED) return <Navigate to="/login" replace />;
  return <PageShell title={t("settings.storage.title")} description={t("settings.storage.description")}
    actions={<Button type="button" disabled={!snapshot || busy} onClick={() => openConnection(null)}><Plus data-icon="inline-start" aria-hidden />
      {t(drafts[NEW_CONNECTION] ? "settings.storage.resumeDraft" : "settings.storage.addConnection")}</Button>}>
    <div className="storage-settings-workspace">
      {query.isPending ? <LoadingState label={t("settings.storage.loading")} /> : null}
      {query.isError ? <Notice tone="danger" title={t("api.errors.storageSettingsUnavailable")}><p>{query.error.message}</p>
        <Button variant="outline" disabled={query.isFetching || busy} onClick={() => void query.refetch()}>{t("common.refresh")}</Button></Notice> : null}
      {errorNotice("page")}
      {message ? <Notice tone="success">{message}</Notice> : null}
      {snapshot ? <>
        <Panel className="storage-directory-panel" title={t("settings.storage.directoryTitle")} description={t("settings.storage.directoryDescription")}
          actions={<StatusBadge>{t("settings.storage.connectionCount", { "0": snapshot.profiles.length })}</StatusBadge>}>
          {snapshot.profiles.length ? <div className="storage-connection-list">
            {snapshot.profiles.map((profile) => <section className="storage-connection-card" key={profile.id} aria-label={profile.name}>
              <div className="storage-connection-identity"><span className="storage-connection-symbol" aria-hidden><HardDrives weight="duotone" /></span>
                <div className="storage-connection-copy"><h3>{profile.name}</h3><p>{LABELS[profile.provider]}{!fileProtocol(profile.provider) ? ` · ${profile.bucket} · ${profile.region}` : ""}</p></div>
                <div className="storage-connection-badges">
                  {profile.id === snapshot.activeProfileId ? <StatusBadge tone="success">{t("settings.storage.archiveUsage")}</StatusBadge> : null}
                  {profile.id === snapshot.relayProfileId ? <StatusBadge>{t("settings.storage.relayUsage")}</StatusBadge> : null}
                  {drafts[profile.id] ? <StatusBadge tone="warning">{t("common.unsavedChanges")}</StatusBadge> : null}
                </div>
              </div>
              <p className="storage-connection-endpoint">{profile.endpoint}</p>
              <div className="storage-connection-footer"><div className="storage-connection-meta"><span><Key aria-hidden />{profile.accessKeyMask}</span>
                {profile.inUse ? <span title={t("settings.storage.locationLocked")}><LockSimple aria-hidden />{t("settings.storage.inUse")}</span> : null}</div>
                <div className="storage-connection-actions">
                  <Button type="button" variant="outline" size="sm" disabled={busy} aria-label={t("settings.storage.editNamed", { "0": profile.name })} onClick={() => openConnection(profile)}><PencilSimple data-icon="inline-start" aria-hidden />{t("settings.storage.editConnection")}</Button>
                  <Button type="button" variant="ghost" size="sm" disabled={busy} aria-label={t("settings.storage.updateCredentialsNamed", { "0": profile.name })} onClick={() => {
                    setRotation({ id: profile.id, name: profile.name, provider: profile.provider, version: snapshot.version, accessKeyId: "", secret: "" }); setError(null); setMessage("");
                  }}><Key data-icon="inline-start" aria-hidden />{t("settings.storage.credentialsSection")}</Button>
                  <Button type="button" variant="ghost" size="icon-sm" disabled={busy} aria-label={t("settings.storage.deleteNamed", { "0": profile.name })} onClick={() => { setDeleting({ profile, version: snapshot.version }); setError(null); setMessage(""); }}><Trash aria-hidden /></Button>
                </div>
              </div>
            </section>)}
          </div> : <EmptyState icon={<HardDrives aria-hidden />} title={t("settings.storage.connectionsEmpty")} />}
          <p className="storage-privacy-note"><ShieldCheck aria-hidden />{t("settings.storage.credentialPrivacyHint")}</p>
        </Panel>
        <div className="storage-purpose-grid">
          <Panel className="storage-purpose-panel" title={t("settings.storage.defaultStorage")} description={t("settings.storage.archiveDescription")}
            actions={<Archive aria-hidden />}>
            <div className="storage-destination storage-current-destination"><strong>{archiveProfile?.name ?? t("settings.storage.local")}</strong><StatusBadge tone="success">{t("settings.storage.currentDefault")}</StatusBadge></div>
            <FieldGroup>
              <Field data-disabled={busy || undefined}><FieldLabel htmlFor="storage-archive-destination">{t("settings.storage.archiveConnection")}</FieldLabel>
                <Select id="storage-archive-destination" disabled={busy} value={snapshot.activeProfileId ?? ""} onChange={(event) => {
                  const profileId = event.target.value || null;
                  void write(() => activateStorageProfile({ expectedVersion: snapshot.version, profileId }), t(profileId ? "settings.storage.cloudDefaultHint" : "settings.storage.localDefaultHint"));
                }}><option value="">{t("settings.storage.local")}</option>{snapshot.profiles.map((profile) => <option key={profile.id} value={profile.id}>{profile.name}</option>)}</Select>
                <FieldDescription>{snapshot.activeProfileId === null ? t("settings.storage.localDirectoryHint") : t("settings.storage.immutableLocationHint")}</FieldDescription>
              </Field>
            </FieldGroup>
          </Panel>
          <Panel className="storage-purpose-panel" title={t("settings.storage.relayTitle")} description={t("settings.storage.relayDescription")}
            actions={<GlobeSimple aria-hidden />}>
            <FieldGroup>
              <Field data-disabled={busy || relayStale || undefined}><FieldLabel htmlFor="storage-relay-destination">{t("settings.storage.relayConnection")}</FieldLabel>
                <Select id="storage-relay-destination" disabled={busy || relayStale} value={relayCurrent?.profileId ?? ""} onChange={(event) => void saveRelay({ profileId: event.target.value || null })}>
                  <option value="">{t("settings.storage.relayDisabled")}</option>{snapshot.profiles.filter((profile) => !fileProtocol(profile.provider)).map((profile) => <option key={profile.id} value={profile.id}>{profile.name}</option>)}
                </Select></Field>
              <FieldSet disabled={busy || relayStale || relayCurrent?.profileId === null} className="storage-relay-functions">
                <FieldLegend variant="label">{t("settings.storage.relayFunctions")}</FieldLegend>
                <FieldGroup>
                  <Field orientation="horizontal"><FieldLabel htmlFor="llm-relay">{t("settings.storage.relayLlm")}</FieldLabel><Switch id="llm-relay" disabled={busy || relayStale || relayCurrent?.profileId === null} checked={relayCurrent?.llmRelayEnabled ?? false} onCheckedChange={(checked) => void saveRelay({ llmRelayEnabled: checked })} /></Field>
                  <Field orientation="horizontal"><FieldLabel htmlFor="image-relay">{t("settings.storage.relayImages")}</FieldLabel><Switch id="image-relay" disabled={busy || relayStale || relayCurrent?.profileId === null} checked={relayCurrent?.imageRelayEnabled ?? false} onCheckedChange={(checked) => void saveRelay({ imageRelayEnabled: checked })} /></Field>
                </FieldGroup>
                <FieldDescription>{t("settings.storage.relayFunctionHint")}</FieldDescription>
              </FieldSet>
              {relayDraft && !busy ? <div className="ui-stack">
                {relayStale ? <Notice tone="warning">{t("settings.storage.versionChanged")}</Notice> : null}
                <div className="ui-form-actions"><Button variant="outline" disabled={relayStale} onClick={() => void saveRelay()}>{t("settings.storage.relayRetry")}</Button>
                  <Button variant="ghost" disabled={busy || query.isFetching} onClick={() => void refreshRelay()}>{t("settings.shared.refreshConfig")}</Button></div>
              </div> : null}
              <FieldDescription>{t("settings.storage.relayPolicy")}</FieldDescription>
            </FieldGroup>
          </Panel>
        </div>
      </> : null}
    </div>
    {connectionEditor ? <Dialog className="storage-connection-dialog" title={t(editing ? "settings.storage.editConnection" : "settings.storage.addConnection")}
      description={editing?.name ?? t("settings.storage.directoryDescription")} busy={busy} onClose={() => closeConnection()} onSubmit={submit}
      footer={<><div className="storage-dialog-draft-status">{connectionDirty ? t("common.unsavedChanges") : null}</div>
        {connectionDirty ? <Button variant="ghost" type="button" disabled={busy} onClick={() => closeConnection(true)}>{t("settings.storage.discardDraft")}</Button> : null}
        <Button variant="outline" type="button" disabled={busy} onClick={() => closeConnection()}>{t("common.close")}</Button>
        <Button type="submit" disabled={busy || remoteUpdate || locationDraftConflict}>{busy ? t("common.savingProgress") : t(editing ? "settings.storage.saveChanges" : "settings.shared.saveConnection")}</Button></>}>
      <FieldGroup>
        {errorNotice("connection", true)}
        {remoteUpdate ? <Notice tone="warning" title={t("settings.storage.versionChanged")}><p>{t("settings.storage.refreshDraftHint")}</p><Button variant="outline" type="button" disabled={busy} onClick={reloadConnection}>{t("settings.shared.refreshConfig")}</Button></Notice> : null}
        <FieldGroup className="storage-form-columns">
          <Field><FieldLabel htmlFor="storage-name">{t("settings.shared.connectionName")}</FieldLabel><Input id="storage-name" required maxLength={CONNECTION_NAME_MAX_LENGTH} disabled={busy} value={current.name} onChange={(event) => edit("name", event.target.value)} /></Field>
          <Field data-disabled={busy || locationLocked || undefined}><FieldLabel htmlFor="storage-provider">{t("settings.storage.storageType")}</FieldLabel><Select id="storage-provider" disabled={busy || locationLocked} value={current.provider} onChange={(event) => {
            const provider = event.target.value as StorageProvider;
            editConnection({ ...current, provider, pathStyle: false, endpoint: fileProtocol(provider) !== isFile || provider !== current.provider && fileProtocol(provider) ? "" : current.endpoint,
              region: fileProtocol(provider) ? "" : current.region, bucket: fileProtocol(provider) ? "" : current.bucket, accessKeyId: "", secretAccessKey: "" });
          }}>{Object.entries(LABELS).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</Select></Field>
        </FieldGroup>
        <FieldSet className="storage-form-section"><FieldLegend>{t("settings.storage.locationSection")}</FieldLegend>
          {locationLocked ? <Notice tone="warning">{t("settings.storage.locationLocked")}{locationDraftConflict && !remoteUpdate ? <Button variant="outline" type="button" disabled={busy} onClick={reloadConnection}>{t("settings.shared.refreshConfig")}</Button> : null}</Notice> : null}
          <FieldGroup>
            <Field data-disabled={busy || locationLocked || undefined}><FieldLabel htmlFor="storage-endpoint">Endpoint</FieldLabel><Input id="storage-endpoint" required type="url" autoComplete="off" disabled={busy || locationLocked} value={current.endpoint} placeholder={EXAMPLES[current.provider].endpoint} onChange={(event) => edit("endpoint", event.target.value)} />
              <FieldDescription>{t(current.provider === "SMB" ? "settings.storage.smbEndpointHint" : current.provider === "WEBDAV" ? "settings.storage.webdavEndpointHint" : "settings.storage.endpointHint")}</FieldDescription></Field>
            {!isFile ? <FieldGroup className="storage-form-columns">
              <Field data-disabled={busy || locationLocked || undefined}><FieldLabel htmlFor="storage-region">Region</FieldLabel><Input id="storage-region" required disabled={busy || locationLocked} value={current.region} placeholder={EXAMPLES[current.provider].region} onChange={(event) => edit("region", event.target.value)} />
                {current.provider === "ALIYUN_OSS" ? <FieldDescription>{t("settings.storage.ossRegionHint")}</FieldDescription> : null}</Field>
              <Field data-disabled={busy || locationLocked || undefined}><FieldLabel htmlFor="storage-bucket">Bucket</FieldLabel><Input id="storage-bucket" required disabled={busy || locationLocked} value={current.bucket} placeholder={EXAMPLES[current.provider].bucket} onChange={(event) => edit("bucket", event.target.value)} /></Field>
            </FieldGroup> : null}
            <Field data-disabled={busy || locationLocked || undefined}><FieldLabel htmlFor="storage-prefix">{t("settings.storage.objectPrefix")}</FieldLabel><Input id="storage-prefix" disabled={busy || locationLocked} value={current.keyPrefix} onChange={(event) => edit("keyPrefix", event.target.value)} /></Field>
            {current.provider === "S3" ? <Field orientation="horizontal"><Checkbox id="storage-path-style" disabled={busy || locationLocked} checked={current.pathStyle} onCheckedChange={(checked) => edit("pathStyle", checked === true)} /><FieldLabel htmlFor="storage-path-style">{t("settings.storage.pathStyle")}</FieldLabel></Field> : null}
          </FieldGroup>
        </FieldSet>
        <FieldSet className="storage-form-section"><FieldLegend>{t("settings.storage.credentialsSection")}</FieldLegend>
          <FieldGroup className="storage-form-columns">
            <Field><FieldLabel htmlFor="storage-access-id">{isFile ? t("settings.storage.username") : "AccessKey ID"}</FieldLabel><Input id="storage-access-id" required={!editing || !!current.secretAccessKey} autoComplete="off" disabled={busy} value={current.accessKeyId} onChange={(event) => edit("accessKeyId", event.target.value)} /></Field>
            <Field><FieldLabel htmlFor="storage-access-secret">{isFile ? t("settings.storage.password") : "AccessKey Secret"}</FieldLabel><Input id="storage-access-secret" required={!editing || !!current.accessKeyId} type="password" autoComplete="new-password" disabled={busy} value={current.secretAccessKey} onChange={(event) => edit("secretAccessKey", event.target.value)} /></Field>
          </FieldGroup>
          <FieldDescription>{t(editing ? "settings.storage.keepCredentialsHint" : "settings.storage.credentialPrivacyHint")}</FieldDescription>
          {isFile ? <FieldDescription>{t("settings.storage.fileProtocolHint")}</FieldDescription> : null}
        </FieldSet>
        {connectionDirty ? <p className="storage-dialog-close-hint">{t("settings.storage.draftKept")}</p> : null}
      </FieldGroup>
    </Dialog> : null}
    {rotation ? <Dialog compact title={t("settings.storage.updateCredentials")} description={rotation.name} busy={busy} onClose={() => { setRotation(null); setError(null); }}
      onSubmit={(event) => { event.preventDefault(); if (busy || rotationStale) return;
        void write(() => rotateStorageCredentials(rotation.id, { expectedVersion: rotation.version, accessKeyId: rotation.accessKeyId, secretAccessKey: rotation.secret }), t("settings.storage.credentialsUpdated"), "credentials").then((saved) => { if (saved) setRotation(null); });
      }} footer={<><Button variant="outline" type="button" disabled={busy} onClick={() => { setRotation(null); setError(null); }}>{t("settings.storage.cancelUpdate")}</Button><Button type="submit" disabled={busy || rotationStale}>{busy ? t("common.savingProgress") : t("settings.storage.saveCredentials")}</Button></>}>
      <FieldGroup>
        <p className="ui-muted">{t("settings.storage.credentialUpdateHint")}</p>
        {errorNotice("credentials", true)}
        {rotationStale ? <Notice tone="warning" title={t("settings.storage.versionChanged")}><p>{t("settings.storage.refreshDraftHint")}</p><Button type="button" variant="outline" disabled={busy} onClick={() => {
          const latest = snapshot?.profiles.find((profile) => profile.id === rotation.id);
          setRotation(latest && snapshot ? { id: latest.id, name: latest.name, provider: latest.provider, version: snapshot.version, accessKeyId: "", secret: "" } : null); setError(null);
        }}>{t("settings.shared.refreshConfig")}</Button></Notice> : null}
        <Field><FieldLabel htmlFor="storage-rotate-id">{t(fileProtocol(rotation.provider) ? "settings.storage.username" : "settings.storage.newAccessKeyId")}</FieldLabel><Input id="storage-rotate-id" required autoComplete="off" disabled={busy} value={rotation.accessKeyId} onChange={(event) => setRotation({ ...rotation, accessKeyId: event.target.value })} /></Field>
        <Field><FieldLabel htmlFor="storage-rotate-secret">{t(fileProtocol(rotation.provider) ? "settings.storage.password" : "settings.storage.newAccessKeySecret")}</FieldLabel><Input id="storage-rotate-secret" required type="password" autoComplete="new-password" disabled={busy} value={rotation.secret} onChange={(event) => setRotation({ ...rotation, secret: event.target.value })} /></Field>
      </FieldGroup>
    </Dialog> : null}
    {deleting ? <Dialog compact title={t("settings.storage.deleteNamed", { "0": deleting.profile.name })} busy={busy} onClose={() => { setDeleting(null); setError(null); }}
      onSubmit={(event) => {
        event.preventDefault(); if (deleteStale || deleteInUse || busy) return;
        void write(() => deleteStorageProfile(deleting.profile.id, deleting.version), t("settings.storage.connectionDeleted"), "delete")
          .then((saved) => { if (saved) { removeDraft(deleting.profile.id); setDeleting(null); } });
      }} footer={<><Button variant="outline" type="button" disabled={busy} onClick={() => { setDeleting(null); setError(null); }}>{t("common.cancel")}</Button>
        <Button variant="destructive" type="submit" disabled={busy || deleteStale || deleteInUse}>{t(busy ? "settings.storage.deleting" : "settings.storage.confirmDelete")}</Button></>}>
      <FieldGroup><p>{t("settings.storage.deleteHint")}</p>
        {deleteInUse ? <Notice tone="warning">{t("settings.storage.locationLocked")}</Notice> : null}
        {deleteStale ? <Notice tone="warning" title={t("settings.storage.versionChanged")}><Button variant="outline" type="button" disabled={busy} onClick={() => {
          const latest = snapshot?.profiles.find((profile) => profile.id === deleting.profile.id);
          setDeleting(latest && snapshot ? { profile: latest, version: snapshot.version } : null); setError(null);
        }}>{t("settings.shared.refreshConfig")}</Button></Notice> : null}
        {errorNotice("delete")}
      </FieldGroup>
    </Dialog> : null}
  </PageShell>;
}
