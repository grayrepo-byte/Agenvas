import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { type FormEvent, useRef, useState } from "react";
import { Link, Navigate } from "react-router";
import {
  ApiError, createMediaCapability, createMediaConnection, getCurrentUser,
  getMediaSettings, setMediaDefault, updateMediaCapability, updateMediaConcurrency,
  updateMediaConnection,
  type MediaCapability, type MediaConnection, type MediaSettings,
} from "../../shared/api/client";

const settingsKey = ["settings", "media"] as const;
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
  return <label className="text-xs">GPT Image 2 质量
    <select value={value} onChange={(event) => onChange(event.target.value)}>
      <option value="low">low</option><option value="medium">medium</option>
      <option value="high">high</option>
    </select>
  </label>;
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
  const [name, setName] = useState(capability.name);
  const [adapterId, setAdapterId] = useState(capability.adapterId);
  const [modelNames, setModelNames] = useState<Record<string, string>>(capability.settings);
  const [maxConcurrent, setMaxConcurrent] = useState(capability.maxConcurrent);
  const [error, setError] = useState("");
  const save = useMutation({
    mutationFn: () => updateMediaCapability(connectionId, capability.id, {
      expectedVersion: capability.version, name: name.trim(),
      enabled: capability.enabled, adapterId,
      settings: fixedModelSettings(adapterId, modelNames),
    }),
    onSuccess: (result) => { apply(result); setError(""); },
    onError: (cause) => {
      if (cause instanceof ApiError && cause.status === 409) {
        void queryClient.invalidateQueries({ queryKey: settingsKey });
      }
      setError(errorMessage(cause));
    },
  });
  const saveConcurrency = useMutation({
    mutationFn: () => updateMediaConcurrency(connectionId, capability.id,
      { expectedVersion: capability.version, maxConcurrent }),
    onSuccess: (result) => { apply(result); setError(""); },
    onError: (cause) => {
      if (cause instanceof ApiError && cause.status === 409) {
        void queryClient.invalidateQueries({ queryKey: settingsKey });
      }
      setError(errorMessage(cause));
    },
  });
  const sameKindAdapters = availableAdapters.filter((id) => adapterKind[id] === capability.kind);
  return <li className="rounded-xl border border-[var(--line)] p-3">
    <div className="flex flex-wrap items-center justify-between gap-2">
      <div>
        <p className="font-medium">{capability.name}{isDefault ? " · 默认" : ""}</p>
        <p className="text-xs text-[var(--muted)]">{capability.kind === "IMAGE_GENERATION" ? "图片" : "视频"} · {capability.adapterId} · v{capability.capabilityVersion} · {capability.enabled ? "已启用" : "已停用"}</p>
      </div>
      <div className="flex gap-2">
        <button className="secondary-button" type="button" disabled={busy || save.isPending || !connectionEnabled || !capability.enabled || isDefault}
          onClick={() => act("default", capability)}>设为默认</button>
        <button className="secondary-button" type="button" disabled={busy || save.isPending}
          onClick={() => act("capability", capability)}>{capability.enabled ? "停用" : "启用"}</button>
      </div>
    </div>
    <form className="mt-3 flex flex-wrap items-end gap-2" onSubmit={(event) => {
      event.preventDefault(); setError(""); save.mutate();
    }}>
      <label className="text-xs">能力名称
        <input value={name} onChange={(event) => setName(event.target.value)} required maxLength={160} />
      </label>
      <label className="text-xs">固定适配器
        <select value={adapterId} onChange={(event) => setAdapterId(event.target.value)}>
          {sameKindAdapters.map((id) => <option key={id} value={id}>{id}</option>)}
        </select>
      </label>
      {fixedModelFields(adapterId).map(({ key, label }) => <label key={key} className="text-xs">{label}
        <input value={modelNames[key] ?? ""} onChange={(event) => setModelNames((old) =>
          ({ ...old, [key]: event.target.value }))} required={key !== "model"} maxLength={160}
          placeholder={key === "model" ? "留空使用内置默认" : "model.safetensors"} />
      </label>)}
      {adapterId === "OPENAI_GPT_IMAGE_2" ? <QualityChoice value={modelNames.quality ?? "medium"}
        onChange={(quality) => setModelNames((old) => ({ ...old, quality }))} /> : null}
      <button className="secondary-button" type="submit" disabled={busy || save.isPending}>
        {save.isPending ? "正在保存…" : "保存能力"}
      </button>
    </form>
    <form className="mt-3 flex items-end gap-2" onSubmit={(event) => {
      event.preventDefault(); setError(""); saveConcurrency.mutate();
    }}>
      <label className="text-xs">全局并发上限
        <input type="number" min={1} max={100} value={maxConcurrent}
          onChange={(event) => setMaxConcurrent(Number(event.target.value))} />
      </label>
      <button className="secondary-button" type="submit"
        disabled={busy || saveConcurrency.isPending}>保存并发上限</button>
    </form>
    {error ? <p className="mt-2 text-sm text-red-800" role="alert">{error}</p> : null}
  </li>;
}

function ConnectionCard({ connection, settings, apply }: {
  connection: MediaConnection;
  settings: MediaSettings;
  apply: (value: MediaSettings) => void;
}) {
  const queryClient = useQueryClient();
  const [name, setName] = useState(connection.name);
  const [origin, setOrigin] = useState(connection.origin ?? "");
  const [apiKey, setApiKey] = useState("");
  const [capabilityName, setCapabilityName] = useState("");
  const [adapterId, setAdapterId] = useState(connection.platform === "COMFYUI" ? "COMFY_IMAGE_V1"
    : connection.platform === "OPENAI" ? "OPENAI_GPT_IMAGE_2"
    : connection.platform === "GOOGLE" ? "GOOGLE_NANO_BANANA_2"
    : connection.platform === "ARK" ? "ARK_SEEDANCE_2_I2V" : "MOCK_IMAGE");
  const [newModelNames, setNewModelNames] = useState<Record<string, string>>({});
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const capabilityCreateKey = useRef<{ payload: string; key: string } | null>(null);
  const save = useMutation({
    mutationFn: () => updateMediaConnection(connection.id, {
      expectedVersion: connection.version, name: name.trim(), enabled: connection.enabled,
      origin: connection.platform === "COMFYUI" || connection.platform === "OPENAI"
        ? origin.trim() || null : null,
      apiKey: apiKey || null,
    }),
    onSuccess: (result) => {
      apply(result);
      setApiKey("");
      setError("");
      setNotice("连接已保存，生成接口未实测。");
    },
    onError: (cause) => {
      setNotice("");
      if (cause instanceof ApiError && cause.status === 409) {
        void queryClient.invalidateQueries({ queryKey: settingsKey });
      }
      setError(cause instanceof ApiError && cause.status === 409
        ? "配置已被其他管理员修改，已刷新版本；请核对当前草稿后重试。"
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
      if (cause instanceof ApiError && cause.status === 409) {
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
      if (cause instanceof ApiError && cause.status === 409) {
        void queryClient.invalidateQueries({ queryKey: settingsKey });
      }
      setError(errorMessage(cause));
    },
  });
  const busy = save.isPending || addCapability.isPending || mutate.isPending;
  const availableAdapters = connection.platform === "MOCK"
    ? ["MOCK_IMAGE", "MOCK_VIDEO"]
    : connection.platform === "COMFYUI" ? ["COMFY_IMAGE_V1", "COMFY_VIDEO_V1"]
    : connection.platform === "OPENAI" ? ["OPENAI_GPT_IMAGE_2"]
    : connection.platform === "GOOGLE" ? ["GOOGLE_NANO_BANANA_2"]
    : connection.platform === "ARK" ? ["ARK_SEEDANCE_2_I2V"] : [];

  function submitConnection(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError("");
    save.mutate();
  }

  return <section className="rounded-3xl border border-[var(--line)] bg-[var(--panel)] p-6">
    <div className="flex flex-wrap items-start justify-between gap-3">
      <div>
        <h2 className="text-xl font-semibold">{connection.name}</h2>
        <p className="mt-1 text-sm text-[var(--muted)]">{connection.platform} · 连接版本 {connection.connectionVersion} · {connection.enabled ? "已启用" : "已停用"}</p>
        <p className="mt-1 text-sm text-[var(--muted)]">{connection.keyMask ? `密钥 ${connection.keyMask} · ` : ""}已配置、未实测</p>
      </div>
      <button className="secondary-button" type="button" disabled={busy}
        onClick={() => mutate.mutate({ type: "connection" })}>{connection.enabled ? "停用连接" : "启用连接"}</button>
    </div>
    <form className="mt-5 grid gap-3" onSubmit={submitConnection}>
      <label className="text-sm">连接名称
        <input value={name} onChange={(event) => setName(event.target.value)} required maxLength={160} />
      </label>
      {connection.platform === "COMFYUI" ? <label className="text-sm">本机 ComfyUI 地址
        <input value={origin} onChange={(event) => setOrigin(event.target.value)} required
          placeholder="http://127.0.0.1:8188" />
      </label> : null}
      {connection.platform === "OPENAI" ? <label className="text-sm">API Base URL（留空使用官方地址）
        <input type="url" value={origin} onChange={(event) => setOrigin(event.target.value)}
          maxLength={500} placeholder="https://api.openai.com/v1" />
      </label> : null}
      {connection.platform === "OPENAI" || connection.platform === "ARK" ||
        connection.platform === "GOOGLE" ? <label className="text-sm">替换 API Key（留空则不修改）
        <input type="password" autoComplete="new-password" value={apiKey}
          onChange={(event) => setApiKey(event.target.value)} />
      </label> : null}
      <button className="secondary-button w-fit" type="submit" disabled={busy}>{save.isPending ? "正在保存…" : "保存连接"}</button>
    </form>
    <div className="mt-6 border-t border-[var(--line)] pt-5">
      <h3 className="font-semibold">已发布能力</h3>
      {connection.capabilities.length === 0 ? <p className="mt-2 text-sm text-[var(--muted)]">尚无能力</p> : null}
      <ul className="mt-3 space-y-3">
        {connection.capabilities.map((capability) => {
          const isDefault = settings.defaults.some((item) => item.kind === capability.kind && item.capabilityId === capability.id);
          return <CapabilityRow key={capability.id} connectionId={connection.id}
            capability={capability} isDefault={isDefault}
            connectionEnabled={connection.enabled} availableAdapters={availableAdapters}
            busy={busy} apply={apply}
            act={(type, selected) => mutate.mutate({ type, capability: selected })} />;
        })}
      </ul>
      {availableAdapters.length > 0 ? <form className="mt-4 flex flex-wrap items-end gap-3" onSubmit={(event) => {
        event.preventDefault();
        setError("");
        addCapability.mutate();
      }}>
        <label className="text-sm">新能力名称
          <input value={capabilityName} onChange={(event) => setCapabilityName(event.target.value)} required />
        </label>
        <label className="text-sm">固定适配器
          <select value={adapterId} onChange={(event) => setAdapterId(event.target.value)}>
            {availableAdapters.map((id) => <option key={id} value={id}>{id}</option>)}
          </select>
        </label>
        {fixedModelFields(adapterId).map(({ key, label }) => <label key={key} className="text-sm">{label}
          <input value={newModelNames[key] ?? ""} onChange={(event) => setNewModelNames((old) =>
            ({ ...old, [key]: event.target.value }))} required={key !== "model"} maxLength={160}
            placeholder={key === "model" ? "留空使用内置默认" : "model.safetensors"} />
        </label>)}
        {adapterId === "OPENAI_GPT_IMAGE_2" ? <QualityChoice value={newModelNames.quality ?? "medium"}
          onChange={(quality) => setNewModelNames((old) => ({ ...old, quality }))} /> : null}
        <button className="secondary-button" type="submit" disabled={busy || !connection.enabled}>{addCapability.isPending ? "正在发布…" : "发布能力"}</button>
      </form> : <p className="mt-4 text-sm text-[var(--muted)]">此平台的固定适配器尚未安装。</p>}
    </div>
    {error ? <p className="mt-4 rounded-xl bg-red-50 p-3 text-sm text-red-800" role="alert">{error}</p> : null}
    {notice ? <p className="mt-4 rounded-xl bg-green-50 p-3 text-sm text-green-800" role="status">{notice}</p> : null}
  </section>;
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

  if (currentUser.isError || (settings.error instanceof ApiError && settings.error.status === 401)) {
    return <Navigate to="/login" replace />;
  }
  return <main className="min-h-screen bg-[var(--canvas)] p-8 text-[var(--ink)]">
    <div className="mx-auto max-w-4xl">
      <Link className="text-sm underline" to="/settings/llm">返回设置</Link>
      <h1 className="mt-6 text-3xl font-semibold">媒体连接与能力</h1>
      <p className="mt-3 text-sm text-[var(--muted)]">一个连接可发布多条图片和视频能力。默认值只影响新计划；已批准任务保留原版本。</p>
      <p className="mt-2 text-sm text-[var(--muted)]">保存配置不会调用付费生成接口，未实测的能力保持“已配置、未实测”。</p>
      {currentUser.isPending || settings.isPending ? <p className="mt-8">正在读取媒体配置…</p> : null}
      {settings.error instanceof ApiError && settings.error.status === 403 ? <p role="alert" className="mt-8">只有管理员可以查看媒体配置。</p> : null}
      {settings.isError && !(settings.error instanceof ApiError && [401, 403].includes(settings.error.status))
        ? <p role="alert" className="mt-8">读取失败，请刷新后重试。</p> : null}
      {settings.data ? <>
        {settings.data.connections.length === 0 ? <p className="mt-8">尚无媒体连接</p> : null}
        <div className="mt-8 grid gap-5">
          {settings.data.connections.map((connection) => <ConnectionCard key={connection.id}
            connection={connection} settings={settings.data}
            apply={(result) => queryClient.setQueryData(settingsKey, result)} />)}
        </div>
        <form className="mt-8 grid gap-4 rounded-3xl border border-[var(--line)] bg-[var(--panel)] p-6"
          onSubmit={(event) => { event.preventDefault(); setError(""); create.mutate(); }}>
          <h2 className="text-xl font-semibold">添加连接</h2>
          <label className="text-sm">连接名称
            <input value={name} onChange={(event) => setName(event.target.value)} required maxLength={160} />
          </label>
          <label className="text-sm">平台
            <select value={platform} onChange={(event) => setPlatform(event.target.value as typeof platform)}>
              <option value="COMFYUI">ComfyUI</option><option value="OPENAI">OpenAI</option>
              <option value="GOOGLE">Google Gemini</option><option value="ARK">火山方舟</option>
            </select>
          </label>
          {platform === "COMFYUI" ? <label className="text-sm">本机 ComfyUI 地址
            <input value={origin} onChange={(event) => setOrigin(event.target.value)}
              required placeholder="http://127.0.0.1:8188" />
          </label> : <label className="text-sm">API Key
            <input type="password" autoComplete="new-password" value={apiKey}
              onChange={(event) => setApiKey(event.target.value)} required />
          </label>}
          {platform === "OPENAI" ? <label className="text-sm">API Base URL（留空使用官方地址）
            <input type="url" value={origin} onChange={(event) => setOrigin(event.target.value)}
              maxLength={500} placeholder="https://api.openai.com/v1" />
          </label> : null}
          {error ? <p className="rounded-xl bg-red-50 p-3 text-sm text-red-800" role="alert">{error}</p> : null}
          <button className="primary-button w-fit" type="submit" disabled={create.isPending}>
            {create.isPending ? "正在保存…" : "添加连接"}
          </button>
        </form>
      </> : null}
    </div>
  </main>;
}
