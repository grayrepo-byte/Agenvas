import { ArrowsOutSimple, Lightbulb, Translate, MagicWand } from "@phosphor-icons/react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useRef, useState } from "react";
import { Dialog } from "../../shared/ui/Dialog";
import { Select } from "../../shared/ui/Select";
import { applyCanvasCommands, createArtifact, getLlmSettings, getSystemDiagnostics,
  listArtifactVersions, listCanvasItems, listDirectTextTasks, runDirectTextGeneration,
  type Artifact } from "../../shared/api/client";
import { readContentText } from "./artifactContent";
import { MEDIA_TASK_REFRESH_INTERVAL_MS, isMediaTaskRunning } from "./mediaTaskState";
import { taskErrorDetail } from "./taskErrorMessages";
import "./AudioPromptTools.css";

const MAX_AUDIO_PROMPT_LENGTH = 3000;
const TEXT_NODE_WIDTH = 280;
const TEXT_NODE_HEIGHT = 180;
const TEXT_NODE_GAP = 64;
const SOURCE_FALLBACK_WIDTH = 430;
const TEMPLATES = [
  { title: "自然对白", text: '用自然亲切的语气说：“你好，欢迎来到创作画布。”语速适中，吐字清晰，尾句轻轻上扬。' },
  { title: "情绪旁白", text: '一位声音温暖沉稳的旁白说：“每一个微小的开始，都有可能成为新的故事。”从容地停顿，情绪由平静转向充满希望。' },
  { title: "环境音", text: '清晨的森林里，鸟鸣此起彼伏，微风轻拂树叶，远处传来潺潺流水声。声音自然，有空间感，没有对白。' },
  { title: "广告配音", text: '用明亮有活力的声音说：“让灵感即刻发生。”语气自信，节奏轻快，最后一句强调品牌感。' },
] as const;
type Mode = "templates" | "assist" | "translate" | "expand";
const LABELS: Record<Mode, string> = { templates: "音频提示词模板", assist: "音频提示词助手", translate: "翻译音频提示词", expand: "展开音频提示词" };

export function AudioPromptTools({ projectId, canvasItemId, prompt, hasMentions, onApply }: {
  projectId: string; canvasItemId: string; prompt: string; hasMentions: boolean; onApply: (text: string) => void;
}) {
  const [mode, setMode] = useState<Mode | null>(null);
  return <><span className="audio-prompt-tools">
    <button type="button" aria-label="音频提示词模板" title="模板" onClick={() => setMode("templates")}><Lightbulb size={15} /></button>
    <button type="button" aria-label="音频提示词助手" title="提示词助手" onClick={() => setMode("assist")}><MagicWand size={15} /></button>
    <button type="button" aria-label="翻译音频提示词" title="翻译" onClick={() => setMode("translate")}><Translate size={15} /></button>
    <button type="button" aria-label="展开音频提示词" title="展开编辑" onClick={() => setMode("expand")}><ArrowsOutSimple size={15} /></button>
  </span>{mode ? <PromptToolDialog mode={mode} projectId={projectId} canvasItemId={canvasItemId}
    prompt={prompt} hasMentions={hasMentions} onApply={onApply} onClose={() => setMode(null)} /> : null}</>;
}
function PromptToolDialog({ mode, projectId, canvasItemId, prompt, hasMentions, onApply, onClose }: {
  mode: Mode; projectId: string; canvasItemId: string; prompt: string; hasMentions: boolean;
  onApply: (text: string) => void; onClose: () => void;
}) {
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
    if (!pending.artifact.resourceDefaultVersionId) throw new Error("文字节点缺少初始版本，请在该节点继续操作。");
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
    description={generated ? "使用文字模型新建独立文字节点，费用计入项目。生成后预览并应用；音频素材不会发送给文字模型。" : "预览并应用到当前音频草稿。"}
    onSubmit={(event) => { event.preventDefault(); if (applyText?.trim() && applyText.length <= MAX_AUDIO_PROMPT_LENGTH) { onApply(applyText); onClose(); } }}
    footer={<><button type="button" className="secondary-button" onClick={onClose} disabled={run.isPending}>关闭</button>
      {generated ? <button type="button" className="secondary-button" disabled={!text.trim() || !available || Boolean(target) || run.isPending || hasMentions}
        onClick={() => run.mutate()}>{run.isPending ? "正在提交…" : run.error ? "重试提交" : "生成"}</button> : null}
      <button type="submit" className="primary-button" disabled={hasMentions || prompt !== basis || !applyText?.trim() || applyText.length > MAX_AUDIO_PROMPT_LENGTH}>应用提示词</button></>}>
    {mode === "templates" ? <div className="audio-prompt-templates">{TEMPLATES.map((template) => <button type="button" className="secondary-button"
      key={template.title} onClick={() => setText(template.text)}>{template.title}</button>)}</div> : null}
    {mode === "translate" ? <label>目标语言<Select value={language} onChange={(event) => setLanguage(event.target.value)} disabled={Boolean(target) || run.isPending}><option>英文</option><option>中文</option></Select></label> : null}
    <label>提示词<textarea className="audio-prompt-expanded" aria-label="完整音频提示词" maxLength={MAX_AUDIO_PROMPT_LENGTH}
      value={text} disabled={Boolean(target) || run.isPending || hasMentions} onChange={(event) => { if (!run.isPending) { progress.current = null; run.reset(); setText(event.target.value); } }} /></label>
    {prompt !== basis ? <p role="alert">当前音频草稿已变化，请关闭窗口后重新编辑。</p> : null}
    {hasMentions ? <p role="alert">提示词含素材标签，请在原编辑区修改，以保留精确引用。</p> : null}
    {generated && !settings.data?.configured && diagnostics.data?.llmMode === "MOCK" ? <p>Mock 文字演示：不会调用真实文字模型。</p> : null}
    {generated && !available ? <p>请在 Provider 配置中设置文字模型；Mock 模式的结果只用于演示。</p> : null}
    {run.error ? <p role="alert">提交失败：{run.error.message}；输入已保留。</p> : null}
    {tasks.error || versions.error ? <p role="alert">结果读取失败，请关闭后在新增文字节点查看。</p> : null}
    {task && task.status !== "SUCCEEDED" ? <p role="status">{isMediaTaskRunning(task) ? "正在生成，关闭窗口后任务继续。" : taskErrorDetail(task.errorCode)}</p> : null}
    {output ? <label>生成结果<textarea className="audio-prompt-expanded" aria-label="生成的音频提示词" readOnly value={output} />
      {output.length > MAX_AUDIO_PROMPT_LENGTH ? <p role="alert">结果超过 3000 字符，请在文字节点精简后复制。</p> : null}</label> : null}
  </Dialog>;
}
