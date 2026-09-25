import { useMutation } from "@tanstack/react-query";
import { useRef, useState, type FormEvent } from "react";
import { ApiError, applyCanvasCommands, createArtifact,
  type Canvas, type CanvasItem, type CreateArtifactRequest } from "../../shared/api/client";

type ManualKind = "CHARACTER" | "SCENE" | "SHOT";

type Props = {
  projectId: string;
  items: CanvasItem[];
  onSaveStart: () => void;
  onSaved: (canvas: Canvas) => void;
  onSaveError: (error: Error) => void;
};

/** Manual structured creation remains useful in Mock mode and never starts a Run. */
export function ManualStoryboardPanel({ projectId, items, onSaveStart, onSaved,
  onSaveError }: Props) {
  const [kind, setKind] = useState<ManualKind>("SCENE");
  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  const [appearance, setAppearance] = useState("");
  const [location, setLocation] = useState("");
  const [timeOfDay, setTimeOfDay] = useState("");
  const [lighting, setLighting] = useState("");
  const [style, setStyle] = useState("");
  const [order, setOrder] = useState(1);
  const [durationSeconds, setDurationSeconds] = useState(3);
  const [camera, setCamera] = useState("");
  const [action, setAction] = useState("");
  const [sceneVersionId, setSceneVersionId] = useState("");
  const [characterVersionIds, setCharacterVersionIds] = useState<string[]>([]);
  const progress = useRef<{ fingerprint: string; artifactId?: string; itemId: string;
    createKey: string } | null>(null);
  const artifactOptions = new Map(items.flatMap((item) => item.artifact
    ? [[item.artifact.id, item.artifact] as const] : []));
  const scenes = [...artifactOptions.values()].filter((artifact) => artifact.kind === "SCENE");
  const characters = [...artifactOptions.values()].filter(
    (artifact) => artifact.kind === "CHARACTER");

  const create = useMutation({
    mutationFn: async (input: CreateArtifactRequest) => {
      const fingerprint = JSON.stringify({ projectId, input });
      if (progress.current?.fingerprint !== fingerprint) {
        progress.current = { fingerprint, itemId: crypto.randomUUID(),
          createKey: crypto.randomUUID() };
      }
      const pending = progress.current;
      if (!pending.artifactId) {
        const artifact = await createArtifact(projectId, input, pending.createKey);
        pending.artifactId = artifact.id;
      }
      const index = items.length;
      return applyCanvasCommands(projectId, [{
        type: "PLACE_ARTIFACT", itemId: pending.itemId, artifactId: pending.artifactId,
        x: 80 + (index % 3) * 320, y: 80 + Math.floor(index / 3) * 220,
        width: input.kind === "SHOT" ? 320 : 280,
        height: input.kind === "SHOT" ? 260 : 220,
        zIndex: index, locked: false,
      }]);
    },
    onMutate: onSaveStart,
    onSuccess: (canvas, input) => {
      progress.current = null;
      onSaved(canvas);
      setTitle("");
      setDescription("");
      setAppearance("");
      setLocation("");
      setTimeOfDay("");
      setLighting("");
      setStyle("");
      setCamera("");
      setAction("");
      if (input.kind === "SHOT") setOrder((current) => Math.min(6, current + 1));
    },
    onError: onSaveError,
  });

  /** Client form shapes mirror the server's current content schemas. */
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const name = title.trim();
    let input: CreateArtifactRequest;
    if (kind === "CHARACTER") {
      input = { kind, title: name, content: { name, description: description.trim(),
        appearance: appearance.trim(), referenceVersionIds: [] } };
    } else if (kind === "SCENE") {
      input = { kind, title: name, content: { name, location: location.trim(),
        timeOfDay: timeOfDay.trim(), lighting: lighting.trim(), style: style.trim(),
        referenceVersionIds: [] } };
    } else {
      if (!sceneVersionId) return;
      if (!Number.isInteger(durationSeconds) || durationSeconds < 1 || durationSeconds > 30) return;
      input = { kind, title: name, content: { order, durationSeconds,
        description: description.trim(), camera: camera.trim(), action: action.trim(),
        characterVersionIds, sceneVersionId } };
    }
    create.mutate(input);
  }

  return <section className="mt-6 border-t border-[var(--line)] pt-5" aria-label="手工分镜">
    <h2 className="text-base font-semibold">手工添加角色、场景与镜头</h2>
    <p className="mt-1 text-xs leading-5 text-[var(--muted)]">
      使用当前精确版本建立引用；保存内容并放到画布，不会启动 Agent 或媒体任务。
    </p>
    <form className="mt-4" onSubmit={submit}>
      <fieldset className="space-y-3" disabled={create.isPending}>
      <label className="block text-sm font-medium">类型
        <select className="mt-1 w-full" value={kind} onChange={(event) =>
          setKind(event.target.value as ManualKind)}>
          <option value="SCENE">场景</option><option value="CHARACTER">角色</option>
          <option value="SHOT">镜头</option>
        </select>
      </label>
      <label className="block text-sm font-medium">标题
        <input className="mt-1 w-full" maxLength={kind === "SHOT" ? 160 : 120} required value={title}
          onChange={(event) => setTitle(event.target.value)} />
      </label>
      {kind !== "SCENE" ? <label className="block text-sm font-medium">描述
        <textarea className="mt-1 w-full" maxLength={4000} required value={description}
          onChange={(event) => setDescription(event.target.value)} />
      </label> : null}
      {kind === "CHARACTER" ? <label className="block text-sm font-medium">外观
        <textarea className="mt-1 w-full" maxLength={4000} required value={appearance}
          onChange={(event) => setAppearance(event.target.value)} />
      </label> : null}
      {kind === "SCENE" ? <>
        <label className="block text-sm font-medium">地点<input className="mt-1 w-full"
          maxLength={500} required value={location} onChange={(event) => setLocation(event.target.value)} /></label>
        <label className="block text-sm font-medium">时间<input className="mt-1 w-full"
          maxLength={80} required value={timeOfDay} onChange={(event) => setTimeOfDay(event.target.value)} /></label>
        <label className="block text-sm font-medium">光线<textarea className="mt-1 w-full"
          maxLength={1000} required value={lighting} onChange={(event) => setLighting(event.target.value)} /></label>
        <label className="block text-sm font-medium">风格<textarea className="mt-1 w-full"
          maxLength={1000} required value={style} onChange={(event) => setStyle(event.target.value)} /></label>
      </> : null}
      {kind === "SHOT" ? <>
        <div className="flex gap-3">
          <label className="block min-w-0 flex-1 text-sm font-medium">顺序（1–6）
            <input className="mt-1 w-full" type="number" min={1} max={6} required value={order}
              onChange={(event) => setOrder(Number(event.target.value))} /></label>
          <label className="block min-w-0 flex-1 text-sm font-medium">时长（秒）
            <input className="mt-1 w-full" type="number" min={1} max={30} step={1} required
              value={durationSeconds} onChange={(event) => setDurationSeconds(Number(event.target.value))} /></label>
        </div>
        <label className="block text-sm font-medium">镜头语言<input className="mt-1 w-full"
          maxLength={1000} required value={camera} onChange={(event) => setCamera(event.target.value)} /></label>
        <label className="block text-sm font-medium">动作<textarea className="mt-1 w-full"
          maxLength={2000} required value={action} onChange={(event) => setAction(event.target.value)} /></label>
        <label className="block text-sm font-medium">场景精确版本
          <select className="mt-1 w-full" required value={sceneVersionId}
            onChange={(event) => setSceneVersionId(event.target.value)}>
            <option value="">选择场景</option>
            {scenes.map((scene) => <option key={scene.id} value={scene.currentVersionId}>
              {scene.title} · v{scene.currentVersion.versionNo}</option>)}
          </select>
        </label>
        {characters.length > 0 ? <fieldset className="text-sm">
          <legend className="font-medium">角色精确版本（可选）</legend>
          {characters.map((character) => <label className="mt-1 flex gap-2" key={character.id}>
            <input type="checkbox" checked={characterVersionIds.includes(character.currentVersionId)}
              onChange={(event) => setCharacterVersionIds((current) => event.target.checked
                ? [...current, character.currentVersionId]
                : current.filter((id) => id !== character.currentVersionId))} />
            {character.title} · v{character.currentVersion.versionNo}
          </label>)}
        </fieldset> : null}
      </> : null}
      <button className="secondary-button w-full" disabled={create.isPending ||
        (kind === "SHOT" && scenes.length === 0)} type="submit">
        {create.isPending ? "保存中…" : `添加${kind === "SHOT" ? "镜头" : kind === "SCENE" ? "场景" : "角色"}到画布`}
      </button>
      </fieldset>
    </form>
    {kind === "SHOT" && scenes.length === 0 ? <p className="mt-2 text-xs text-amber-900">
      请先创建至少一张场景卡片。</p> : null}
    {create.error ? <p className="mt-2 text-xs text-red-800" role="alert">
      {create.error instanceof ApiError ? create.error.message : "创建或放置未完成，请先核对画布后重试。"}
      {progress.current?.artifactId ? "产物已创建；保持表单内容重试可继续放置原产物。" : ""}
    </p> : null}
  </section>;
}
