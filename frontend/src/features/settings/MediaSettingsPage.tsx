import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { type FormEvent, useRef, useState } from "react";
import { ImageSquare, PlugsConnected, Plus, SlidersHorizontal, VideoCamera } from "@phosphor-icons/react";
import { Link, Navigate } from "react-router";
import {
  ApiError, createMediaCapability, createMediaConnection, getCurrentUser,
  getMediaSettings, setMediaDefault, updateMediaCapability,
  updateMediaConnection,
  type MediaCapability, type MediaConnection, type MediaSettings,
} from "../../shared/api/client";
import { PageShell } from "../../shared/ui/PageShell";
import { EmptyState, Notice, Panel, StatusBadge } from "../../shared/ui/PagePrimitives";
import { LoadingState } from "../../shared/ui/LoadingState";
import "./MediaSettingsPage.css";

const settingsKey = ["settings", "media"] as const;
const NAME_LIMIT = 160;
const ORIGIN_LIMIT = 500;
const HTTP_UNAUTHORIZED = 401;
const HTTP_FORBIDDEN = 403;
const HTTP_CONFLICT = 409;
const platformAdapters: Record<MediaConnection["platform"], string[]> = {
  MOCK: ["MOCK_IMAGE", "MOCK_VIDEO"],
  COMFYUI: ["COMFY_IMAGE_V1", "COMFY_VIDEO_V1"],
  OPENAI: ["OPENAI_GPT_IMAGE_2"],
  GOOGLE: ["GOOGLE_NANO_BANANA_2"],
  ARK: ["ARK_SEEDANCE_2_I2V"],
};
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

function fixedModelSettings(adapterId: string, values: Record<string, string>) {
  const fields = Object.fromEntries(fixedModelFields(adapterId).map(({ key }) =>
    [key, values[key]?.trim() ?? ""]));
  return adapterId === "OPENAI_GPT_IMAGE_2"
    ? { ...fields, quality: values.quality ?? "medium" }
    : fields;
}

function QualityChoice({ value, onChange }: { value: string; onChange: (value: string) => void }) {
  return <label className="ui-field">GPT Image 2 质量
    <select value={value} onChange={(event) => onChange(event.target.value)}>
      <option value="low">low</option><option value="medium">medium</option>
      <option value="high">high</option>
    </select>
  </label>;
}

function FixedModelFields({ adapterId, values, onChange }: {
  adapterId: string;
  values: Record<string, string>;
  onChange: (value: Record<string, string>) => void;
}) {
  return <>
    {fixedModelFields(adapterId).map(({ key, label }) => <label key={key} className="ui-field">{label}
      <input value={values[key] ?? ""} onChange={(event) => onChange({ ...values, [key]: event.target.value })}
        required={key !== "model"} maxLength={NAME_LIMIT}
        placeholder={key === "model" ? "留空使用内置默认" : "model.safetensors"} />
    </label>)}
    {adapterId === "OPENAI_GPT_IMAGE_2" ? <QualityChoice value={values.quality ?? "medium"}
      onChange={(quality) => onChange({ ...values, quality })} /> : null}
  </>;
}

function ConnectionCredentials({ platform, origin, apiKey, onOriginChange, onApiKeyChange, creating = false }: {
  platform: MediaConnection["platform"];
  origin: string;
  apiKey: string;
  onOriginChange: (value: string) => void;
  onApiKeyChange: (value: string) => void;
  creating?: boolean;
}) {
  return <>
    {platform === "COMFYUI" ? <label className="ui-field">本机 ComfyUI 地址
      <input value={origin} onChange={(event) => onOriginChange(event.target.value)}
        required placeholder="http://127.0.0.1:8188" />
    </label> : null}
    {platform === "OPENAI" ? <label className="ui-field">API Base URL（留空使用官方地址）
      <input type="url" value={origin} onChange={(event) => onOriginChange(event.target.value)}
        maxLength={ORIGIN_LIMIT} placeholder="https://api.openai.com/v1" />
    </label> : null}
    {platform === "OPENAI" || platform === "ARK" || platform === "GOOGLE" ? <label className="ui-field">
      {creating ? "API Key" : "替换 API Key（留空则不修改）"}
      <input type="password" autoComplete="new-password" value={apiKey}
        onChange={(event) => onApiKeyChange(event.target.value)} required={creating} />
    </label> : null}
  </>;
}

const adapterKind: Record<string, "IMAGE_GENERATION" | "VIDEO_GENERATION"> = {
  MOCK_IMAGE: "IMAGE_GENERATION", MOCK_VIDEO: "VIDEO_GENERATION",
  COMFY_IMAGE_V1: "IMAGE_GENERATION", COMFY_VIDEO_V1: "VIDEO_GENERATION",
  OPENAI_GPT_IMAGE_2: "IMAGE_GENERATION", GOOGLE_NANO_BANANA_2: "IMAGE_GENERATION",
  ARK_SEEDANCE_2_I2V: "VIDEO_GENERATION",
};

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

function CapabilityRow({ connectionId, capability, isDefault, connectionEnabled,
  availableAdapters, busy, apply, act }: {
  connectionId: string;
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
  const [modelNames, setModelNames] = useState<Record<string, string>>(capability.settings);
  const [error, setError] = useState("");
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
      apply(result); setError("");
    },
    onError: (cause) => {
      if (cause instanceof ApiError && cause.status === HTTP_CONFLICT) {
        void queryClient.invalidateQueries({ queryKey: settingsKey });
      }
      setError(errorMessage(cause));
    },
  });
  const sameKindAdapters = availableAdapters.filter((id) => adapterKind[id] === capability.kind);
  const rowBusy = busy || save.isPending;
  function loadLatest() {
    acceptBaseline(capability);
    setName(capability.name);
    setAdapterId(capability.adapterId);
    setModelNames(capability.settings);
    setError("");
  }
  const CapabilityIcon = capability.kind === "IMAGE_GENERATION" ? ImageSquare : VideoCamera;
  return <li className="media-capability-row">
    <div className="media-capability-heading">
      <span className="media-settings-icon"><CapabilityIcon size={20} /></span>
      <div className="media-capability-title">
        <div className="ui-toolbar">
          <h4>{capability.name}</h4>
          {isDefault ? <StatusBadge tone="success">默认</StatusBadge> : null}
          <StatusBadge tone={capability.enabled ? "neutral" : "warning"}>
            {capability.enabled ? "已启用" : "已停用"}
          </StatusBadge>
        </div>
        <p className="ui-muted">{capability.kind === "IMAGE_GENERATION" ? "图片" : "视频"} · {capability.adapterId} · v{capability.capabilityVersion}</p>
        <p className="ui-muted">{capability.kind === "VIDEO_GENERATION" ? `${capability.minimumSeconds}–${capability.maximumSeconds} 秒` : "按执行器可用容量调度"}
          {capability.settings.quality ? ` · ${capability.settings.quality}` : ""}
        </p>
      </div>
      <div className="ui-toolbar media-capability-actions">
        <button className="secondary-button" type="button" disabled={rowBusy || !connectionEnabled || !capability.enabled || isDefault}
          onClick={() => act("default", capability)}>设为默认</button>
        <button className="ghost-button" type="button" disabled={rowBusy}
          onClick={() => act("capability", capability)}>{capability.enabled ? "停用" : "启用"}</button>
      </div>
    </div>
    <details className="media-settings-details">
      <summary><SlidersHorizontal size={15} />编辑能力参数</summary>
      <div className="media-settings-details-body ui-stack">
        <form className="ui-form" onSubmit={(event) => {
          event.preventDefault();
          if (isStale || rowBusy) return;
          setError(""); save.mutate();
        }}>
          <fieldset className="ui-form-grid media-settings-fieldset" disabled={rowBusy}>
            <label className="ui-field">能力名称
              <input value={name} onChange={(event) => setName(event.target.value)} required maxLength={NAME_LIMIT} />
            </label>
            <label className="ui-field">固定适配器
              <select value={adapterId} onChange={(event) => setAdapterId(event.target.value)}>
                {sameKindAdapters.map((id) => <option key={id} value={id}>{id}</option>)}
              </select>
            </label>
            <FixedModelFields adapterId={adapterId} values={modelNames} onChange={setModelNames} />
          </fieldset>
          <div className="ui-form-actions">
            <button className="secondary-button" type="submit" disabled={rowBusy || isStale}>
              {save.isPending ? "正在保存…" : "保存能力"}
            </button>
          </div>
        </form>
      </div>
    </details>
    {isStale ? <ConfigurationUpdatedNotice scope="能力参数" disabled={rowBusy} onReload={loadLatest} /> : null}
    {error ? <Notice tone="danger">{error}</Notice> : null}
  </li>;
}

function ConnectionCard({ connection, settings, apply }: {
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
  const [newModelNames, setNewModelNames] = useState<Record<string, string>>({});
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const capabilityCreateKey = useRef<{ payload: string; key: string } | null>(null);
  const save = useMutation({
    mutationFn: () => updateMediaConnection(connection.id, {
      expectedVersion: baseline.version, name: name.trim(), enabled: baseline.enabled,
      origin: connection.platform === "COMFYUI" || connection.platform === "OPENAI"
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
      setNotice("连接已保存，生成接口未实测。");
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
      setNotice("能力已发布，生成接口未实测。");
    },
    onError: (cause) => {
      if (cause instanceof ApiError && cause.status === HTTP_CONFLICT) {
        void queryClient.invalidateQueries({ queryKey: settingsKey });
      }
      setError(errorMessage(cause));
    },
  });
  const mutate = useMutation({
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
    onSuccess: (result) => {
      apply(result);
      setError("");
      setNotice("配置已保存，生成接口未实测。");
    },
    onError: (cause) => {
      if (cause instanceof ApiError && cause.status === HTTP_CONFLICT) {
        void queryClient.invalidateQueries({ queryKey: settingsKey });
      }
      setError(errorMessage(cause));
    },
  });
  const busy = save.isPending || addCapability.isPending || mutate.isPending;
  const availableAdapters = platformAdapters[connection.platform];

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

  return <Panel className="media-connection-card"
    title={connection.name}
    description={<span>{connection.platform} · 连接版本 {connection.connectionVersion}</span>}
    actions={<div className="ui-toolbar">
      <StatusBadge tone={connection.enabled ? "success" : "warning"}>{connection.enabled ? "已启用" : "已停用"}</StatusBadge>
      <button className="ghost-button" type="button" disabled={busy}
        onClick={() => mutate.mutate({ type: "connection" })}>{connection.enabled ? "停用连接" : "启用连接"}</button>
    </div>}>
    <p className="media-connection-status ui-muted">{connection.keyMask ? `密钥 ${connection.keyMask} · ` : ""}已配置、未实测</p>
    <form className="ui-form" onSubmit={submitConnection}>
      <fieldset className="ui-form-grid media-settings-fieldset" disabled={busy}>
        <label className="ui-field">连接名称
          <input value={name} onChange={(event) => setName(event.target.value)} required maxLength={NAME_LIMIT} />
        </label>
        <ConnectionCredentials platform={connection.platform} origin={origin} apiKey={apiKey}
          onOriginChange={setOrigin} onApiKeyChange={setApiKey} />
      </fieldset>
      <div className="ui-form-actions">
        <button className="secondary-button" type="submit" disabled={busy || isStale}>{save.isPending ? "正在保存…" : "保存连接"}</button>
      </div>
    </form>
    {isStale ? <ConfigurationUpdatedNotice scope="连接配置" disabled={busy} onReload={loadLatest} /> : null}
    <section className="media-capabilities" aria-label={`${connection.name} 的能力`}>
      <div className="media-section-heading">
        <h3>已发布能力</h3><StatusBadge>{connection.capabilities.length}</StatusBadge>
      </div>
      {connection.capabilities.length === 0 ? <p className="media-capability-empty ui-muted">尚无能力，发布后即可在画布中选择。</p> : null}
      <ul className="media-capabilities-list">
        {connection.capabilities.map((capability) => {
          const isDefault = settings.defaults.some((item) => item.kind === capability.kind && item.capabilityId === capability.id);
          return <CapabilityRow key={capability.id} connectionId={connection.id}
            capability={capability} isDefault={isDefault}
            connectionEnabled={connection.enabled} availableAdapters={availableAdapters}
            busy={busy} apply={apply}
            act={(type, selected) => mutate.mutate({ type, capability: selected })} />;
        })}
      </ul>
      {availableAdapters.length > 0 ? <form className="ui-form media-publish-form" onSubmit={(event) => {
        event.preventDefault();
        setError("");
        addCapability.mutate();
      }}>
        <h4>发布新能力</h4>
        <fieldset className="ui-form-grid media-settings-fieldset" disabled={busy || !connection.enabled}>
          <label className="ui-field">新能力名称
            <input value={capabilityName} onChange={(event) => setCapabilityName(event.target.value)} required maxLength={NAME_LIMIT} />
          </label>
          <label className="ui-field">固定适配器
            <select value={adapterId} onChange={(event) => setAdapterId(event.target.value)}>
              {availableAdapters.map((id) => <option key={id} value={id}>{id}</option>)}
            </select>
          </label>
          <FixedModelFields adapterId={adapterId} values={newModelNames} onChange={setNewModelNames} />
        </fieldset>
        <div className="ui-form-actions">
          <button className="secondary-button" type="submit" disabled={busy || !connection.enabled}>
            <Plus size={15} />{addCapability.isPending ? "正在发布…" : "发布能力"}
          </button>
          {!connection.enabled ? <p className="ui-muted">启用连接后可发布新能力。</p> : null}
        </div>
      </form> : <p className="ui-muted">此平台的固定适配器尚未安装。</p>}
    </section>
    {error ? <Notice tone="danger">{error}</Notice> : null}
    {notice ? <Notice tone="success">{notice}</Notice> : null}
  </Panel>;
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
  const connectionCreateKey = useRef<{ payload: string; key: string } | null>(null);
  const create = useMutation({
    mutationFn: () => {
      const payload = { name: name.trim(), platform,
        origin: platform === "COMFYUI" || platform === "OPENAI"
          ? origin.trim() || null : null,
        apiKey: platform === "COMFYUI" ? null : apiKey };
      connectionCreateKey.current = stableCreateKey(connectionCreateKey.current, JSON.stringify(payload));
      return createMediaConnection(payload, connectionCreateKey.current.key);
    },
    onSuccess: (result) => {
      connectionCreateKey.current = null;
      queryClient.setQueryData(settingsKey, result);
      setName(""); setOrigin(""); setApiKey(""); setError("");
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
      <Notice title="配置与生成彼此独立">
        默认能力只影响新计划，已批准任务保留原版本。保存配置不会调用付费生成接口，未实测的能力保持“已配置、未实测”。
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
        <div className="media-section-heading">
          <h2>媒体连接</h2><StatusBadge>{settings.data.connections.length} 个连接</StatusBadge>
        </div>
        {settings.data.connections.length === 0
          ? <EmptyState icon={<PlugsConnected size={30} />} title="尚无媒体连接"
            description="添加一个连接，再发布图片或视频能力。" /> : null}
        <div className="ui-stack">
          {settings.data.connections.map((connection) => <ConnectionCard key={connection.id}
            connection={connection} settings={settings.data}
            apply={(result) => queryClient.setQueryData(settingsKey, result)} />)}
        </div>
        <Panel title="添加连接" description="配置本机 ComfyUI，或连接已支持的云端生成平台。">
          <form className="ui-form" onSubmit={(event) => { event.preventDefault(); setError(""); create.mutate(); }}>
            <fieldset className="ui-form-grid media-settings-fieldset" disabled={create.isPending}>
              <label className="ui-field">连接名称
                <input value={name} onChange={(event) => setName(event.target.value)} required maxLength={NAME_LIMIT} />
              </label>
              <label className="ui-field">平台
                <select value={platform} onChange={(event) => setPlatform(event.target.value as typeof platform)}>
                  <option value="COMFYUI">ComfyUI</option><option value="OPENAI">OpenAI</option>
                  <option value="GOOGLE">Google Gemini</option><option value="ARK">火山方舟</option>
                </select>
              </label>
              <ConnectionCredentials platform={platform} origin={origin} apiKey={apiKey}
                onOriginChange={setOrigin} onApiKeyChange={setApiKey} creating />
            </fieldset>
            {error ? <Notice tone="danger">{error}</Notice> : null}
            <div className="ui-form-actions">
              <button className="primary-button" type="submit" disabled={create.isPending}>
                <Plus size={16} />{create.isPending ? "正在保存…" : "添加连接"}
              </button>
            </div>
          </form>
        </Panel>
      </> : null}
    </div>
  </PageShell>;
}
