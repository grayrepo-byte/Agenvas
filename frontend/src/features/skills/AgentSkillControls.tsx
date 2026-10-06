import { BookOpen,CaretDown,Check } from "@phosphor-icons/react";
import { useInfiniteQuery,useMutation,useQueries,useQuery,useQueryClient } from "@tanstack/react-query";
import { useEffect,useRef,useState } from "react";
import { getAgentSkillBinding,getSkillInstallation,getSkillVersion,installAgentSkill,listArtifacts,listSkills,listSkillVersions,saveAgentSkillBinding,
  type Agent,type SkillInstallation,type SkillSelection } from "../../shared/api/client";
import { t,useLocale } from "../../shared/i18n";
import { Dialog } from "../../shared/ui/Dialog";
import { Input } from "../../shared/ui/primitives/input";
import { LoadingState } from "../../shared/ui/LoadingState";
import { EmptyState,Notice,Panel,StatusBadge } from "../../shared/ui/PagePrimitives";
import { Select } from "../../shared/ui/Select";
import { Button } from "../../shared/ui/primitives/button";
import { Field,FieldGroup,FieldLabel } from "../../shared/ui/primitives/field";
import { SkillError } from "./SkillsPage";
import "./Skills.css";

const INSTALL_POLL_MS = 1500;
const MAX_SKILLS = 100;
type Choice = SkillSelection["skills"][number];
const pendingInstallation = (installation?:SkillInstallation)=>installation?.status==="ACCEPTED" || installation?.status==="PREPARING" || installation?.status==="CLEANING";
function initialSelection(agentId:string):SkillSelection {
  const params=new URLSearchParams(window.location.search);
  const skillId=params.get("skillId"),skillVersionId=params.get("skillVersionId");
  return params.get("agentId")===agentId && skillId && skillVersionId
    ? {mode:"VERSIONS",skills:[{skillId,skillVersionId,inputs:[]}]} : {mode:"NONE",skills:[]};
}
/** UI selection fixes availability; only the runtime's read_skill tool activates a Skill. */
export function useAgentSkillSelection(projectId:string,agent:Agent | null | undefined,initial?:SkillSelection) {
  const client=useQueryClient();
  const [selection,setSelection]=useState<SkillSelection>(()=>initial??initialSelection(agent?.id??""));
  const binding=useQuery({queryKey:["agent-skill-binding",projectId,agent?.id,agent?.version],queryFn:()=>getAgentSkillBinding(projectId,agent!.id),enabled:Boolean(agent)});
  const choices:Choice[]=selection.mode==="DEFAULT" ? (binding.data?.skills??[]).map((item)=>({...item,inputs:[]})) : selection.skills;
  const versions=useQueries({queries:choices.map((choice)=>({queryKey:["skill-version",choice.skillId,choice.skillVersionId],
    queryFn:()=>getSkillVersion(choice.skillId,choice.skillVersionId)}))});
  const [operations,setOperations]=useState<Record<string,SkillInstallation>>({});
  const installations=useQueries({queries:Object.values(operations).map((operation)=>({
    queryKey:["skill-installation",projectId,agent?.id,operation.id],queryFn:()=>getSkillInstallation(projectId,agent!.id,operation.id),enabled:Boolean(agent),
    refetchInterval:(query:{state:{data:SkillInstallation | undefined}})=>pendingInstallation(query.state.data??operation) ? INSTALL_POLL_MS : false}))});
  const progress=Object.values(operations).map((operation,index)=>installations[index]?.data??operation);
  const install=useMutation({mutationFn:(choice:Choice)=>installAgentSkill(projectId,agent!.id,choice.skillId,choice.skillVersionId,crypto.randomUUID()),onSuccess:(saved)=>{
    setOperations((previous)=>({...previous,[saved.skillVersionId]:saved}));void client.invalidateQueries({queryKey:["run-preflight",projectId,agent?.id]});
  }});
  const missingInputs=choices.some((choice,index)=>versions[index]?.data?.inputSlots.some((slot)=>slot.required && !choice.inputs.some((input)=>input.alias===slot.alias)));
  const busy=install.isPending || progress.some((operation)=>choices.some((choice)=>choice.skillVersionId===operation.skillVersionId) && pendingInstallation(operation));
  const ready=(selection.mode!=="DEFAULT" || (!binding.isPending && !binding.isError))
    && versions.every((version)=>!version.isFetching && !version.isError && Boolean(version.data)) && !missingInputs && !busy;
  const fingerprint=JSON.stringify({selection,bindingVersion:selection.mode==="DEFAULT" ? binding.data?.agentVersion : null,bundleHashes:versions.map((version)=>version.data?.bundleHash)});
  return {selection,setSelection,binding,choices,versions,install,installations,progress,missingInputs,busy,ready,fingerprint};
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
  const [choices,setChoices]=useState([{skillId:"",skillVersionId:""}]);
  const [dirty,setDirty]=useState(false);
  const baseAgentVersion=useRef(agent.version);
  useEffect(()=>{if(state.binding.data && !dirty)setChoices(state.binding.data.skills.length ? state.binding.data.skills : [{skillId:"",skillVersionId:""}]);},[state.binding.data,dirty]);
  const intent=useRef<{fingerprint:string;key:string} | null>(null);
  function changed(next:typeof choices) {
    if(!dirty)baseAgentVersion.current=state.binding.data?.agentVersion ?? agent.version;
    setChoices(next);setDirty(true);
  }
  const save=useMutation({mutationFn:(clear:boolean)=>{
    const request={expectedAgentVersion:dirty ? baseAgentVersion.current : (state.binding.data?.agentVersion ?? agent.version),skills:clear ? []:choices};
    const fingerprint=JSON.stringify(request);
    if(intent.current?.fingerprint!==fingerprint)intent.current={fingerprint,key:crypto.randomUUID()};
    return saveAgentSkillBinding(projectId,agent.id,request,intent.current.key);
  },onSuccess:(saved)=>{intent.current=null;setDirty(false);client.setQueryData(["agent-skill-binding",projectId,agent.id,agent.version],saved);
    void client.invalidateQueries({queryKey:["agent-skill-binding",projectId,agent.id]});void client.invalidateQueries({queryKey:["canvas",projectId]});
    void client.invalidateQueries({queryKey:["snapshot",projectId]});onChanged();}});
  const valid=choices.every((choice)=>choice.skillId && choice.skillVersionId) && new Set(choices.map((choice)=>choice.skillId)).size===choices.length;
  return <section className="agent-skill-settings" aria-label={t("skills.default")}><p>{t("skills.bindingHint")}</p>
    {state.binding.isPending ? <LoadingState label={t("common.loading")} compact /> : null}
    {choices.map((choice,index)=><div key={index}>
      <SkillVersionPicker skillId={choice.skillId} versionId={choice.skillVersionId} disabled={save.isPending} onChange={(skillId,skillVersionId)=>changed(choices.map((item,position)=>position===index ? {skillId,skillVersionId}:item))} />
      {choices.length>1 ? <Button variant="ghost" type="button" disabled={save.isPending} onClick={()=>changed(choices.filter((_,position)=>position!==index))}>{t("skills.removeChoice")}</Button> : null}
    </div>)}
    <Button variant="outline" type="button" disabled={choices.length>=MAX_SKILLS || save.isPending} onClick={()=>changed([...choices,{skillId:"",skillVersionId:""}])}>{t("skills.addChoice")}</Button>
    <div className="ui-form-actions"><Button type="button" disabled={!dirty || !valid || save.isPending || state.binding.isFetching || state.binding.isError} onClick={()=>save.mutate(false)}>{t("skills.saveBinding")}</Button>
      <Button variant="outline" type="button" disabled={!state.binding.data?.skills.length || save.isPending || state.binding.isFetching || state.binding.isError} onClick={()=>save.mutate(true)}>{t("skills.clear")}</Button></div>
    {state.binding.error || save.error ? <SkillError error={(state.binding.error??save.error)!} onRefresh={()=>{
      void state.binding.refetch().then((latest)=>{if(!latest.error && latest.data){baseAgentVersion.current=latest.data.agentVersion;intent.current=null;save.reset();}});
      void client.invalidateQueries({queryKey:["canvas",projectId]});
    }} /> : null}
  </section>;
}
/** Modal edits stay local until applied, including all versions and input mappings. */
export function AgentRunSkillControls({projectId,agent,state,onChanged}:{projectId:string;agent:Agent;state:AgentSkillState;onChanged:()=>void}) {
  useLocale();
  const [open,setOpen]=useState(false);
  const client=useQueryClient();
  const catalog=client.getQueryData<{pages:{items:{id:string;title:string}[]}[]}>(["skills","published-picker"]);
  const names=state.choices.map((choice,index)=>catalog?.pages.flatMap((page)=>page.items).find((skill)=>skill.id===choice.skillId)?.title ?? state.versions[index]?.data?.name).filter(Boolean).join(" · ");
  return <>
    <Button variant="ghost" size="xs" className="agent-skill-trigger" type="button" aria-label={t("skills.choose")} aria-haspopup="dialog"
      aria-expanded={open} title={names || t("skills.choose")} onClick={()=>setOpen(true)}>
      <BookOpen data-icon="inline-start" /><span>{state.choices.length>1 ? t("skills.selectedCount",{"0":state.choices.length}) : names || t("skills.entry")}</span><CaretDown data-icon="inline-end" />
    </Button>
    {open ? <AgentSkillPickerDialog projectId={projectId} agent={agent} selection={state.selection} onClose={()=>setOpen(false)}
      onSelect={(selection)=>{state.setSelection(selection);onChanged();setOpen(false);}} /> : null}
  </>;
}
function AgentSkillPickerDialog({projectId,agent,selection,onClose,onSelect}:{projectId:string;agent:Agent;
  selection:SkillSelection;onClose:()=>void;onSelect:(selection:SkillSelection)=>void}) {
  useLocale();
  const state=useAgentSkillSelection(projectId,agent,selection);
  const [search,setSearch]=useState("");
  const skills=useInfiniteQuery({queryKey:["skills","published-picker"],initialPageParam:undefined as string | undefined,
    queryFn:({pageParam})=>listSkills("",pageParam),getNextPageParam:(page)=>page.nextCursor??undefined});
  const resources=useQuery({queryKey:["artifacts",projectId],queryFn:()=>listArtifacts(projectId),enabled:state.versions.some((query)=>Boolean(query.data?.inputSlots.length))});
  const items=(skills.data?.pages.flatMap((page)=>page.items)??[]).filter((skill)=>skill.currentVersionId && !skill.trashed
    && `${skill.title}\n${skill.description}`.toLocaleLowerCase().includes(search.trim().toLocaleLowerCase()));
  function update(choices:Choice[]) {state.setSelection({mode:choices.length ? "VERSIONS":"NONE",skills:choices});}
  function toggle(skillId:string,skillVersionId:string) {
    const existing=state.choices.some((choice)=>choice.skillId===skillId);
    update(existing ? state.choices.filter((choice)=>choice.skillId!==skillId):[...state.choices,{skillId,skillVersionId,inputs:[]}]);
  }
  const errors=[skills.error,...state.versions.map((query)=>query.error),state.install.error,...state.installations.map((query)=>query.error),resources.error];
  const error=errors.find(Boolean);
  return <Dialog title={t("skills.choose")} description={t("skills.pickerHint")} className="skills-dialog agent-skill-picker" busy={state.busy}
    onClose={onClose} onSubmit={(event)=>{event.preventDefault();event.stopPropagation();if(state.ready && !resources.isError)onSelect({mode:state.choices.length ? "VERSIONS":"NONE",skills:state.choices});}}
    footer={<>
      <Button variant="outline" type="button" disabled={state.busy} onClick={()=>onSelect({mode:"NONE",skills:[]})}>{t("skills.none")}</Button>
      <Button variant="outline" type="button" disabled={state.busy} onClick={onClose}>{t("common.cancel")}</Button>
      <Button type="submit" disabled={state.busy || !state.ready || resources.isError}>{t("skills.use")}</Button>
    </>}>
    <div className="agent-skill-picker-toolbar">
      <Input type="search" aria-label={t("skills.search")} placeholder={t("skills.search")} value={search} disabled={state.busy} onChange={(event)=>setSearch(event.target.value)} />
      {state.binding.data?.skills.length ? <Button variant="outline" type="button" disabled={state.busy} onClick={()=>update(state.binding.data!.skills.map((choice)=>({...choice,inputs:[]})))}>{t("skills.defaultMode")}</Button> : null}
    </div>
    <p role="status">{t("skills.selectionLimit",{"0":state.choices.length,"1":MAX_SKILLS})}</p>
    {skills.isPending ? <LoadingState compact label={t("common.loading")} /> : null}
    {skills.isSuccess && !items.length ? <EmptyState icon={<BookOpen />} title={t("skills.empty")} description={t("skills.emptyHint")} /> : null}
    <div className="agent-skill-picker-grid">
      {items.map((skill)=>{const selected=state.choices.some((choice)=>choice.skillId===skill.id);return <Button key={skill.id} variant="outline" type="button"
        className="agent-skill-picker-card" aria-label={skill.title} aria-pressed={selected} disabled={state.busy || (!selected && state.choices.length>=MAX_SKILLS)}
        onClick={()=>toggle(skill.id,skill.currentVersionId!)}>
        {selected ? <Check data-icon="inline-start" />:<BookOpen data-icon="inline-start" />}<strong>{skill.title}</strong>{skill.builtin ? <StatusBadge>{t("skills.builtin")}</StatusBadge> : null}<span>{skill.description}</span>
      </Button>;})}
    </div>
    {skills.hasNextPage ? <Button variant="outline" type="button" disabled={state.busy || skills.isFetchingNextPage} onClick={()=>void skills.fetchNextPage()}>{t("projects.loadMore")}</Button> : null}
    {state.choices.map((choice,index)=><SkillChoicePanel key={choice.skillId} agent={agent} choice={choice} state={state} index={index}
      resources={resources.data} resourcesFetching={resources.isFetching} onChange={(changed)=>update(state.choices.map((item)=>item.skillId===changed.skillId ? changed:item))} />)}
    {error ? <SkillError error={error} onRefresh={()=>{void skills.refetch();state.versions.forEach((query)=>void query.refetch());state.installations.forEach((query)=>void query.refetch());if(resources.isEnabled)void resources.refetch();}} /> : null}
  </Dialog>;
}
function SkillChoicePanel({agent,choice,state,index,resources,resourcesFetching,onChange}:{agent:Agent;choice:Choice;state:AgentSkillState;index:number;
  resources:Awaited<ReturnType<typeof listArtifacts>> | undefined;resourcesFetching:boolean;onChange:(choice:Choice)=>void}) {
  const query=state.versions[index];
  const selected=query?.data;
  const versions=useQuery({queryKey:["skill-versions",choice.skillId],queryFn:()=>listSkillVersions(choice.skillId)});
  const progress=state.progress.find((operation)=>operation.skillVersionId===choice.skillVersionId);
  const missing=selected?.inputSlots.some((slot)=>slot.required && !choice.inputs.some((input)=>input.alias===slot.alias));
  return <>
    {query?.isFetching ? <LoadingState compact label={t("common.loading")} /> : null}
    {selected ? <Panel title={selected.name} description={selected.description} className="agent-skill-picker-preview">
      <FieldGroup><Field><FieldLabel>{t("skills.versions")}</FieldLabel>
        <Select aria-label={`${selected.name} · ${t("skills.versions")}`} value={choice.skillVersionId} disabled={state.busy || versions.isFetching}
          onChange={(event)=>onChange({...choice,skillVersionId:event.target.value,inputs:[]})}>
          {!versions.data?.some((version)=>version.id===selected.id) ? <option value={selected.id}>{t("skills.version",{"0":selected.versionNumber})}</option> : null}
          {versions.data?.map((version)=><option key={version.id} value={version.id}>{t("skills.version",{"0":version.versionNumber})}</option>)}
        </Select></Field>
        {selected.inputSlots.map((slot)=><Field key={slot.alias}><FieldLabel>{slot.alias}{slot.required ? ` · ${t("skills.required")}`:""}</FieldLabel>
          <Select aria-label={`${slot.alias} · ${t("skills.inputVersion")}`} disabled={state.busy || resourcesFetching} value={choice.inputs.find((input)=>input.alias===slot.alias)?.artifactVersionId??""}
            onChange={(event)=>{const inputs=choice.inputs.filter((input)=>input.alias!==slot.alias);if(event.target.value)inputs.push({alias:slot.alias,artifactVersionId:event.target.value});onChange({...choice,inputs});}}>
            <option value="">{t("skills.inputVersion")}</option>{agent.bindings.filter((binding)=>resources?.items.some((artifact)=>artifact.id===binding.artifactId && artifact.kind===slot.kind))
              .map((binding)=><option key={binding.selectedVersionId} value={binding.selectedVersionId}>{resources?.items.find((artifact)=>artifact.id===binding.artifactId)?.title}</option>)}
          </Select></Field>)}
      </FieldGroup>
      <details><summary>{t("skills.body")}</summary><pre className="skills-source-preview">{selected.skillMd}</pre></details><p>{t("skills.modelOnly")}</p>
      <Button variant="outline" type="button" disabled={state.busy} onClick={()=>state.install.mutate(choice)}>{t("skills.install")}</Button>
      {progress ? <Notice tone={progress.status==="FAILED" ? "danger":"info"}>{pendingInstallation(progress) ? t("skills.installing"):progress.status==="SUCCEEDED" ? t("skills.installed"):progress.errorDetail??progress.errorCode}</Notice> : null}
      {missing ? <p role="status">{t("skills.missingInput")}</p> : null}
      {versions.error ? <SkillError error={versions.error} onRefresh={()=>void versions.refetch()} /> : null}
    </Panel> : null}
  </>;
}
export function RunSkillSummary({skill}:{skill:import("../../shared/api/client").RunPreflight["creativeSkills"][number]}) {
  return <section aria-label={t("skills.preflight")}><p>{skill.title} · {t("skills.version",{"0":skill.versionNumber})}</p><p>{skill.installed ? t("skills.installed"):t("skills.installing")}</p><ul>{skill.resources.map((resource)=><li key={resource.path}><details><summary>{resource.path} · {resource.contentHash}</summary><pre className="skills-source-preview">{resource.content}</pre></details></li>)}{skill.assets.map((asset)=><li key={asset.alias}>{asset.alias} · {asset.purpose} · {asset.usage==="GUIDE" ? t("skills.guide"):t("skills.reference")}</li>)}</ul><p>{t("skills.fees")}</p></section>;
}
