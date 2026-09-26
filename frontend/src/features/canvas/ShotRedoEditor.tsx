import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState, type FormEvent } from "react";
import { ArrowUp, CheckCircle, FilmSlate } from "@phosphor-icons/react";
import { ApiError, getArtifact, reviseShotForRedo, type ReviseShotForRedoRequest } from "../../shared/api/client";
import { hasCurrentVersion, type VersionedArtifact } from "./versionedArtifact";
import { CanvasLoadingState } from "./CanvasLoadingState";
import "./ContentArtifactEditor.css";

const MIN_DURATION_SECONDS = 1;
const MAX_DURATION_SECONDS = 30;
const MILLISECONDS_PER_SECOND = 1000;
const MAX_DESCRIPTION_LENGTH = 4000;
const MAX_CAMERA_LENGTH = 1000;
const MAX_ACTION_LENGTH = 2000;
const MAX_SCENE_TIME_LENGTH = 80;
type ShotFields = { description: string; camera: string; action: string; durationSeconds: number; sceneTime: string };

/** Edits one pinned shot version; remote changes cannot silently replace an unsaved draft's CAS base. */
export function ShotRedoEditor({ artifact }: { artifact: VersionedArtifact }) {
  const queryClient = useQueryClient();
  const [base, setBase] = useState(artifact);
  const [fields, setFields] = useState(() => readFields(artifact));
  const dirty = JSON.stringify(fields) !== JSON.stringify(readFields(base));
  const newerAvailable = artifact.version > base.version;
  const legacyDurationMs = readNumber(base.currentVersion.content, "durationMs");
  const validDuration = Number.isInteger(fields.durationSeconds) && fields.durationSeconds >= MIN_DURATION_SECONDS
    && fields.durationSeconds <= MAX_DURATION_SECONDS;
  const valid = validDuration && [fields.description, fields.camera, fields.action].every((value) => value.trim());
  const revise = useMutation({
    mutationFn: (revision: ReviseShotForRedoRequest) => reviseShotForRedo(base.projectId, base.id, revision),
    onSuccess: async (result) => {
      if (hasCurrentVersion(result.shot)) { setBase(result.shot); setFields(readFields(result.shot)); }
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["canvas", base.projectId] }),
        queryClient.invalidateQueries({ queryKey: ["snapshot", base.projectId] }),
        queryClient.invalidateQueries({ queryKey: ["artifact-versions", base.projectId, base.id] }),
      ]);
    },
  });
  const reload = useMutation({
    mutationFn: async () => {
      const latest = await getArtifact(base.projectId, base.id);
      if (!hasCurrentVersion(latest)) throw new Error("当前镜头没有可编辑版本。");
      return latest;
    },
    onSuccess: (latest) => { setBase(latest); setFields(readFields(latest)); revise.reset(); },
  });
  const busy = revise.isPending || reload.isPending;
  const conflict = revise.error instanceof ApiError && revise.error.status === 409;
  useEffect(() => {
    if (artifact.id !== base.id || (artifact.version > base.version && !dirty && !busy)) {
      setBase(artifact); setFields(readFields(artifact));
    }
  }, [artifact, base.id, base.version, dirty, busy]);
  function edit(patch: Partial<ShotFields>) { setFields((current) => ({ ...current, ...patch })); }
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy || !dirty || !valid) return;
    revise.mutate({
      expectedShotVersionId: base.currentVersionId, expectedShotArtifactVersion: base.version,
      description: fields.description, camera: fields.camera, action: fields.action, durationSeconds: fields.durationSeconds,
      ...(fields.sceneTime.trim() ? { scene: { timeOfDay: fields.sceneTime.trim() } } : {}),
    });
  }

  return <section aria-label="镜头编辑器" className="content-artifact-editor content-artifact-editor--shot nodrag nowheel nopan">
    <header className="content-editor-header">
      <span className="content-editor-heading"><FilmSlate size={18} /><strong>镜头</strong><span>v{base.currentVersion.versionNo}</span></span>
      <span className="content-editor-state" aria-live="polite">{busy
        ? <CanvasLoadingState compact label={revise.isPending ? "保存中…" : "正在载入…"} />
        : dirty ? "有未保存修改" : <><CheckCircle size={13} />已保存</>}</span>
    </header>
    <form onSubmit={submit}>
      <fieldset disabled={busy}>
        <div className="content-editor-scroll">
          {newerAvailable || conflict ? <div className="content-editor-notice">
            <p>当前版本已更新。载入最新版本会替换这里未保存的修改。</p>
            <button className="content-editor-text-button" type="button" onClick={() => reload.mutate()}>载入最新版本</button>
          </div> : null}
          {legacyDurationMs > 0 && legacyDurationMs % MILLISECONDS_PER_SECOND !== 0 ?
            <p className="content-editor-notice">历史时长 {legacyDurationMs / MILLISECONDS_PER_SECOND} 秒，需调整为整数秒后才能保存新版本和生成计划。</p> : null}
          {revise.error ? <p className="content-editor-error" role="alert">{conflict
            ? "镜头版本有冲突，修改未保存；请核对最新版本。输入已保留。"
            : `${revise.error instanceof ApiError ? revise.error.message : "修改未完成，请重试。"} 输入已保留。`}</p> : null}
          {reload.error ? <p className="content-editor-error" role="alert">载入失败，当前输入已保留；请重试。</p> : null}
          <div className="content-editor-grid">
            <label className="content-editor-wide">描述<textarea data-content-editor-focus="true" className="content-editor-shot-description"
              maxLength={MAX_DESCRIPTION_LENGTH} required value={fields.description} onChange={(event) => edit({ description: event.target.value })} /></label>
            <label>镜头方式<input maxLength={MAX_CAMERA_LENGTH} required value={fields.camera} onChange={(event) => edit({ camera: event.target.value })} /></label>
            <label>动作<input maxLength={MAX_ACTION_LENGTH} required value={fields.action} onChange={(event) => edit({ action: event.target.value })} /></label>
            <label>时长（秒）<input min={MIN_DURATION_SECONDS} max={MAX_DURATION_SECONDS} step={1} type="number" required
              value={fields.durationSeconds || ""} onChange={(event) => edit({ durationSeconds: Number(event.target.value) })} /></label>
            <label>新场景时间（可选）<input maxLength={MAX_SCENE_TIME_LENGTH} placeholder="例如：黄昏" value={fields.sceneTime}
              onChange={(event) => edit({ sceneTime: event.target.value })} /></label>
          </div>
          <p className="content-editor-explanation">场景时间只为此镜头另建场景版本；旧图片与视频保留在历史，生成仍需审批。</p>
          {revise.isSuccess && !dirty ? <p className="content-editor-success" role="status">新版本已保存。选择此镜头与 Agent 卡片，绑定新版本后发起新 Run；媒体计划仍需单独审批。</p> : null}
        </div>
        <footer className="content-editor-footer">
          <span className="content-editor-footnote">只修改此镜头 · 保留历史</span>
          <button aria-label={revise.isPending ? "保存新版本中…" : "保存局部修改"} className="content-editor-save"
            disabled={busy || !dirty || !valid} type="submit"><span>{revise.isPending ? "保存中…" : "保存新版本"}</span><ArrowUp size={17} weight="bold" /></button>
        </footer>
      </fieldset>
    </form>
  </section>;
}

function readFields(artifact: VersionedArtifact): ShotFields {
  const content = artifact.currentVersion.content;
  return { description: readText(content, "description"), camera: readText(content, "camera"), action: readText(content, "action"),
    durationSeconds: readNumber(content, "durationSeconds") || readNumber(content, "durationMs") / MILLISECONDS_PER_SECOND, sceneTime: "" };
}
function readText(content: unknown, field: string): string {
  if (typeof content !== "object" || content === null || !(field in content)) return "";
  const value = content[field as keyof typeof content];
  return typeof value === "string" ? value : "";
}
function readNumber(content: unknown, field: string): number {
  if (typeof content !== "object" || content === null || !(field in content)) return 0;
  const value = content[field as keyof typeof content];
  return typeof value === "number" ? value : 0;
}
