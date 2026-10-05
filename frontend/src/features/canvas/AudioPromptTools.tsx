import { ArrowsOutSimple,MagicWand,Translate } from "@phosphor-icons/react";
import { useMutation,useQuery,useQueryClient } from "@tanstack/react-query";
import { useRef,useState } from "react";
import {
applyCanvasCommands,createArtifact,getLlmSettings,getSystemDiagnostics,
listArtifactVersions,listCanvasItems,listDirectTextTasks,runDirectTextGeneration,
type Artifact
} from "../../shared/api/client";
import { t,useLocale } from "../../shared/i18n";
import { Dialog } from "../../shared/ui/Dialog";
import { Button } from "../../shared/ui/primitives/button";
import { Textarea } from "../../shared/ui/primitives/textarea";
import { Select } from "../../shared/ui/Select";
import { readContentText } from "./artifactContent";
import "./AudioPromptTools.css";
import { MEDIA_TASK_REFRESH_INTERVAL_MS,isMediaTaskRunning } from "./mediaTaskState";
import { taskErrorDetail } from "./taskErrorMessages";

const MAX_AUDIO_PROMPT_LENGTH = 3000;
const TEXT_NODE_WIDTH = 280;
const TEXT_NODE_HEIGHT = 180;
const TEXT_NODE_GAP = 64;
const SOURCE_FALLBACK_WIDTH = 430;
type Mode = "assist" | "translate" | "expand";
const LABELS: Record<Mode, string> = { get assist() { return t("audio.promptTools.assistantTitle"); }, get translate() { return t("audio.promptTools.translateTitle"); }, get expand() { return t("audio.promptTools.expandLabel"); } };

export function AudioPromptTools({ projectId, canvasItemId, prompt, hasMentions, onApply }: {
  projectId: string; canvasItemId: string; prompt: string; hasMentions: boolean; onApply: (text: string) => void;
}) {
  useLocale();
  const [mode, setMode] = useState<Mode | null>(null);
  return <><span className="audio-prompt-tools">
    <Button variant="ghost" type="button" aria-label={t("audio.promptTools.assistantTitle")} title={t("audio.promptTools.assistant")} onClick={() => setMode("assist")}><MagicWand size={15} /></Button>
    <Button variant="ghost" type="button" aria-label={t("audio.promptTools.translateTitle")} title={t("audio.promptTools.translate")} onClick={() => setMode("translate")}><Translate size={15} /></Button>
    <Button variant="ghost" type="button" aria-label={t("audio.promptTools.expandLabel")} title={t("audio.promptTools.expand")} onClick={() => setMode("expand")}><ArrowsOutSimple size={15} /></Button>
  </span>{mode ? <PromptToolDialog mode={mode} projectId={projectId} canvasItemId={canvasItemId}
    prompt={prompt} hasMentions={hasMentions} onApply={onApply} onClose={() => setMode(null)} /> : null}</>;
}
function PromptToolDialog({ mode, projectId, canvasItemId, prompt, hasMentions, onApply, onClose }: {
  mode: Mode; projectId: string; canvasItemId: string; prompt: string; hasMentions: boolean;
  onApply: (text: string) => void; onClose: () => void;
}) {
  useLocale();
  const [text, setText] = useState(prompt);
  const basis = useRef(prompt).current;
  const [language, setLanguage] = useState("英文");
  const generated = mode === "assist" || mode === "translate";
  const progress = useRef<{ key: string; itemId: string; artifact?: Artifact; placed?: boolean } | null>(null);
  const [target, setTarget] = useState<Artifact | null>(null);
  const client = useQueryClient();
  const settings = useQuery({ queryKey: ["settings", "llm"], queryFn: getLlmSettings, enabled: generated });
  const diagnostics = useQuery({ queryKey: ["settings", "diagnostics"], queryFn: getSystemDiagnostics, enabled: generated });
  const run = useMutation({ mutationFn: async () => {
    progress.current ??= { key: crypto.randomUUID(), itemId: crypto.randomUUID() };
    const pending = progress.current;
    pending.artifact ??= await createArtifact(projectId, { kind: "TEXT", title: LABELS[mode],
      content: { format: "PLAIN_TEXT", text: "" } }, pending.key);
    if (!pending.placed) {
      const canvas = await listCanvasItems(projectId);
      const source = canvas.items.find((item) => item.id === canvasItemId);
      await applyCanvasCommands(projectId, [{ type: "PLACE_ARTIFACT", artifactId: pending.artifact.id,
        itemId: pending.itemId, x: (source?.x ?? 0) + (source?.width ?? SOURCE_FALLBACK_WIDTH) + TEXT_NODE_GAP, y: (source?.y ?? 0) + TEXT_NODE_WIDTH,
        width: TEXT_NODE_WIDTH, height: TEXT_NODE_HEIGHT, zIndex: canvas.items.length, locked: false }]);
      pending.placed = true;
    }
    if (!pending.artifact.resourceDefaultVersionId) throw new Error(t("audio.promptTools.initialVersionMissing"));
    const instruction = mode === "translate"
      ? `把下列音频生成提示词翻译为${language}，保留对白、声音、情绪与音效细节。`
      : "将下列意图改写为清晰的 Seed Audio 声音生成提示词，保留原意，补充声音、对白、情绪、节奏与环境音描述。";
    await runDirectTextGeneration(projectId, pending.artifact.id, { prompt: `${instruction}只输出提示词，最多 3000 字符。你只处理文字，不分析或声称听过音频。\n\n${text}`,
      expectedArtifactVersion: pending.artifact.version, expectedCurrentVersionId: pending.artifact.resourceDefaultVersionId }, pending.key);
    setTarget(pending.artifact);
    await client.invalidateQueries({ queryKey: ["canvas", projectId] });
  } });
  const tasks = useQuery({ queryKey: ["direct-text-tasks", projectId, target?.id],
    queryFn: () => listDirectTextTasks(projectId, target!.id), enabled: Boolean(target),
    refetchInterval: (query) => !query.state.data?.length || query.state.data.some(isMediaTaskRunning) ? MEDIA_TASK_REFRESH_INTERVAL_MS : false });
  const task = tasks.data?.[0];
  const versions = useQuery({ queryKey: ["artifact-versions", projectId, target?.id, task?.output?.artifactVersionId],
    queryFn: () => listArtifactVersions(projectId, target!.id), enabled: task?.status === "SUCCEEDED" });
  const result = versions.data?.items.find((version) => version.id === task?.output?.artifactVersionId);
  const output = readContentText(result?.content, "text");
  const available = settings.data?.configured || diagnostics.data?.llmMode === "MOCK";
  const applyText = generated ? output : text;
  return <Dialog title={LABELS[mode]} onClose={onClose} busy={run.isPending}
    description={generated ? t("audio.promptTools.modelUsageHint") : t("audio.promptTools.applyHint")}
    onSubmit={(event) => { event.preventDefault(); if (applyText?.trim() && applyText.length <= MAX_AUDIO_PROMPT_LENGTH) { onApply(applyText); onClose(); } }}
    footer={<><Button variant="outline" type="button"  onClick={onClose} disabled={run.isPending}>{t("common.close")}</Button>
      {generated ? <Button variant="outline" type="button"  disabled={!text.trim() || !available || Boolean(target) || run.isPending || hasMentions}
        onClick={() => run.mutate()}>{run.isPending ? t("audio.promptTools.submitting") : run.error ? t("audio.promptTools.retry") : t("audio.promptTools.generate")}</Button> : null}
      <Button variant="default" type="submit"  disabled={hasMentions || prompt !== basis || !applyText?.trim() || applyText.length > MAX_AUDIO_PROMPT_LENGTH}>{t("audio.promptTools.apply")}</Button></>}>
    {mode === "translate" ? <label>{t("audio.promptTools.targetLanguage")}<Select variant="ghost" value={language} onChange={(event) => setLanguage(event.target.value)} disabled={Boolean(target) || run.isPending}><option value="英文">{t("audio.promptTools.english")}</option><option value="中文">{t("common.chinese")}</option></Select></label> : null}
    <label>{t("audio.promptTools.prompt")}<Textarea className="audio-prompt-expanded" aria-label={t("audio.promptTools.fullPrompt")} maxLength={MAX_AUDIO_PROMPT_LENGTH}
      value={text} disabled={Boolean(target) || run.isPending || hasMentions} onChange={(event) => { if (!run.isPending) { progress.current = null; run.reset(); setText(event.target.value); } }} /></label>
    {prompt !== basis ? <p role="alert">{t("audio.promptTools.conflict")}</p> : null}
    {hasMentions ? <p role="alert">{t("audio.promptTools.mentionsHint")}</p> : null}
    {generated && !settings.data?.configured && diagnostics.data?.llmMode === "MOCK" ? <p>{t("audio.promptTools.mockTextHint")}</p> : null}
    {generated && !available ? <p>{t("audio.promptTools.modelSetupHint")}</p> : null}
    {run.error ? <p role="alert">{t("audio.promptTools.submitFailed", { "0": run.error.message })}</p> : null}
    {tasks.error || versions.error ? <p role="alert">{t("audio.promptTools.resultUnavailable")}</p> : null}
    {task && task.status !== "SUCCEEDED" ? <p role="status">{isMediaTaskRunning(task) ? t("audio.promptTools.generatingHint") : taskErrorDetail(task.errorCode)}</p> : null}
    {output ? <label>{t("audio.promptTools.result")}<Textarea className="audio-prompt-expanded" aria-label={t("audio.promptTools.generatedPrompt")} readOnly value={output} />
      {output.length > MAX_AUDIO_PROMPT_LENGTH ? <p role="alert">{t("audio.promptTools.resultTooLong")}</p> : null}</label> : null}
  </Dialog>;
}
