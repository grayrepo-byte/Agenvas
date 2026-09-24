import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useState, type FormEvent } from "react";
import { reviseShotForRedo, type Artifact } from "../../shared/api/client";

/** Edits one exact shot version while leaving sibling shot references untouched. */
export function ShotRedoEditor({ artifact }: { artifact: Artifact }) {
  const queryClient = useQueryClient();
  const [description, setDescription] = useState(readText(artifact.currentVersion.content, "description"));
  const [camera, setCamera] = useState(readText(artifact.currentVersion.content, "camera"));
  const [action, setAction] = useState(readText(artifact.currentVersion.content, "action"));
  const [durationMs, setDurationMs] = useState(readNumber(artifact.currentVersion.content, "durationMs"));
  const [sceneTime, setSceneTime] = useState("");
  const revise = useMutation({
    mutationFn: () => reviseShotForRedo(artifact.projectId, artifact.id, {
      expectedShotVersionId: artifact.currentVersionId,
      expectedShotArtifactVersion: artifact.version,
      description, camera, action, durationMs,
      ...(sceneTime.trim() ? { scene: { timeOfDay: sceneTime.trim() } } : {}),
    }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["canvas", artifact.projectId] });
      await queryClient.invalidateQueries({ queryKey: ["snapshot", artifact.projectId] });
    },
  });

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    revise.mutate();
  }

  return <details className="nodrag nowheel mt-3 border-t border-[var(--line)] pt-2 text-xs">
    <summary className="cursor-pointer font-semibold">修改此镜头</summary>
    <p className="mt-2 text-[var(--muted)]">只为本镜头创建新版本。填写场景时间会另建共享场景版本，并只重绑此镜头；旧图片/视频保留在历史，不自动沿用。</p>
    <form className="mt-2 space-y-2" onSubmit={submit}>
      <label className="block">描述<textarea className="mt-1 min-h-16 w-full rounded-lg border border-[var(--line)] bg-white p-2"
        maxLength={4000} onChange={(event) => setDescription(event.target.value)} required value={description} /></label>
      <label className="block">镜头方式<input maxLength={1000} onChange={(event) => setCamera(event.target.value)}
        required value={camera} /></label>
      <label className="block">动作<input maxLength={2000} onChange={(event) => setAction(event.target.value)}
        required value={action} /></label>
      <label className="block">时长（毫秒）<input min={100} max={30000} onChange={(event) =>
        setDurationMs(Number(event.target.value))} required type="number" value={durationMs} /></label>
      <label className="block">新场景时间（可选）<input maxLength={80}
        onChange={(event) => setSceneTime(event.target.value)} placeholder="例如：黄昏" value={sceneTime} /></label>
      <button className="node-action" disabled={revise.isPending} type="submit">
        {revise.isPending ? "保存新版本中…" : "保存局部修改"}
      </button>
    </form>
    {revise.isSuccess ? <p className="mt-2 text-emerald-800" role="status">
      新版本已保存。选择此镜头与 Agent 卡片，绑定新版本后发起新 Run；媒体计划仍需单独审批。
    </p> : null}
    {revise.error ? <p className="mt-2 text-red-700" role="alert">{revise.error.message}</p> : null}
  </details>;
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
