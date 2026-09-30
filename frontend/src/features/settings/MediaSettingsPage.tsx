import { Select } from "../../shared/ui/Select";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { type FormEvent, useId, useRef, useState } from "react";
import { ImageSquare, PlugsConnected, Plus, VideoCamera } from "@phosphor-icons/react";
import { Link, Navigate } from "react-router";
import {
  ApiError, createMediaCapability, createMediaConnection, getCurrentUser,
  getMediaSettings, setMediaDefault, updateMediaCapability,
  updateMediaConnection,
  type MediaCapability, type MediaConnection, type MediaSettings,
} from "../../shared/api/client";
import { PageShell } from "../../shared/ui/PageShell";
import { EmptyState, Notice, Panel, StatusBadge, SummaryStrip } from "../../shared/ui/PagePrimitives";
import { Dialog } from "../../shared/ui/Dialog";
import { LoadingState } from "../../shared/ui/LoadingState";
import "./MediaSettingsPage.css";
import { adapterLabel, adapterMetadata, adapterModel, platformAdapters } from "./mediaAdapterCatalog";
import { CapabilityConfigurationFields } from "./CapabilityConfigurationFields";
import { GoogleImageConnectionHelp, googleImageApiLabel } from "./GoogleImageConnectionHelp";
type AdapterSettings = MediaCapability["settings"];
const MODEL_LIMIT = 120;

const settingsKey = ["settings", "media"] as const;
const NAME_LIMIT = 160;
const ORIGIN_LIMIT = 500;
const HTTP_UNAUTHORIZED = 401;
const HTTP_FORBIDDEN = 403;
const HTTP_CONFLICT = 409;
const comfyImageFields = [{ key: "checkpoint", label: "图片 checkpoint 文件名" }] as const;
const comfyVideoFields = [
  { key: "diffusionModel", label: "视频扩散模型文件名" },
  { key: "textEncoder", label: "文本编码器文件名" },
  { key: "vae", label: "VAE 文件名" },
  { key: "clipVision", label: "CLIP Vision 文件名" },
] as const;

const cloudImageFields = [{ key: "model", label: "模型名（留空用内置默认）" }] as const;

function fixedModelFields(adapterId: string) {
  return adapterId === "COMFY_IMAGE_V1" ? comfyImageFields
    : adapterId === "COMFY_VIDEO_V1" ? comfyVideoFields
    : adapterId === "OPENAI_GPT_IMAGE_2" || adapterId === "GOOGLE_NANO_BANANA_2"
      ? cloudImageFields : [];
}

function fixedModelSettings(adapterId: string, values: AdapterSettings) {
  const fields = Object.fromEntries(fixedModelFields(adapterId).map(({ key }) =>
    [key, values[key as keyof AdapterSettings]?.toString().trim() ?? ""]));
  const { defaultParameters, defaultDurationSeconds, minimumSeconds, maximumSeconds,
    maxReferenceImages, pricing } = values;
  return { ...fields, ...(adapterId === "OPENAI_GPT_IMAGE_2" ? { quality: values.quality ?? "medium" } : {}),
    ...(defaultParameters ? { defaultParameters } : {}),
    ...(defaultDurationSeconds !== undefined ? { defaultDurationSeconds } : {}),
    ...(minimumSeconds !== undefined ? { minimumSeconds } : {}),
    ...(maximumSeconds !== undefined ? { maximumSeconds } : {}),
    ...(maxReferenceImages !== undefined ? { maxReferenceImages } : {}),
    ...(pricing?.amount.trim() ? { pricing } : {}) };

}

function QualityChoice({ value, onChange }: { value: string; onChange: (value: string) => void }) {
  return <label className="ui-field">GPT Image 2 质量
    <Select value={value} onChange={(event) => onChange(event.target.value)}>
      <option value="low">low</option><option value="medium">medium</option>
      <option value="high">high</option>
    </Select>
  </label>;
}

function FixedModelFields({ adapterId, values, onChange }: {
  adapterId: string;
  values: AdapterSettings;
  onChange: (value: AdapterSettings) => void;
}) {
  return <>
    {adapterModel(adapterId) && adapterId !== "ARK_SEEDANCE_2_I2V" ? <label className="ui-field">模型选项
      <Select value={values.model ? "custom" : "builtin"} onChange={(event) => onChange({ ...values,
        model: event.target.value === "builtin" ? "" : adapterModel(adapterId) })}>
        <option value="builtin">内置模型 · {adapterModel(adapterId)}</option>
        <option value="custom">自定义兼容模型</option>
      </Select>
    </label> : null}
    {adapterId === "ARK_SEEDANCE_2_I2V" ? <label className="ui-field">固定模型
      <input value={adapterModel(adapterId)} readOnly />
    </label> : null}
    {fixedModelFields(adapterId).map(({ key, label }) => <label key={key} className="ui-field">{label}
      <input value={String(values[key as keyof AdapterSettings] ?? "")} onChange={(event) => onChange({ ...values, [key]: event.target.value })}
        required={key !== "model"} maxLength={key === "model" ? MODEL_LIMIT : NAME_LIMIT}
        placeholder={key === "model" ? adapterModel(adapterId) ?? "留空使用内置默认" : "model.safetensors"} />
    </label>)}
    {adapterId === "GOOGLE_NANO_BANANA_2" ? <p className="ui-muted media-model-help">
      直连官方服务可使用内置模型；中转站请选择“自定义兼容模型”并填写服务商提供的模型名，例如 nano-banana-2-lite。
      Gemini v1 / v1beta 请求格式由连接的 API 地址决定，与模型名分开配置。
    </p> : null}
    {adapterId === "OPENAI_GPT_IMAGE_2" ? <QualityChoice value={values.quality ?? "medium"}
      onChange={(quality) => onChange({ ...values, quality: quality as AdapterSettings["quality"],
        ...(values.defaultParameters ? { defaultParameters: { ...values.defaultParameters, quality: quality as AdapterSettings["quality"] } } : {}) })} /> : null}
  </>;
}

const EDITOR_TABS = [
  { id: "model", label: "模型配置" }, { id: "defaults", label: "默认参数" },
  { id: "limits", label: "输入限制" }, { id: "pricing", label: "估算价格" },
] as const;
type EditorTab = typeof EDITOR_TABS[number]["id"];

function CapabilityEditorFields({ name, onNameChange, adapterId, onAdapterChange, availableAdapters,
  values, onChange, creating = false, disabled }: {
  name: string; onNameChange: (value: string) => void;
  adapterId: string; onAdapterChange: (value: string) => void; availableAdapters: string[];
  values: AdapterSettings; onChange: (value: AdapterSettings) => void;
  creating?: boolean; disabled: boolean;
}) {
  const [tab, setTab] = useState<EditorTab>("model");
  const id = useId();
  return <div className="media-editor ui-stack" onInvalidCapture={(event) => {
    // Reveal the invalid field before native form validation tries to focus it.
    const input = event.target;
    if (!(input instanceof HTMLElement)) return;
    const panel = input.closest<HTMLElement>("[data-editor-tab]");
    const section = panel?.dataset.editorTab;
    if (section && EDITOR_TABS.some((item) => item.id === section)) {
      if (panel?.hidden) event.preventDefault();
      setTab(section as EditorTab);
      requestAnimationFrame(() => input.focus());
    }
  }}>
    <div className="ui-tabs" role="tablist" aria-label="能力配置分区">
      {EDITOR_TABS.map((item, index) => <button type="button" role="tab" key={item.id}
        id={`${id}-${item.id}-tab`} aria-controls={`${id}-${item.id}-panel`}
        aria-selected={tab === item.id} tabIndex={tab === item.id ? 0 : -1}
        onClick={() => setTab(item.id)} onKeyDown={(event) => {
          if (!["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) return;
          event.preventDefault();
          const next = event.key === "Home" ? 0 : event.key === "End" ? EDITOR_TABS.length - 1
            : (index + (event.key === "ArrowRight" ? 1 : EDITOR_TABS.length - 1)) % EDITOR_TABS.length;
          const selected = EDITOR_TABS[next];
          if (selected) { setTab(selected.id); document.getElementById(`${id}-${selected.id}-tab`)?.focus(); }
        }}>{item.label}</button>)}
    </div>
    {EDITOR_TABS.map((item) => <div key={item.id} role="tabpanel" data-editor-tab={item.id}
      id={`${id}-${item.id}-panel`} aria-labelledby={`${id}-${item.id}-tab`} hidden={tab !== item.id}>
      <fieldset className="ui-form-grid media-settings-fieldset" disabled={disabled}>
        {item.id === "model" ? <>
          <label className="ui-field">{creating ? "新能力名称" : "能力名称"}
            <input value={name} onChange={(event) => onNameChange(event.target.value)} required maxLength={NAME_LIMIT} />
          </label>
          <label className="ui-field">固定适配器
            <Select value={adapterId} onChange={(event) => onAdapterChange(event.target.value)}>
              {availableAdapters.map((adapter) => <option key={adapter} value={adapter}>{adapterLabel(adapter)}</option>)}
            </Select>
          </label>
          <FixedModelFields adapterId={adapterId} values={values} onChange={onChange} />
        </> : <CapabilityConfigurationFields section={item.id} adapterId={adapterId} values={values} onChange={onChange} />}
      </fieldset>
    </div>)}
  </div>;
}

function ConnectionCredentials({ platform, origin, apiKey, onOriginChange, onApiKeyChange, creating = false }: {
  platform: MediaConnection["platform"];
  origin: string;
  apiKey: string;
  onOriginChange: (value: string) => void;
  onApiKeyChange: (value: string) => void;
  creating?: boolean;
}) {
  const helpId = useId();
  return <>
    {platform === "COMFYUI" ? <label className="ui-field">本机 ComfyUI 地址
      <input value={origin} onChange={(event) => onOriginChange(event.target.value)}
        required placeholder="http://127.0.0.1:8188" />
    </label> : null}
    {platform === "OPENAI" || platform === "GOOGLE" ? <label className="ui-field">API Base URL（留空使用官方地址）
      <input type="url" value={origin} onChange={(event) => onOriginChange(event.target.value)}
        aria-describedby={platform === "GOOGLE" ? helpId : undefined}
        maxLength={ORIGIN_LIMIT} placeholder={platform === "GOOGLE" ? "https://generativelanguage.googleapis.com" : "https://api.openai.com/v1"} />
    </label> : null}
    {platform === "GOOGLE" ? <GoogleImageConnectionHelp id={helpId} origin={origin} /> : null}
    {platform === "ARK" ? <label className="ui-field">固定 API 地址
      <input value="https://ark.cn-beijing.volces.com/api/v3" readOnly />
    </label> : null}
    {platform === "OPENAI" || platform === "ARK" || platform === "GOOGLE" ? <label className="ui-field">
      {creating ? "API Key" : "替换 API Key（留空则不修改）"}
      <input type="password" autoComplete="new-password" value={apiKey}
        onChange={(event) => onApiKeyChange(event.target.value)} required={creating} />
    </label> : null}
  </>;
}

function stableCreateKey(previous: { payload: string; key: string } | null,
  payload: string): { payload: string; key: string } {
  return previous?.payload === payload ? previous
    : { payload, key: crypto.randomUUID() };
}

function errorMessage(cause: unknown): string {
  return cause instanceof ApiError ? cause.message : "保存失败，请重试。";
}

/** Keep a draft's CAS token until its own save or an explicit reload accepts a new baseline. */
function useConfigurationBaseline<T extends { version: number }>(current: T) {
  const [baseline, acceptBaseline] = useState(current);
  return { baseline, acceptBaseline, isStale: current.version > baseline.version };
}

function ConfigurationUpdatedNotice({ scope, disabled, onReload }: {
  scope: string; disabled: boolean; onReload: () => void;
}) {
  return <Notice tone="warning" title="配置已有新版本">
    <p>当前草稿已保留。载入最新版本将替换{scope}的未保存内容。</p>
    <button className="secondary-button" type="button" disabled={disabled} onClick={onReload}>载入最新版本</button>
  </Notice>;
}

function CapabilityRow({ connectionId, connectionName, capability, isDefault, connectionEnabled,
  availableAdapters, busy, apply, act }: {
  connectionId: string;
  connectionName: string;
  capability: MediaCapability;
  isDefault: boolean;
  connectionEnabled: boolean;
  availableAdapters: string[];
  busy: boolean;
  apply: (value: MediaSettings) => void;
  act: (type: "capability" | "default", capability: MediaCapability) => void;
}) {
  const queryClient = useQueryClient();
  const { baseline, acceptBaseline, isStale } = useConfigurationBaseline(capability);
  const [name, setName] = useState(capability.name);
  const [adapterId, setAdapterId] = useState(capability.adapterId);
  const [modelNames, setModelNames] = useState<AdapterSettings>(capability.settings);
  const [error, setError] = useState("");
  const [editing, setEditing] = useState(false);
  const save = useMutation({
    mutationFn: () => updateMediaCapability(connectionId, capability.id, {
      expectedVersion: baseline.version, name: name.trim(),
      enabled: baseline.enabled, adapterId,
      settings: fixedModelSettings(adapterId, modelNames),
    }),
    onSuccess: (result) => {
      const saved = result.connections.find((item) => item.id === connectionId)
        ?.capabilities.find((item) => item.id === capability.id);
      if (saved) {
        acceptBaseline(saved);
        setName(saved.name);
        setAdapterId(saved.adapterId);
        setModelNames(saved.settings);
      }
      apply(result); setError(""); setEditing(false);
    },
    onError: (cause) => {
      if (cause instanceof ApiError && cause.status === HTTP_CONFLICT) {
        void queryClient.invalidateQueries({ queryKey: settingsKey });
      }
      setError(errorMessage(cause));
    },
  });
  const sameKindAdapters = availableAdapters.filter((id) => adapterMetadata(id)?.kind === capability.kind);
  const rowBusy = busy || save.isPending;
  function loadLatest() {
    acceptBaseline(capability);
    setName(capability.name);
    setAdapterId(capability.adapterId);
    setModelNames(capability.settings);
    setError("");
  }
  const CapabilityIcon = capability.kind === "IMAGE_GENERATION" ? ImageSquare : VideoCamera;
  return <>
    <tr>
      <td><div className="media-table-name"><CapabilityIcon size={19} /><div>
        <strong>{capability.name}</strong><small>{connectionName}</small>
      </div></div></td>
      <td><strong>{adapterLabel(capability.adapterId)}</strong>
        <small>{capability.settings.model || adapterModel(capability.adapterId) || capability.adapterId}</small></td>
      <td><strong>{capability.kind === "VIDEO_GENERATION" ? `${capability.minimumSeconds}–${capability.maximumSeconds} 秒` : "图片"}</strong>
        <small>最多 {capability.maxReferenceImages} 张参考图</small></td>
      <td>{capability.settings.pricing ? <>{capability.settings.pricing.amount} {capability.settings.pricing.currency}
        <small>每{capability.settings.pricing.unit === "SECOND" ? "秒" : capability.settings.pricing.unit === "VIDEO" ? "个视频" : "张图片"} · 估算</small></> : <span className="ui-muted">未设置</span>}</td>
      <td><div className="media-table-badges">
        <StatusBadge tone={capability.enabled && connectionEnabled ? "success" : "warning"}>{capability.enabled && connectionEnabled ? "已启用" : "已停用"}</StatusBadge>
        {isDefault ? <StatusBadge>默认</StatusBadge> : null}
      </div></td>
      <td><div className="media-table-actions">
        <button className="secondary-button" type="button" disabled={rowBusy} onClick={() => setEditing(true)}>编辑能力参数</button>
        <button className="ghost-button" type="button" disabled={rowBusy || !connectionEnabled || !capability.enabled || isDefault}
          onClick={() => act("default", capability)}>设为默认</button>
        <button className="ghost-button" type="button" disabled={rowBusy}
          onClick={() => act("capability", capability)}>{capability.enabled ? "停用" : "启用"}</button>
      </div></td>
    </tr>
    {editing ? <Dialog title={`编辑能力 · ${capability.name}`} description={`${connectionName} · ${adapterLabel(adapterId)}`}
      onClose={() => setEditing(false)} busy={rowBusy} onSubmit={(event) => {
        event.preventDefault(); if (isStale || rowBusy) return; setError(""); save.mutate();
      }} footer={<>
        <button className="secondary-button" type="button" disabled={rowBusy} onClick={() => setEditing(false)}>取消</button>
        <button className="primary-button" type="submit" disabled={rowBusy || isStale}>{save.isPending ? "正在保存…" : "保存能力"}</button>
      </>}>
      <div className="ui-stack">
        {isStale ? <ConfigurationUpdatedNotice scope="能力参数" disabled={rowBusy} onReload={loadLatest} /> : null}
        <CapabilityEditorFields name={name} onNameChange={setName} adapterId={adapterId}
          onAdapterChange={(value) => { setAdapterId(value); setModelNames({}); }} availableAdapters={sameKindAdapters}
          values={modelNames} onChange={setModelNames} disabled={rowBusy} />
        {error ? <Notice tone="danger">{error}</Notice> : null}
      </div>
    </Dialog> : null}
  </>;
}

function useMediaActions(connection: MediaConnection, settings: MediaSettings, apply: (value: MediaSettings) => void) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (action: { type: "connection" | "capability" | "default"; capability?: MediaCapability }) => {
      if (action.type === "connection") {
        return updateMediaConnection(connection.id, {
          expectedVersion: connection.version, name: connection.name,
          enabled: !connection.enabled, origin: connection.origin, apiKey: null,
        });
      }
      const capability = action.capability;
      if (!capability) throw new Error("请选择能力");
      if (action.type === "capability") {
        return updateMediaCapability(connection.id, capability.id, {
          expectedVersion: capability.version, name: capability.name,
          enabled: !capability.enabled, adapterId: capability.adapterId,
          settings: capability.settings,
        });
      }
      const current = settings.defaults.find((item) => item.kind === capability.kind);
      if (!current) throw new Error("找不到默认能力版本");
      return setMediaDefault(capability.kind, {
        expectedVersion: current.version, capabilityId: capability.id,
      });
    },
    onSuccess: apply,
    onError: (cause) => {
      if (cause instanceof ApiError && cause.status === HTTP_CONFLICT) {
        void queryClient.invalidateQueries({ queryKey: settingsKey });
      }
    },
  });
}

function ConnectionCapabilities({ connection, settings, apply }: {
  connection: MediaConnection; settings: MediaSettings; apply: (value: MediaSettings) => void;
}) {
  const mutate = useMediaActions(connection, settings, apply);
  const systemManaged = connection.platform === "LOCAL";
  const availableAdapters = platformAdapters[connection.platform];
  return <>
    {connection.capabilities.map((capability) => systemManaged ? <tr key={capability.id}>
      <td><strong>{capability.name}</strong><small>{connection.name}</small></td>
      <td><strong>{adapterLabel(capability.adapterId)}</strong><small>应用内置</small></td>
      <td>图片后处理<small>最多 {capability.maxReferenceImages} 张参考图</small></td>
      <td>本地执行</td><td><StatusBadge tone="success">已启用</StatusBadge></td><td className="ui-muted">系统管理</td>
    </tr> : <CapabilityRow key={capability.id} connectionId={connection.id} connectionName={connection.name}
      capability={capability} isDefault={settings.defaults.some((item) => item.kind === capability.kind && item.capabilityId === capability.id)}
      connectionEnabled={connection.enabled} availableAdapters={availableAdapters} busy={mutate.isPending} apply={apply}
      act={(type, selected) => mutate.mutate({ type, capability: selected })} />)}
    {mutate.isError ? <tr><td colSpan={6}><Notice tone="danger">{errorMessage(mutate.error)}</Notice></td></tr> : null}
  </>;

}

function ConnectionRow({ connection, settings, apply }: {
  connection: MediaConnection;
  settings: MediaSettings;
  apply: (value: MediaSettings) => void;
}) {
  const queryClient = useQueryClient();
  const { baseline, acceptBaseline, isStale } = useConfigurationBaseline(connection);
  const [name, setName] = useState(connection.name);
  const [origin, setOrigin] = useState(connection.origin ?? "");
  const [apiKey, setApiKey] = useState("");
  const [capabilityName, setCapabilityName] = useState("");
  const [adapterId, setAdapterId] = useState(platformAdapters[connection.platform][0] ?? "");
  const [newModelNames, setNewModelNames] = useState<AdapterSettings>({});
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [dialog, setDialog] = useState<"connection" | "capability" | null>(null);
  const capabilityCreateKey = useRef<{ payload: string; key: string } | null>(null);
  const save = useMutation({
    mutationFn: () => updateMediaConnection(connection.id, {
      expectedVersion: baseline.version, name: name.trim(), enabled: baseline.enabled,
      origin: connection.platform === "COMFYUI" || connection.platform === "OPENAI" || connection.platform === "GOOGLE"
        ? origin.trim() || null : null,
      apiKey: apiKey || null,
    }),
    onSuccess: (result) => {
      const saved = result.connections.find((item) => item.id === connection.id);
      if (saved) {
        acceptBaseline(saved);
        setName(saved.name);
        setOrigin(saved.origin ?? "");
      }
      apply(result);
      setApiKey("");
      setError("");
      setNotice("连接已保存。");
      setDialog(null);
    },
    onError: (cause) => {
      setNotice("");
      if (cause instanceof ApiError && cause.status === HTTP_CONFLICT) {
        void queryClient.invalidateQueries({ queryKey: settingsKey });
      }
      setError(cause instanceof ApiError && cause.status === HTTP_CONFLICT
        ? "配置已被其他管理员修改，已请求刷新；当前草稿已保留。"
        : errorMessage(cause));
    },
  });
  const addCapability = useMutation({
    mutationFn: () => {
      const payload = { name: capabilityName.trim(), adapterId,
        settings: fixedModelSettings(adapterId, newModelNames) };
      capabilityCreateKey.current = stableCreateKey(capabilityCreateKey.current, JSON.stringify(payload));
      return createMediaCapability(connection.id, payload, capabilityCreateKey.current.key);
    },
    onSuccess: (result) => {
      capabilityCreateKey.current = null;
      apply(result);
      setCapabilityName("");
      setNewModelNames({});
      setError("");
      setNotice("能力已发布。");
      setDialog(null);
    },
    onError: (cause) => {
      if (cause instanceof ApiError && cause.status === HTTP_CONFLICT) {
        void queryClient.invalidateQueries({ queryKey: settingsKey });
      }
      setError(errorMessage(cause));
    },
  });
  const mutate = useMediaActions(connection, settings, apply);
  const busy = save.isPending || addCapability.isPending || mutate.isPending;
  const systemManaged = connection.platform === "LOCAL";
  const availableAdapters = systemManaged ? [] : platformAdapters[connection.platform];

  function loadLatest() {
    acceptBaseline(connection);
    setName(connection.name);
    setOrigin(connection.origin ?? "");
    setApiKey("");
    setError("");
    setNotice("");
  }

  function submitConnection(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (isStale || busy) return;
    setError("");
    save.mutate();
  }


  return <>
    <tr>
      <td><strong>{connection.name}</strong><small>{connection.platform} · {connection.capabilities.length} 个能力</small></td>
      <td><span className="media-table-endpoint">{connection.origin || (connection.platform === "ARK" ? "https://ark.cn-beijing.volces.com/api/v3" : systemManaged ? "应用内置" : "平台默认地址")}</span>
        <small>{connection.keyMask ? `密钥 ${connection.keyMask} · ` : ""}{systemManaged ? "本地图片后处理" : "已配置"}</small>
        {connection.platform === "GOOGLE" ? <small>{googleImageApiLabel(connection.origin)}</small> : null}</td>
      <td><StatusBadge tone={connection.enabled ? "success" : "warning"}>{connection.enabled ? "已启用" : "已停用"}</StatusBadge></td>
      <td>{systemManaged ? <span className="ui-muted">系统管理</span> : <div className="media-table-actions">
        <button className="secondary-button" type="button" disabled={busy} onClick={() => setDialog("connection")}>编辑连接</button>
        <button className="secondary-button" type="button" disabled={busy || !connection.enabled || !availableAdapters.length}
          onClick={() => setDialog("capability")}>发布能力</button>
        <button className="ghost-button" type="button" disabled={busy}
          onClick={() => mutate.mutate({ type: "connection" })}>{connection.enabled ? "停用连接" : "启用连接"}</button>
      </div>}</td>
    </tr>
    {mutate.isError || (error && !dialog) || notice ? <tr><td colSpan={4}>{mutate.isError || (error && !dialog) ? <Notice tone="danger">{mutate.isError ? errorMessage(mutate.error) : error}</Notice> : <Notice tone="success">{notice}</Notice>}</td></tr> : null}
    {dialog === "connection" ? <Dialog title={`编辑连接 · ${connection.name}`} description={`${connection.platform} · API 地址与凭证`}
      onClose={() => setDialog(null)} onSubmit={submitConnection} busy={busy} footer={<>
        <button className="secondary-button" type="button" disabled={busy} onClick={() => setDialog(null)}>取消</button>
        <button className="primary-button" type="submit" disabled={busy || isStale}>{save.isPending ? "正在保存…" : "保存连接"}</button>
      </>}>
      <div className="ui-stack">
        {isStale ? <ConfigurationUpdatedNotice scope="连接配置" disabled={busy} onReload={loadLatest} /> : null}
        <fieldset className="ui-form-grid media-settings-fieldset" disabled={busy}>
          <label className="ui-field">连接名称<input value={name} onChange={(event) => setName(event.target.value)} required maxLength={NAME_LIMIT} /></label>
          <ConnectionCredentials platform={connection.platform} origin={origin} apiKey={apiKey} onOriginChange={setOrigin} onApiKeyChange={setApiKey} />
        </fieldset>
        {error ? <Notice tone="danger">{error}</Notice> : null}
      </div>
    </Dialog> : null}
    {dialog === "capability" ? <Dialog title="发布新能力" description={`${connection.name} · ${adapterLabel(adapterId)}`}
      onClose={() => setDialog(null)} busy={busy} onSubmit={(event) => {
        event.preventDefault(); if (busy || !connection.enabled) return; setError(""); addCapability.mutate();
      }} footer={<>
        <button className="secondary-button" type="button" disabled={busy} onClick={() => setDialog(null)}>取消</button>
        <button className="primary-button" type="submit" disabled={busy || !connection.enabled}>{addCapability.isPending ? "正在发布…" : "发布能力"}</button>
      </>}>
      <div className="ui-stack">
        <CapabilityEditorFields name={capabilityName} onNameChange={setCapabilityName} adapterId={adapterId}
          onAdapterChange={(value) => { setAdapterId(value); setNewModelNames({}); }} availableAdapters={availableAdapters}
          values={newModelNames} onChange={setNewModelNames} creating disabled={busy || !connection.enabled} />
        {!connection.enabled ? <Notice tone="warning">启用连接后可发布新能力。</Notice> : null}
        {error ? <Notice tone="danger">{error}</Notice> : null}
      </div>
    </Dialog> : null}
  </>;
}

export function MediaSettingsPage() {
  const queryClient = useQueryClient();
  const currentUser = useQuery({ queryKey: ["auth", "me"], queryFn: getCurrentUser, retry: false });
  const settings = useQuery({ queryKey: settingsKey, queryFn: getMediaSettings,
    enabled: currentUser.isSuccess, retry: false });
  const [name, setName] = useState("");
  const [platform, setPlatform] = useState<"COMFYUI" | "OPENAI" | "ARK" | "GOOGLE">("COMFYUI");
  const [origin, setOrigin] = useState("");
  const [apiKey, setApiKey] = useState("");
  const [error, setError] = useState("");
  const [creating, setCreating] = useState(false);
  const connectionCreateKey = useRef<{ payload: string; key: string } | null>(null);
  const create = useMutation({
    mutationFn: () => {
      const payload = { name: name.trim(), platform,
        origin: platform === "COMFYUI" || platform === "OPENAI" || platform === "GOOGLE"
          ? origin.trim() || null : null,
        apiKey: platform === "COMFYUI" ? null : apiKey };
      connectionCreateKey.current = stableCreateKey(connectionCreateKey.current, JSON.stringify(payload));
      return createMediaConnection(payload, connectionCreateKey.current.key);
    },
    onSuccess: (result) => {
      connectionCreateKey.current = null;
      queryClient.setQueryData(settingsKey, result);
      setName(""); setOrigin(""); setApiKey(""); setError(""); setCreating(false);
    },
    onError: (cause) => setError(errorMessage(cause)),
  });

  if (settings.error instanceof ApiError && settings.error.status === HTTP_UNAUTHORIZED) {
    return <Navigate to="/login" replace />;
  }
  return <PageShell title="媒体连接与能力"
    description="管理图片与视频模型，让创作使用合适的生成能力。"
    actions={<Link className="secondary-button" to="/settings/llm">LLM 设置</Link>}>
    <div className="media-settings-page ui-stack">
      {settings.data ? <SummaryStrip items={[
        { label: "媒体连接", value: `${settings.data.connections.length} 个`, detail: "本机服务与云端平台" },
        { label: "可用能力", value: `${settings.data.connections.filter((connection) => connection.enabled)
          .flatMap((connection) => connection.capabilities).filter((capability) => capability.enabled).length} 个`,
          detail: "已启用连接中的已启用能力" },
        { label: "默认能力", value: `${settings.data.defaults.filter((item) => item.capabilityId).length} 项`, detail: "新请求使用当前默认值" },
      ]} /> : null}
      <Notice title="配置与生成彼此独立">
        默认配置补充未填写的草稿参数，已受理任务保留固定输入。保存配置不会调用付费生成接口，实际生成结果见调用日志。
      </Notice>
      {currentUser.isSuccess && settings.isPending ? <LoadingState label="正在读取媒体配置…" /> : null}
      {settings.error instanceof ApiError && settings.error.status === HTTP_FORBIDDEN
        ? <Notice tone="warning" title="需要管理员权限">只有管理员可以查看媒体配置。</Notice> : null}
      {settings.isError && !(settings.error instanceof ApiError && [HTTP_UNAUTHORIZED, HTTP_FORBIDDEN].includes(settings.error.status))
        ? <Notice tone="danger" title="读取失败">
          <p>读取失败，请刷新后重试。</p>
          <button className="secondary-button" type="button" onClick={() => { void settings.refetch(); }}
            disabled={settings.isFetching}>{settings.isFetching ? "正在重试…" : "重新读取"}</button>
        </Notice> : null}
      {settings.data ? <>
        <Panel title="媒体连接" description="配置平台地址与凭证，然后为连接发布能力。"
          actions={<button className="primary-button" type="button" onClick={() => setCreating(true)}><Plus size={15} />添加连接</button>}>
          {settings.data.connections.length === 0 ? <EmptyState icon={<PlugsConnected size={30} />} title="尚无媒体连接"
            description="添加一个连接，再发布图片或视频能力。" /> : <div className="media-table-scroll" tabIndex={0} role="region" aria-label="媒体连接表格">
            <table className="media-settings-table" aria-label="媒体连接"><thead><tr>
              <th scope="col">连接 / 平台</th><th scope="col">地址 / 凭证</th><th scope="col">状态</th><th scope="col">操作</th>
            </tr></thead><tbody>{settings.data.connections.map((connection) => <ConnectionRow key={connection.id}
              connection={connection} settings={settings.data} apply={(result) => queryClient.setQueryData(settingsKey, result)} />)}</tbody></table>
          </div>}
        </Panel>
        <Panel title="已发布能力" description="查看模型、输入范围和估算价格，直接切换启用状态与默认能力。">
          {settings.data.connections.every((connection) => connection.capabilities.length === 0) ? <p className="ui-muted">尚无能力，请在连接表中发布。</p>
            : <div className="media-table-scroll" tabIndex={0} role="region" aria-label="生成能力表格">
              <table className="media-settings-table" aria-label="已发布能力"><thead><tr>
                <th scope="col">能力 / 连接</th><th scope="col">模型</th><th scope="col">输入范围</th><th scope="col">价格</th><th scope="col">状态</th><th scope="col">操作</th>
              </tr></thead><tbody>{settings.data.connections.map((connection) => <ConnectionCapabilities key={connection.id}
                connection={connection} settings={settings.data} apply={(result) => queryClient.setQueryData(settingsKey, result)} />)}</tbody></table>
            </div>}
        </Panel>
        {creating ? <Dialog title="添加连接" description="连接本机 ComfyUI 或云端生成平台。" busy={create.isPending}
          onClose={() => setCreating(false)} onSubmit={(event) => { event.preventDefault(); if (create.isPending) return; setError(""); create.mutate(); }}
          footer={<>
            <button className="secondary-button" type="button" disabled={create.isPending} onClick={() => setCreating(false)}>取消</button>
            <button className="primary-button" type="submit" disabled={create.isPending}>{create.isPending ? "正在保存…" : "添加连接"}</button>
          </>}>
          <div className="ui-stack">
            <fieldset className="ui-form-grid media-settings-fieldset" disabled={create.isPending}>
              <label className="ui-field">连接名称
                <input value={name} onChange={(event) => setName(event.target.value)} required maxLength={NAME_LIMIT} />
              </label>
              <label className="ui-field">平台
                <Select value={platform} onChange={(event) => { setPlatform(event.target.value as typeof platform); setOrigin(""); setApiKey(""); }}>
                  <option value="COMFYUI">ComfyUI</option><option value="OPENAI">OpenAI</option>
                  <option value="GOOGLE">Google Gemini · Nano Banana 2</option><option value="ARK">火山方舟</option>
                </Select>
              </label>
              <ConnectionCredentials platform={platform} origin={origin} apiKey={apiKey}
                onOriginChange={setOrigin} onApiKeyChange={setApiKey} creating />
            </fieldset>
            {error ? <Notice tone="danger">{error}</Notice> : null}
          </div>
        </Dialog> : null}
      </> : null}
    </div>
  </PageShell>;
}
