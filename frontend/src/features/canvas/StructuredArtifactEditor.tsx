import type { FormEvent } from "react";
import { Mountains, TextT, UserCircle } from "@phosphor-icons/react";
import { reviseArtifact, type ReviseArtifactRequest } from "../../shared/api/client";
import type { VersionedArtifact } from "./versionedArtifact";
import { readContentText as readText } from "./artifactContent";
import { ContentEditorFrame } from "./ContentEditorFrame";
import { useArtifactRevision } from "./useArtifactRevision";

const MAX_TEXT_LENGTH = 20000;
const MAX_NAME_LENGTH = 120;
const MAX_DESCRIPTION_LENGTH = 4000;
const MAX_LOCATION_LENGTH = 500;
const MAX_TIME_LENGTH = 80;
const MAX_SCENE_DETAIL_LENGTH = 1000;
type Fields = {
  name: string; description: string; appearance: string; location: string;
  timeOfDay: string; lighting: string; style: string; text: string;
  format: "PLAIN_TEXT" | "MARKDOWN";
};

/** The local base fixes the CAS version until the user saves or explicitly reloads their draft. */
export function StructuredArtifactEditor({ artifact }: { artifact: VersionedArtifact }) {
  const { base, fields, edit, save, reload, status } = useArtifactRevision({ artifact, readFields, saveRevision });
  const kind = base.kind === "TEXT" ? "TEXT" : base.kind === "CHARACTER" ? "CHARACTER" : "SCENE";
  const kindLabel = kind === "TEXT" ? "文字" : kind === "CHARACTER" ? "角色" : "场景";
  const Icon = kind === "TEXT" ? TextT : kind === "CHARACTER" ? UserCircle : Mountains;
  const valid = kind === "TEXT" ? Boolean(fields.text.trim()) : kind === "CHARACTER"
    ? [fields.name, fields.description, fields.appearance].every((value) => value.trim())
    : [fields.name, fields.location, fields.timeOfDay, fields.lighting, fields.style].every((value) => value.trim());
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (status.busy || !status.dirty || !valid) return;
    const content = base.currentVersion.content;
    const references = "referenceVersionIds" in content && Array.isArray(content.referenceVersionIds)
      ? content.referenceVersionIds : [];
    const revision: ReviseArtifactRequest = kind === "TEXT"
      ? { expectedVersion: base.version, title: base.title, content: { format: fields.format, text: fields.text.trim() } }
      : kind === "CHARACTER" ? { expectedVersion: base.version, title: fields.name.trim(), content: {
        name: fields.name.trim(), description: fields.description.trim(), appearance: fields.appearance.trim(), referenceVersionIds: references,
      } } : { expectedVersion: base.version, title: fields.name.trim(), content: {
        name: fields.name.trim(), location: fields.location.trim(), timeOfDay: fields.timeOfDay.trim(),
        lighting: fields.lighting.trim(), style: fields.style.trim(), referenceVersionIds: references,
      } };
    save(revision);
  }

  return <ContentEditorFrame kind={kind} kindLabel={kindLabel} icon={<Icon size={18} />}
    versionNo={base.currentVersion.versionNo} status={status} valid={valid} onSubmit={submit} onReload={reload}
    conflictMessage="内容有冲突，修改未保存；请核对当前版本后重试。" successMessage="新版本已保存。"
    footer={kind === "TEXT" ? <><label className="content-editor-format">格式<select value={fields.format}
      onChange={(event) => edit({ format: event.target.value === "MARKDOWN" ? "MARKDOWN" : "PLAIN_TEXT" })}>
      <option value="PLAIN_TEXT">纯文本</option><option value="MARKDOWN">Markdown</option>
    </select></label><span className="content-editor-count">{fields.text.length} / {MAX_TEXT_LENGTH} 字</span></>
      : <span className="content-editor-footnote">保存为新版本 · 保留原有引用</span>}>
    {kind === "TEXT" ? <label className="content-editor-text-label"><span className="content-editor-sr-only">内容</span>
      <textarea data-content-editor-focus="true" className="content-editor-text" maxLength={MAX_TEXT_LENGTH}
        required placeholder="写下想法、故事或创作说明…" value={fields.text} onChange={(event) => edit({ text: event.target.value })} />
    </label> : <div className="content-editor-grid">
      <label className={kind === "CHARACTER" ? "content-editor-wide" : ""}>名称<input data-content-editor-focus="true"
        maxLength={MAX_NAME_LENGTH} required value={fields.name} onChange={(event) => edit({ name: event.target.value })} /></label>
      {kind === "CHARACTER" ? <>
        <label>描述<textarea maxLength={MAX_DESCRIPTION_LENGTH} required value={fields.description}
          onChange={(event) => edit({ description: event.target.value })} /></label>
        <label>外观<textarea maxLength={MAX_DESCRIPTION_LENGTH} required value={fields.appearance}
          onChange={(event) => edit({ appearance: event.target.value })} /></label>
      </> : <>
        <label>地点<input maxLength={MAX_LOCATION_LENGTH} required value={fields.location} onChange={(event) => edit({ location: event.target.value })} /></label>
        <label>时间<input maxLength={MAX_TIME_LENGTH} required value={fields.timeOfDay} onChange={(event) => edit({ timeOfDay: event.target.value })} /></label>
        <label>光线<textarea maxLength={MAX_SCENE_DETAIL_LENGTH} required value={fields.lighting} onChange={(event) => edit({ lighting: event.target.value })} /></label>
        <label className="content-editor-wide">风格<textarea maxLength={MAX_SCENE_DETAIL_LENGTH} required value={fields.style} onChange={(event) => edit({ style: event.target.value })} /></label>
      </>}
    </div>}
  </ContentEditorFrame>;
}

function saveRevision(base: VersionedArtifact, revision: ReviseArtifactRequest) {
  return reviseArtifact(base.projectId, base.id, revision);
}

function readFields(artifact: VersionedArtifact): Fields {
  const content = artifact.currentVersion.content;
  return { name: readText(content, "name"), description: readText(content, "description"),
    appearance: readText(content, "appearance"), location: readText(content, "location"),
    timeOfDay: readText(content, "timeOfDay"), lighting: readText(content, "lighting"), style: readText(content, "style"),
    text: readText(content, "text"), format: readText(content, "format") === "MARKDOWN" ? "MARKDOWN" : "PLAIN_TEXT" };
}
