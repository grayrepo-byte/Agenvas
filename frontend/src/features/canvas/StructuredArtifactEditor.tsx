import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState, type FormEvent } from "react";
import { ArrowUp, CheckCircle, Mountains, TextT, UserCircle } from "@phosphor-icons/react";
import { ApiError, getArtifact, reviseArtifact, type ReviseArtifactRequest } from "../../shared/api/client";
import { hasCurrentVersion, type VersionedArtifact } from "./versionedArtifact";
import { CanvasLoadingState } from "./CanvasLoadingState";
import "./ContentArtifactEditor.css";

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
  const queryClient = useQueryClient();
  const [base, setBase] = useState(artifact);
  const [fields, setFields] = useState(() => readFields(artifact));
  const dirty = JSON.stringify(fields) !== JSON.stringify(readFields(base));
  const newerAvailable = artifact.version > base.version;
  const kind = base.kind === "TEXT" ? "TEXT" : base.kind === "CHARACTER" ? "CHARACTER" : "SCENE";
  const kindLabel = kind === "TEXT" ? "文字" : kind === "CHARACTER" ? "角色" : "场景";
  const Icon = kind === "TEXT" ? TextT : kind === "CHARACTER" ? UserCircle : Mountains;
  const valid = kind === "TEXT" ? Boolean(fields.text.trim()) : kind === "CHARACTER"
    ? [fields.name, fields.description, fields.appearance].every((value) => value.trim())
    : [fields.name, fields.location, fields.timeOfDay, fields.lighting, fields.style].every((value) => value.trim());
  const save = useMutation({
    mutationFn: (revision: ReviseArtifactRequest) => reviseArtifact(base.projectId, base.id, revision),
    onSuccess: async (saved) => {
      if (hasCurrentVersion(saved)) { setBase(saved); setFields(readFields(saved)); }
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
      if (!hasCurrentVersion(latest)) throw new Error("当前产物没有可编辑版本。");
      return latest;
    },
    onSuccess: (latest) => { setBase(latest); setFields(readFields(latest)); save.reset(); },
  });
  const busy = save.isPending || reload.isPending;
  const conflict = save.error instanceof ApiError && save.error.status === 409;
  useEffect(() => {
    // A remote update may refresh a clean editor, but never rewrites an unsaved draft or its CAS base.
    if (artifact.id !== base.id || (artifact.version > base.version && !dirty && !busy)) {
      setBase(artifact); setFields(readFields(artifact));
    }
  }, [artifact, base.id, base.version, dirty, busy]);

  function edit(patch: Partial<Fields>) { setFields((current) => ({ ...current, ...patch })); }
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy || !dirty || !valid) return;
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
    save.mutate(revision);
  }

  return <section aria-label={`${kindLabel}编辑器`} className={`content-artifact-editor content-artifact-editor--${kind.toLowerCase()} nodrag nowheel nopan`}>
    <header className="content-editor-header">
      <span className="content-editor-heading"><Icon size={18} /><strong>{kindLabel}</strong><span>v{base.currentVersion.versionNo}</span></span>
      <span className="content-editor-state" aria-live="polite">{busy
        ? <CanvasLoadingState compact label={save.isPending ? "保存中…" : "正在载入…"} />
        : dirty ? "有未保存修改" : <><CheckCircle size={13} />已保存</>}</span>
    </header>
    <form onSubmit={submit}>
      <fieldset disabled={busy}>
        <div className={`content-editor-scroll ${kind === "TEXT" ? "content-editor-text-area" : ""}`}>
          {newerAvailable || conflict ? <div className="content-editor-notice">
            <p>当前版本已更新。载入最新版本会替换这里未保存的修改。</p>
            <button className="content-editor-text-button" type="button" onClick={() => reload.mutate()}>载入最新版本</button>
          </div> : null}
          {save.error ? <p className="content-editor-error" role="alert">{conflict
            ? "内容有冲突，修改未保存；请核对当前版本后重试。"
            : `${save.error instanceof ApiError ? save.error.message : "修改未完成，请重试。"} 输入已保留。`}</p> : null}
          {reload.error ? <p className="content-editor-error" role="alert">载入失败，当前输入已保留；请重试。</p> : null}
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
          {save.isSuccess && !dirty ? <p className="content-editor-success" role="status">新版本已保存。</p> : null}
        </div>
        <footer className="content-editor-footer">
          {kind === "TEXT" ? <><label className="content-editor-format">格式<select value={fields.format}
            onChange={(event) => edit({ format: event.target.value === "MARKDOWN" ? "MARKDOWN" : "PLAIN_TEXT" })}>
            <option value="PLAIN_TEXT">纯文本</option><option value="MARKDOWN">Markdown</option>
          </select></label><span className="content-editor-count">{fields.text.length} / {MAX_TEXT_LENGTH} 字</span></>
            : <span className="content-editor-footnote">保存为新版本 · 保留原有引用</span>}
          <button className="content-editor-save" type="submit" disabled={busy || !dirty || !valid}>
            <span>{save.isPending ? "保存中…" : "保存新版本"}</span><ArrowUp size={17} weight="bold" />
          </button>
        </footer>
      </fieldset>
    </form>
  </section>;
}

function readFields(artifact: VersionedArtifact): Fields {
  const content = artifact.currentVersion.content;
  return { name: readText(content, "name"), description: readText(content, "description"),
    appearance: readText(content, "appearance"), location: readText(content, "location"),
    timeOfDay: readText(content, "timeOfDay"), lighting: readText(content, "lighting"), style: readText(content, "style"),
    text: readText(content, "text"), format: readText(content, "format") === "MARKDOWN" ? "MARKDOWN" : "PLAIN_TEXT" };
}
function readText(content: unknown, field: string): string {
  if (typeof content !== "object" || content === null || !(field in content)) return "";
  const value = content[field as keyof typeof content];
  return typeof value === "string" ? value : "";
}
