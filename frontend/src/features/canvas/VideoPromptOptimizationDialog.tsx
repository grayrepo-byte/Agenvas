import { MagicWand } from "@/shared/ui/icons";
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useId, useRef, useState } from "react";
import { ApiError, cancelVideoPromptOptimization, listSkills, listSkillVersions, listVideoPromptTasks, runVideoPromptOptimization,
  type CreativeSkill, type SkillCategory, type Task, type VideoPromptOptimizationRequest } from "../../shared/api/client";
import { t, useLocale } from "../../shared/i18n";
import { LoadingState } from "../../shared/ui/LoadingState";
import { Select } from "../../shared/ui/Select";
import { Alert, AlertDescription } from "../../shared/ui/primitives/alert";
import { Button } from "../../shared/ui/primitives/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "../../shared/ui/primitives/dialog";
import { Field, FieldDescription, FieldGroup, FieldLabel } from "../../shared/ui/primitives/field";
import { Textarea } from "../../shared/ui/primitives/textarea";
import { Input } from "../../shared/ui/primitives/input";
import { SkillCategoryFilter } from "../skills/SkillCategoryFilter";
import { skillCatalogueGroups } from "../skills/skillCategories";
import { isMediaTaskRunning, MEDIA_TASK_REFRESH_INTERVAL_MS } from "./mediaTaskState";
import { taskErrorMessage } from "./taskErrorMessages";

/** Closing only hides durable work; refreshing the history never submits a new model call. */
export function VideoPromptOptimizationDialog({ projectId, canvasItemId, prompt, maxLength, onApply, onClose }: {
  projectId: string; canvasItemId: string; prompt: string; maxLength: number;
  onApply: (prompt: string) => boolean; onClose: () => void;
}) {
  useLocale();
  const id = useId();
  const client = useQueryClient();
  const queryKey = ["video-prompt-tasks", projectId, canvasItemId] as const;
  const [skillId, setSkillId] = useState("");
  const [versionId, setVersionId] = useState("");
  const [category, setCategory] = useState<SkillCategory>();
  const [search, setSearch] = useState("");
  const [chosenSkill, setChosenSkill] = useState<CreativeSkill>();
  const [edited, setEdited] = useState<{ taskId: string; value: string } | null>(null);
  const [applyError, setApplyError] = useState(false);
  const command = useRef<{ key: string; request: VideoPromptOptimizationRequest } | null>(null);
  const history = useQuery({ queryKey, queryFn: () => listVideoPromptTasks(projectId, canvasItemId), retry: false,
    refetchInterval: (query) => query.state.data?.some(isMediaTaskRunning) ? MEDIA_TASK_REFRESH_INTERVAL_MS : false });
  const skills = useInfiniteQuery({ queryKey: ["skills", "video-prompt-picker", search, category], initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) => listSkills(search, pageParam, false, category), getNextPageParam: (page) => page.nextCursor ?? undefined, retry: false });
  const choices = skills.data?.pages.flatMap((page) => page.items).filter((skill) => !skill.trashed && skill.currentVersionId) ?? [];
  const versions = useQuery({ queryKey: ["skill-versions", skillId], queryFn: () => listSkillVersions(skillId), enabled: !!skillId, retry: false });
  const selectedSkill = choices.find((skill) => skill.id === skillId) ?? chosenSkill;
  const selectedVersionId = versionId || selectedSkill?.currentVersionId || "";
  const active = history.data?.find(isMediaTaskRunning);
  const latest = history.data?.find((task) => task.input.prompt === prompt);
  const output = latest?.status === "SUCCEEDED" ? latest.output : undefined;
  const generated = typeof output?.prompt === "string" ? output.prompt : "";
  const value = latest && edited?.taskId === latest.id ? edited.value : generated;
  function updateTask(task: Task) {
    client.setQueryData<Task[]>(queryKey, (old) => [task, ...(old ?? []).filter((entry) => entry.id !== task.id)]);
  }
  const run = useMutation({ mutationFn: () => {
    command.current ??= { key: crypto.randomUUID(), request: { prompt, maxLength,
      skill: skillId ? { skillId, skillVersionId: selectedVersionId } : null } };
    return runVideoPromptOptimization(projectId, canvasItemId, command.current.request, command.current.key);
  }, onSuccess: (task) => { command.current = null; updateTask(task); }, onError: (error) => {
    if (error instanceof ApiError && error.status < 500 && error.status !== 408) command.current = null;
    if (error instanceof ApiError && error.code === "VIDEO_PROMPT_IN_PROGRESS") void history.refetch();
  } });
  const cancel = useMutation({ mutationFn: (task: Task) => cancelVideoPromptOptimization(projectId, canvasItemId, task.id),
    onSuccess: updateTask, onSettled: () => void history.refetch() });
  const uncertain = run.isError && (!(run.error instanceof ApiError) || run.error.status >= 500 || run.error.status === 408);
  const busy = Boolean(active) || run.isPending || cancel.isPending;
  const error = history.error ?? run.error ?? cancel.error;
  const failed = latest && ["FAILED", "BLOCKED", "UNKNOWN"].includes(latest.status);

  return <Dialog open onOpenChange={(open) => { if (!open) onClose(); }}>
    <DialogContent className="nodrag nowheel nopan max-h-[calc(100dvh-2rem)] overflow-y-auto sm:max-w-2xl" showCloseButton={false}
      onPointerDown={(event) => event.stopPropagation()} onClick={(event) => event.stopPropagation()}
      onKeyDown={(event) => event.stopPropagation()} onWheel={(event) => event.stopPropagation()}>
      <DialogHeader><DialogTitle>{t("video.optimize.title")}</DialogTitle><DialogDescription>{t("video.optimize.hint")}</DialogDescription></DialogHeader>
      <FieldGroup>
        <Field><FieldLabel htmlFor={`${id}-source`}>{t("video.optimize.source")}</FieldLabel>
          <Textarea id={`${id}-source`} value={prompt} readOnly rows={3} className="max-h-40" /></Field>
        <Field><FieldLabel className="sr-only" htmlFor={`${id}-skill-search`}>{t("skills.search")}</FieldLabel>
          <Input id={`${id}-skill-search`} type="search" placeholder={t("skills.search")} value={search} disabled={busy || uncertain} onChange={(event)=>setSearch(event.target.value)} /></Field>
        <SkillCategoryFilter value={category} onChange={setCategory} disabled={busy || uncertain} />
        <Field><FieldLabel htmlFor={`${id}-skill`}>{t("video.optimize.skill")}</FieldLabel>
          <Select id={`${id}-skill`} value={skillId} disabled={busy || uncertain || skills.isPending}
            onChange={(event) => { setSkillId(event.target.value); setVersionId(""); setChosenSkill(choices.find((skill)=>skill.id===event.target.value)); }}>
            <option value="">{t("video.optimize.noSkill")}</option>
            {chosenSkill && !choices.some((skill)=>skill.id===chosenSkill.id) ? <option value={chosenSkill.id}>{chosenSkill.title}</option> : null}
            {skillCatalogueGroups(choices).map((group)=><optgroup key={group.label} label={group.label}>{group.items.map((skill)=><option key={skill.id} value={skill.id}>{skill.title}</option>)}</optgroup>)}
          </Select><FieldDescription>{t("video.optimize.textOnlySkill")}</FieldDescription></Field>
        {skills.isPending ? <LoadingState compact label={t("common.loading")} /> : null}
        {skills.isSuccess && !choices.length ? <p role="status">{t("video.optimize.noPublishedSkills")}</p> : null}
        {skills.error ? <Alert variant="destructive"><AlertDescription>{skills.error.message}<Button variant="outline" onClick={() => void skills.refetch()}>{t("common.retryRead")}</Button></AlertDescription></Alert> : null}
        {skills.hasNextPage ? <Button variant="outline" disabled={skills.isFetchingNextPage || busy || uncertain} onClick={() => void skills.fetchNextPage()}>{t("projects.loadMore")}</Button> : null}
        {selectedSkill ? <Field><FieldLabel htmlFor={`${id}-version`}>{t("skills.versions")}</FieldLabel>
          <Select id={`${id}-version`} value={selectedVersionId} disabled={busy || uncertain || versions.isPending || versions.isError}
            onChange={(event) => setVersionId(event.target.value)}>
            {!versions.data?.some((version) => version.id === selectedVersionId) ? <option value={selectedVersionId}>{t("video.optimize.currentVersion")}</option> : null}
            {versions.data?.map((version) => <option key={version.id} value={version.id}>{t("skills.version", { "0": version.versionNumber })}</option>)}
          </Select><FieldDescription>{selectedSkill.description}</FieldDescription></Field> : null}
        {skillId && versions.isPending ? <LoadingState compact label={t("common.loading")} /> : null}
        {skillId && versions.error ? <Alert variant="destructive"><AlertDescription>{versions.error.message}<Button variant="outline" onClick={() => void versions.refetch()}>{t("common.retryRead")}</Button></AlertDescription></Alert> : null}
        {generated ? <Field><FieldLabel htmlFor={`${id}-result`}>{t("video.optimize.result")}</FieldLabel>
          {typeof latest?.input.skillName === "string" && typeof latest.input.skillVersionNumber === "number"
            ? <FieldDescription>{latest.input.skillName} · {t("skills.version", { "0": latest.input.skillVersionNumber })}</FieldDescription> : null}
          <Textarea id={`${id}-result`} rows={8} className="max-h-72 resize-y" value={value} maxLength={maxLength}
            onChange={(event) => { if (latest) setEdited({ taskId: latest.id, value: event.target.value }); setApplyError(false); }} /></Field> : null}
      </FieldGroup>
      {output?.mock === true ? <p role="status">{t("image.reversePrompt.mock")}</p> : null}
      {history.isPending || active ? <LoadingState compact label={active?.status === "READY" ? t("tasks.status.queued") : active ? t("video.optimize.running") : t("common.loading")} /> : null}
      {latest?.status === "CANCELED" ? <p role="status">{t("video.optimize.canceled")}</p> : null}
      {failed ? <Alert variant="destructive"><AlertDescription>{taskErrorMessage(latest.errorCode) || t("video.optimize.failed")}</AlertDescription></Alert> : null}
      {error ? <Alert variant="destructive"><AlertDescription>{uncertain ? t("video.optimize.uncertain") : error.message}
        {history.isError ? <Button variant="outline" onClick={() => void history.refetch()}>{t("common.retryRead")}</Button> : null}
      </AlertDescription></Alert> : null}
      {applyError ? <Alert variant="destructive"><AlertDescription>{t("video.optimize.applyFailed")}</AlertDescription></Alert> : null}
      <DialogFooter className="flex-wrap">
        <Button variant="outline" onClick={onClose}>{t("common.close")}</Button>
        {active ? <Button variant="outline" disabled={cancel.isPending} onClick={() => cancel.mutate(active)}>{t("common.cancel")}</Button> : null}
        {generated ? <Button variant="outline" disabled={busy || uncertain || !value.trim() || value.length > maxLength}
          onClick={() => { if (!onApply(value)) setApplyError(true); }}>{t("video.optimize.apply")}</Button> : null}
        <Button disabled={busy || history.isPending || history.isError || !prompt.trim() || (!uncertain && !!skillId && (!selectedVersionId || versions.isPending || versions.isError))}
          onClick={() => { setApplyError(false); cancel.reset(); run.mutate(); }}>
          <MagicWand data-icon="inline-start" />{run.isPending ? t("image.reversePrompt.submitting") : uncertain ? t("common.retry") : latest ? t("video.optimize.again") : t("video.optimize.start")}
        </Button>
      </DialogFooter>
    </DialogContent>
  </Dialog>;
}
