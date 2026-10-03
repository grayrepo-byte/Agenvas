import { BookOpen,CaretDown } from "@phosphor-icons/react";
import { useInfiniteQuery,useMutation,useQuery,useQueryClient,type InfiniteData } from "@tanstack/react-query";
import { useEffect,useRef,useState } from "react";
import { getAgentSkillBinding,getSkillInstallation,getSkillVersion,installAgentSkill,listArtifacts,listSkills,listSkillVersions,saveAgentSkillBinding,
  type Agent,type SkillInstallation,type SkillSelection } from "../../shared/api/client";
import { t,useLocale } from "../../shared/i18n";
import { Dialog } from "../../shared/ui/Dialog";
import { Input } from "../../shared/ui/primitives/input";
import { LoadingState } from "../../shared/ui/LoadingState";
import { EmptyState,Notice,Panel } from "../../shared/ui/PagePrimitives";
import { Select } from "../../shared/ui/Select";
import { Button } from "../../shared/ui/primitives/button";
import { Field,FieldGroup,FieldLabel } from "../../shared/ui/primitives/field";
import { SkillError } from "./SkillsPage";
import "./Skills.css";

const INSTALL_POLL_MS = 1500;
const pendingInstallation = (installation?:SkillInstallation)=>installation?.status==="ACCEPTED" || installation?.status==="PREPARING" || installation?.status==="CLEANING";
function initialSelection(agentId:string):SkillSelection {
  const params=new URLSearchParams(window.location.search);
  const skillId=params.get("skillId"),skillVersionId=params.get("skillVersionId");
  return params.get("agentId")===agentId && skillId && skillVersionId ? {mode:"VERSION",skillId,skillVersionId,inputs:[]} : {mode:"NONE",inputs:[]};
}
export function useAgentSkillSelection(projectId:string,agent:Agent | null | undefined,initial?:SkillSelection) {
  const client=useQueryClient();
  const [selection,setSelection]=useState<SkillSelection>(()=>initial??initialSelection(agent?.id??""));
  const binding=useQuery({queryKey:["agent-skill-binding",projectId,agent?.id,agent?.version],queryFn:()=>getAgentSkillBinding(projectId,agent!.id),enabled:Boolean(agent)});
  const selectedSkillId=selection.mode==="VERSION" ? selection.skillId : selection.mode==="DEFAULT" ? binding.data?.skillId : null;
  const selectedVersionId=selection.mode==="VERSION" ? selection.skillVersionId : selection.mode==="DEFAULT" ? binding.data?.skillVersionId : null;
  const version=useQuery({queryKey:["skill-version",selectedSkillId,selectedVersionId],queryFn:()=>getSkillVersion(selectedSkillId!,selectedVersionId!),enabled:Boolean(selectedSkillId && selectedVersionId)});
  const [operation,setOperation]=useState<SkillInstallation | null>(null);
  const installation=useQuery({queryKey:["skill-installation",projectId,agent?.id,operation?.id],queryFn:()=>getSkillInstallation(projectId,agent!.id,operation!.id),enabled:Boolean(agent && operation),
    refetchInterval:(query)=>pendingInstallation(query.state.data ?? operation ?? undefined) ? INSTALL_POLL_MS : false});
  const progress=installation.data ?? operation;
  const install=useMutation({mutationFn:()=>installAgentSkill(projectId,agent!.id,selectedSkillId!,selectedVersionId!,crypto.randomUUID()),onSuccess:(saved)=>{
    setOperation(saved);void client.invalidateQueries({queryKey:["run-preflight",projectId,agent?.id]});
  }});
  const currentProgress=progress?.skillVersionId===selectedVersionId ? progress : null;
  const inputSlots=version.data?.inputSlots??[];
  const missingInputs=inputSlots.some((slot)=>slot.required && !selection.inputs?.some((input)=>input.alias===slot.alias));
  const selectionPending=selection.mode==="DEFAULT" ? binding.isPending : selection.mode==="VERSION" && !selectedVersionId;
  const fingerprint=JSON.stringify({selection,bindingVersion:selection.mode==="DEFAULT" ? binding.data?.agentVersion : null,bundleHash:version.data?.bundleHash});
  return {selection,setSelection,binding,version,selectedSkillId,selectedVersionId,install,installation,progress,inputSlots,missingInputs,
    ready:!selectionPending && (selection.mode!=="DEFAULT" || !binding.isError) && !version.isFetching && !version.isError && !missingInputs && !pendingInstallation(currentProgress??undefined),fingerprint};
}
export type AgentSkillState=ReturnType<typeof useAgentSkillSelection>;

export function SkillVersionPicker({skillId,versionId,onChange,disabled=false}:{skillId?:string | null;versionId?:string | null;onChange:(skillId:string,versionId:string)=>void;disabled?:boolean}) {
  useLocale();
  const skills=useInfiniteQuery({queryKey:["skills","published-picker"],initialPageParam:undefined as string | undefined,queryFn:({pageParam})=>listSkills("",pageParam),getNextPageParam:(page)=>page.nextCursor??undefined});
  const versions=useQuery({queryKey:["skill-versions",skillId],queryFn:()=>listSkillVersions(skillId!),enabled:Boolean(skillId)});
  return <FieldGroup><Field><FieldLabel>{t("skills.choose")}</FieldLabel><Select aria-label={t("skills.choose")} value={skillId??""} disabled={disabled} onChange={(event)=>onChange(event.target.value,"")}><option value="">{t("skills.choose")}</option>{skills.data?.pages.flatMap((page)=>page.items).filter((skill)=>skill.currentVersionId).map((skill)=><option key={skill.id} value={skill.id}>{skill.title}</option>)}</Select></Field>
    <Field><FieldLabel>{t("skills.versions")}</FieldLabel><Select aria-label={t("skills.versions")} value={versionId??""} disabled={disabled || !skillId || versions.isPending} onChange={(event)=>onChange(skillId!,event.target.value)}><option value="">{t("skills.noVersion")}</option>{versions.data?.map((version)=><option key={version.id} value={version.id}>{t("skills.version",{"0":version.versionNumber})}</option>)}</Select></Field>
    {skills.hasNextPage ? <Button variant="outline" disabled={skills.isFetchingNextPage} onClick={()=>void skills.fetchNextPage()}>{t("projects.loadMore")}</Button> : null}
    {skills.error || versions.error ? <SkillError error={(skills.error??versions.error)!} onRefresh={()=>{void skills.refetch();if(skillId)void versions.refetch();}} /> : null}
  </FieldGroup>;
}
export function AgentSkillSettings({projectId,agent,state,onChanged}:{projectId:string;agent:Agent;state:AgentSkillState;onChanged:()=>void}) {
  const client=useQueryClient();
  const [choice,setChoice]=useState({skillId:"",versionId:""});
  const [dirty,setDirty]=useState(false);
  const baseAgentVersion=useRef(agent.version);
  useEffect(()=>{if(state.binding.data && !dirty)setChoice({skillId:state.binding.data.skillId??"",versionId:state.binding.data.skillVersionId??""});},[state.binding.data,dirty]);
  const intent=useRef<{fingerprint:string;key:string} | null>(null);
  const save=useMutation({mutationFn:(clear:boolean)=>{
    const request={expectedAgentVersion:dirty ? baseAgentVersion.current : (state.binding.data?.agentVersion ?? agent.version),skillId:clear ? null:choice.skillId,skillVersionId:clear ? null:choice.versionId};
    const fingerprint=JSON.stringify(request);
    if(intent.current?.fingerprint!==fingerprint)intent.current={fingerprint,key:crypto.randomUUID()};
    return saveAgentSkillBinding(projectId,agent.id,request,intent.current.key);
  },
    onSuccess:(saved)=>{intent.current=null;setDirty(false);client.setQueryData(["agent-skill-binding",projectId,agent.id,agent.version],saved);void client.invalidateQueries({queryKey:["agent-skill-binding",projectId,agent.id]});void client.invalidateQueries({queryKey:["canvas",projectId]});void client.invalidateQueries({queryKey:["snapshot",projectId]});onChanged();}});
  return <section className="agent-skill-settings" aria-label={t("skills.default")}><p>{t("skills.bindingHint")}</p>
    {state.binding.isPending ? <LoadingState label={t("common.loading")} compact /> : null}
    <SkillVersionPicker skillId={choice.skillId} versionId={choice.versionId} disabled={save.isPending} onChange={(skillId,versionId)=>{if(!dirty)baseAgentVersion.current=state.binding.data?.agentVersion ?? agent.version;setChoice({skillId,versionId});setDirty(true);}} />
    <div className="ui-form-actions"><Button type="button" disabled={!dirty || !choice.versionId || save.isPending || state.binding.isFetching || state.binding.isError} onClick={()=>save.mutate(false)}>{t("skills.saveBinding")}</Button><Button variant="outline" type="button" disabled={!state.binding.data?.skillVersionId || save.isPending || state.binding.isFetching || state.binding.isError} onClick={()=>save.mutate(true)}>{t("skills.clear")}</Button></div>
    {state.binding.error || save.error ? <SkillError error={(state.binding.error??save.error)!} onRefresh={()=>{
      void state.binding.refetch().then((latest)=>{
        if (!latest.error && latest.data) {
          // Only an explicit refresh advances a dirty edit's CAS baseline; keep its selection.
          baseAgentVersion.current=latest.data.agentVersion;intent.current=null;save.reset();
        }
      });
      void client.invalidateQueries({queryKey:["canvas",projectId]});
    }} /> : null}
  </section>;
}
/** The composer keeps only a compact entry; cancelled modal changes never alter a Run. */
export function AgentRunSkillControls({projectId,agent,state,onChanged}:{projectId:string;agent:Agent;state:AgentSkillState;onChanged:()=>void}) {
  useLocale();
  const client=useQueryClient();
  const [open,setOpen]=useState(false);
  const catalog=client.getQueryData<InfiniteData<Awaited<ReturnType<typeof listSkills>>>>(["skills","published-picker"]);
  const name=catalog?.pages.flatMap((page)=>page.items).find((skill)=>skill.id===state.selectedSkillId)?.title ?? state.version.data?.name;
  return <>
    <Button variant="ghost" size="xs" className="agent-skill-trigger" type="button"
      aria-label={t("skills.choose")} aria-haspopup="dialog" aria-expanded={open} title={name??t("skills.choose")}
      onClick={()=>setOpen(true)}>
      <BookOpen data-icon="inline-start" />
      <span>{state.selectedVersionId && name ? name : t("skills.entry")}</span><CaretDown data-icon="inline-end" />
    </Button>
    {open ? <AgentSkillPickerDialog projectId={projectId} agent={agent} selection={state.selection}
      onClose={()=>setOpen(false)} onSelect={(selection)=>{
        state.setSelection(selection);onChanged();setOpen(false);
      }} /> : null}
  </>;
}

function AgentSkillPickerDialog({projectId,agent,selection,onClose,onSelect}:{projectId:string;agent:Agent;
  selection:SkillSelection;onClose:()=>void;onSelect:(selection:SkillSelection)=>void}) {
  useLocale();
  const state=useAgentSkillSelection(projectId,agent,selection);
  const [search,setSearch]=useState("");
  const skills=useInfiniteQuery({queryKey:["skills","published-picker"],initialPageParam:undefined as string | undefined,
    queryFn:({pageParam})=>listSkills("",pageParam),getNextPageParam:(page)=>page.nextCursor??undefined});
  const versions=useQuery({queryKey:["skill-versions",state.selectedSkillId],queryFn:()=>listSkillVersions(state.selectedSkillId!),enabled:Boolean(state.selectedSkillId)});
  const resources=useQuery({queryKey:["artifacts",projectId],queryFn:()=>listArtifacts(projectId),enabled:state.inputSlots.length>0});
  const items=(skills.data?.pages.flatMap((page)=>page.items)??[]).filter((skill)=>skill.currentVersionId && !skill.trashed
    && `${skill.title}\n${skill.description}`.toLocaleLowerCase().includes(search.trim().toLocaleLowerCase()));
  const currentProgress=state.progress?.skillVersionId===state.selectedVersionId ? state.progress : null;
  const busy=state.install.isPending || pendingInstallation(currentProgress??undefined);
  const selected=state.version.data;
  const title=skills.data?.pages.flatMap((page)=>page.items).find((skill)=>skill.id===state.selectedSkillId)?.title ?? selected?.name;
  function choose(skillId:string,skillVersionId:string) {state.setSelection({mode:"VERSION",skillId,skillVersionId,inputs:[]});}
  return <Dialog title={t("skills.choose")} description={t("skills.pickerHint")} className="agent-skill-picker"
    busy={busy} onClose={onClose} onSubmit={(event)=>{event.preventDefault();event.stopPropagation();if(state.ready && selected && !resources.isError)onSelect(state.selection);}}
    footer={<>
      <Button variant="outline" type="button" disabled={busy} onClick={()=>onSelect({mode:"NONE",inputs:[]})}>{t("skills.none")}</Button>
      <Button variant="outline" type="button" disabled={busy} onClick={onClose}>{t("common.cancel")}</Button>
      <Button type="submit" disabled={busy || !state.ready || !selected || resources.isError}>{t("skills.use")}</Button>
    </>}>
    <div className="agent-skill-picker-toolbar">
      <Input type="search" aria-label={t("skills.search")} placeholder={t("skills.search")} value={search}
        disabled={busy} onChange={(event)=>setSearch(event.target.value)} />
      {state.binding.data?.skillId && state.binding.data.skillVersionId ? <Button variant="outline" type="button" disabled={busy}
        onClick={()=>choose(state.binding.data!.skillId!,state.binding.data!.skillVersionId!)}>{t("skills.defaultMode")}</Button> : null}
    </div>
    {skills.isPending ? <LoadingState compact label={t("common.loading")} /> : null}
    {skills.isSuccess && !items.length ? <EmptyState icon={<BookOpen />} title={t("skills.empty")} description={t("skills.emptyHint")} /> : null}
    <div className="agent-skill-picker-grid">
      {items.map((skill)=><Button key={skill.id} variant="outline" type="button" className="agent-skill-picker-card"
        aria-label={skill.title} aria-pressed={state.selectedSkillId===skill.id} disabled={busy}
        onClick={()=>choose(skill.id,skill.currentVersionId!)}>
        <BookOpen data-icon="inline-start" /><strong>{skill.title}</strong><span>{skill.description}</span>
      </Button>)}
    </div>
    {skills.hasNextPage ? <Button variant="outline" type="button" disabled={busy || skills.isFetchingNextPage}
      onClick={()=>void skills.fetchNextPage()}>{t("projects.loadMore")}</Button> : null}
    {state.version.isFetching ? <LoadingState compact label={t("common.loading")} /> : null}
    {selected ? <Panel title={title??selected.name} description={selected.description} className="agent-skill-picker-preview">
      <FieldGroup><Field><FieldLabel>{t("skills.versions")}</FieldLabel>
        <Select aria-label={t("skills.versions")} value={state.selectedVersionId??""} disabled={busy || versions.isFetching}
          onChange={(event)=>choose(state.selectedSkillId!,event.target.value)}>
          {/* A retained default binding can refer to a trashed Skill outside the new-choice list. */}
          {!versions.data?.some((version)=>version.id===selected.id) ? <option value={selected.id}>{t("skills.version",{"0":selected.versionNumber})}</option> : null}
          {versions.data?.map((version)=><option key={version.id} value={version.id}>{t("skills.version",{"0":version.versionNumber})}</option>)}
        </Select></Field>
        {state.inputSlots.map((slot)=><Field key={slot.alias}><FieldLabel>{slot.alias}{slot.required ? ` · ${t("skills.required")}`:""}</FieldLabel>
          <Select aria-label={`${slot.alias} · ${t("skills.inputVersion")}`} disabled={busy || resources.isFetching}
            value={state.selection.inputs?.find((input)=>input.alias===slot.alias)?.artifactVersionId??""} onChange={(event)=>{
              const inputs=(state.selection.inputs??[]).filter((input)=>input.alias!==slot.alias);
              if(event.target.value)inputs.push({alias:slot.alias,artifactVersionId:event.target.value});state.setSelection({...state.selection,inputs});
            }}><option value="">{t("skills.inputVersion")}</option>
            {agent.bindings.filter((binding)=>resources.data?.items.some((artifact)=>artifact.id===binding.artifactId && artifact.kind===slot.kind))
              .map((binding)=><option key={binding.selectedVersionId} value={binding.selectedVersionId}>{resources.data?.items.find((artifact)=>artifact.id===binding.artifactId)?.title}</option>)}
          </Select></Field>)}
      </FieldGroup>
      <details><summary>{t("skills.body")}</summary><pre className="skills-source-preview">{selected.skillMd}</pre></details>
      <p>{t("skills.modelOnly")}</p>
      <Button variant="outline" type="button" disabled={busy} onClick={()=>state.install.mutate()}>{t("skills.install")}</Button>
      {currentProgress ? <Notice tone={currentProgress.status==="FAILED" ? "danger":"info"}>{pendingInstallation(currentProgress) ? t("skills.installing"):currentProgress.status==="SUCCEEDED" ? t("skills.installed"):currentProgress.errorDetail??currentProgress.errorCode}</Notice> : null}
      {state.missingInputs ? <p role="status">{t("skills.missingInput")}</p> : null}
    </Panel> : null}
    {skills.error || versions.error || state.version.error || state.install.error || state.installation.error || resources.error ?
      <SkillError error={(skills.error??versions.error??state.version.error??state.install.error??state.installation.error??resources.error)!}
        onRefresh={()=>{void skills.refetch();if(state.selectedSkillId)void versions.refetch();if(state.selectedVersionId)void state.version.refetch();
          if(state.progress)void state.installation.refetch();if(state.inputSlots.length)void resources.refetch();}} /> : null}
  </Dialog>;
}
export function RunSkillSummary({skill}:{skill:NonNullable<import("../../shared/api/client").RunPreflight["creativeSkill"]>}) {
  return <section aria-label={t("skills.preflight")}><p>{skill.title} · {t("skills.version",{"0":skill.versionNumber})}</p><p>{skill.installed ? t("skills.installed"):t("skills.installing")}</p><ul>{skill.resources.map((resource)=><li key={resource.path}><details><summary>{resource.path} · {resource.contentHash}</summary><pre className="skills-source-preview">{resource.content}</pre></details></li>)}{skill.assets.map((asset)=><li key={asset.alias}>{asset.alias} · {asset.purpose} · {asset.usage==="GUIDE" ? t("skills.guide"):t("skills.reference")}</li>)}</ul><p>{t("skills.fees")}</p></section>;
}
