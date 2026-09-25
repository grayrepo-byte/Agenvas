import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useState, type FormEvent } from "react";
import { ApiError, reviseArtifact,
  type ReviseArtifactRequest } from "../../shared/api/client";
import type { VersionedArtifact } from "./versionedArtifact";

/** Edits user-authored TEXT/CHARACTER/SCENE fields while preserving exact media references. */
export function StructuredArtifactEditor({ artifact }: { artifact: VersionedArtifact }) {
  const queryClient = useQueryClient();
  const content = artifact.currentVersion.content;
  const [name, setName] = useState(readText(content, "name"));
  const [description, setDescription] = useState(readText(content, "description"));
  const [appearance, setAppearance] = useState(readText(content, "appearance"));
  const [location, setLocation] = useState(readText(content, "location"));
  const [timeOfDay, setTimeOfDay] = useState(readText(content, "timeOfDay"));
  const [lighting, setLighting] = useState(readText(content, "lighting"));
  const [style, setStyle] = useState(readText(content, "style"));
  const [text, setText] = useState(readText(content, "text"));
  const [format, setFormat] = useState<"PLAIN_TEXT" | "MARKDOWN">(
    readText(content, "format") === "MARKDOWN" ? "MARKDOWN" : "PLAIN_TEXT");

  const save = useMutation({
    mutationFn: () => {
      const references = "referenceVersionIds" in content &&
        Array.isArray(content.referenceVersionIds) ? content.referenceVersionIds : [];
      const revision: ReviseArtifactRequest = artifact.kind === "TEXT"
        ? { expectedVersion: artifact.version, title: artifact.title,
          content: { format, text: text.trim() } }
        : artifact.kind === "CHARACTER"
        ? { expectedVersion: artifact.version, title: name.trim(), content: {
          name: name.trim(), description: description.trim(),
          appearance: appearance.trim(), referenceVersionIds: references,
        } }
        : { expectedVersion: artifact.version, title: name.trim(), content: {
          name: name.trim(), location: location.trim(), timeOfDay: timeOfDay.trim(),
          lighting: lighting.trim(), style: style.trim(), referenceVersionIds: references,
        } };
      return reviseArtifact(artifact.projectId, artifact.id, revision);
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["canvas", artifact.projectId] });
      await queryClient.invalidateQueries({ queryKey: ["snapshot", artifact.projectId] });
      await queryClient.invalidateQueries({ queryKey: ["artifact-versions", artifact.projectId,
        artifact.id] });
    },
  });

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    save.mutate();
  }

  return <details className="nodrag nowheel mt-3 border-t border-[var(--line)] pt-2 text-xs">
    <summary className="cursor-pointer font-semibold">修改{artifact.kind === "TEXT" ? "文字内容"
      : artifact.kind === "CHARACTER" ? "角色说明" : "场景说明"}</summary>
    <p className="mt-2 text-[var(--muted)]">保存为新内容版本；已有版本保持不变。</p>
    <form className="mt-2 space-y-2" onSubmit={submit}>
      <fieldset className="space-y-2" disabled={save.isPending}>
        {artifact.kind === "TEXT" ? <>
          <label className="block">格式<select className="mt-1 w-full" value={format}
            onChange={(event) => setFormat(event.target.value as "PLAIN_TEXT" | "MARKDOWN")}>
            <option value="PLAIN_TEXT">纯文本</option><option value="MARKDOWN">Markdown</option>
          </select></label>
          <label className="block">内容<textarea className="mt-1 w-full min-h-24" maxLength={20000}
            required value={text} onChange={(event) => setText(event.target.value)} /></label>
        </> : <>
          <label className="block">名称<input className="mt-1 w-full" maxLength={120} required
            value={name} onChange={(event) => setName(event.target.value)} /></label>
        {artifact.kind === "CHARACTER" ? <>
          <label className="block">描述<textarea className="mt-1 w-full" maxLength={4000}
            required value={description} onChange={(event) => setDescription(event.target.value)} /></label>
          <label className="block">外观<textarea className="mt-1 w-full" maxLength={4000}
            required value={appearance} onChange={(event) => setAppearance(event.target.value)} /></label>
        </> : <>
          <label className="block">地点<input className="mt-1 w-full" maxLength={500}
            required value={location} onChange={(event) => setLocation(event.target.value)} /></label>
          <label className="block">时间<input className="mt-1 w-full" maxLength={80}
            required value={timeOfDay} onChange={(event) => setTimeOfDay(event.target.value)} /></label>
          <label className="block">光线<textarea className="mt-1 w-full" maxLength={1000}
            required value={lighting} onChange={(event) => setLighting(event.target.value)} /></label>
          <label className="block">风格<textarea className="mt-1 w-full" maxLength={1000}
            required value={style} onChange={(event) => setStyle(event.target.value)} /></label>
        </>}
        </>}
        <button className="node-action" type="submit">
          {save.isPending ? "保存中…" : "保存新版本"}</button>
      </fieldset>
    </form>
    {save.isSuccess ? <p className="mt-2 text-emerald-800" role="status">新版本已保存。</p> : null}
    {save.error ? <p className="mt-2 text-red-700" role="alert">
      {save.error instanceof ApiError && save.error.status === 409
        ? "内容有冲突，修改未保存；请核对当前版本后重试。"
        : save.error instanceof ApiError ? save.error.message : "修改未完成，请重试。"}
    </p> : null}
  </details>;
}

function readText(content: unknown, field: string): string {
  if (typeof content !== "object" || content === null || !(field in content)) return "";
  const value = content[field as keyof typeof content];
  return typeof value === "string" ? value : "";
}
