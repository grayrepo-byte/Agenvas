import type { FormEvent } from "react";
import { FilmSlate } from "@phosphor-icons/react";
import { reviseShotForRedo, type ReviseShotForRedoRequest } from "../../shared/api/client";
import type { VersionedArtifact } from "./versionedArtifact";
import { readContentNumber as readNumber, readContentText as readText } from "./artifactContent";
import { ContentEditorFrame } from "./ContentEditorFrame";
import { useArtifactRevision } from "./useArtifactRevision";

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
  const { base, fields, edit, save, reload, status } = useArtifactRevision({ artifact, readFields, saveRevision });
  const legacyDurationMs = readNumber(base.currentVersion.content, "durationMs");
  const validDuration = Number.isInteger(fields.durationSeconds) && fields.durationSeconds >= MIN_DURATION_SECONDS
    && fields.durationSeconds <= MAX_DURATION_SECONDS;
  const valid = validDuration && [fields.description, fields.camera, fields.action].every((value) => value.trim());
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (status.busy || !status.dirty || !valid) return;
    save({
      expectedShotVersionId: base.currentVersionId, expectedShotArtifactVersion: base.version,
      description: fields.description, camera: fields.camera, action: fields.action, durationSeconds: fields.durationSeconds,
      ...(fields.sceneTime.trim() ? { scene: { timeOfDay: fields.sceneTime.trim() } } : {}),
    });
  }

  return <ContentEditorFrame kind="SHOT" kindLabel="镜头" icon={<FilmSlate size={18} />}
    versionNo={base.currentVersion.versionNo} status={status} valid={valid} onSubmit={submit} onReload={reload}
    conflictMessage="镜头版本有冲突，修改未保存；请核对最新版本。输入已保留。"
    successMessage="新版本已保存。选择此镜头与 Agent 卡片，绑定新版本后发起新 Run；媒体计划仍需单独审批。"
    saveAriaLabel={status.saving ? "保存新版本中…" : "保存局部修改"}
    notice={legacyDurationMs > 0 && legacyDurationMs % MILLISECONDS_PER_SECOND !== 0
      ? <p className="content-editor-notice">历史时长 {legacyDurationMs / MILLISECONDS_PER_SECOND} 秒，需调整为整数秒后才能保存新版本和生成计划。</p> : null}
    footer={<span className="content-editor-footnote">只修改此镜头 · 保留历史</span>}>
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
  </ContentEditorFrame>;
}

async function saveRevision(base: VersionedArtifact, revision: ReviseShotForRedoRequest) {
  const result = await reviseShotForRedo(base.projectId, base.id, revision);
  return result.shot;
}

function readFields(artifact: VersionedArtifact): ShotFields {
  const content = artifact.currentVersion.content;
  return { description: readText(content, "description"), camera: readText(content, "camera"), action: readText(content, "action"),
    durationSeconds: readNumber(content, "durationSeconds") || readNumber(content, "durationMs") / MILLISECONDS_PER_SECOND, sceneTime: "" };
}
