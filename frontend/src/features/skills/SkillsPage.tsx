import { useInfiniteQuery,useMutation,useQuery,useQueryClient } from "@tanstack/react-query";
import { ArrowCounterClockwise,ArrowUpRight,BookOpen,CaretRight,Copy,FileText,FloppyDisk,FolderSimple,ImageSquare,MagnifyingGlass,Plus,Sparkle,Stack,Trash,UploadSimple } from "@/shared/ui/icons";
import { useEffect,useRef,useState,type ReactNode } from "react";
import { useNavigate } from "react-router";
import { ApiError,HTTP_STATUS,copySkill,copySkillVersion,createSkill,getSkill,getSkillDraft,getSkillOperation,getSkillVersion,
  listAgents,listProjects,listSkills,listSkillVersions,publishSkillVersion,retrySkillOperation,saveSkillDraft,skillAssetThumbnailUrl,updateSkill,
  type CreativeSkill,type LibraryEntry,type SaveSkillDraftRequest,type SkillDraft,type SkillOperation,type SkillVersion } from "../../shared/api/client";
import { t,useLocale } from "../../shared/i18n";
import { Dialog } from "../../shared/ui/Dialog";
import { LoadingState } from "../../shared/ui/LoadingState";
import { EmptyState,Notice,Panel,StatusBadge } from "../../shared/ui/PagePrimitives";
import { PageShell } from "../../shared/ui/PageShell";
import { Select } from "../../shared/ui/Select";
import { Button } from "../../shared/ui/primitives/button";
import { Badge } from "../../shared/ui/primitives/badge";
import { Checkbox } from "../../shared/ui/primitives/checkbox";
import { Field,FieldDescription,FieldGroup,FieldLabel,FieldLegend,FieldSet } from "../../shared/ui/primitives/field";
import { Input } from "../../shared/ui/primitives/input";
import { Tabs,TabsContent,TabsList,TabsTrigger } from "../../shared/ui/primitives/tabs";
import { Textarea } from "../../shared/ui/primitives/textarea";
import { ToggleGroup,ToggleGroupItem } from "../../shared/ui/primitives/toggle-group";
import { LibraryBrowser } from "../library/LibraryBrowser";
import "./Skills.css";

const AUTOSAVE_DELAY_MS = 650;
const OPERATION_POLL_MS = 1500;
const MAX_BODY_LENGTH = 8000;
const MAX_RESOURCE_BYTES = MAX_BODY_LENGTH * 4;
const MAX_RESOURCES = 8;
const MAX_ASSETS = 14;
const MAX_SLOTS = 14;
const MAX_TITLE_LENGTH = 160;
const EMPTY_SKILL_MD = "---\nname: new-skill\ndescription: Describe when this Skill should be used.\n---\n\n# Creative method\n";
const operationPending = (operation?: SkillOperation) => operation?.status === "ACCEPTED" || operation?.status === "ARCHIVING";
function editable(draft: SkillDraft): Omit<SaveSkillDraftRequest,"expectedVersion"> {
  return { skillMd: draft.skillMd, outputKinds: draft.outputKinds, inputSlots: draft.inputSlots, resources: draft.resources, assets: draft.assets };
}
export function SkillsPage() {
  useLocale();
  const client = useQueryClient();
  const [query,setQuery] = useState("");
  const [trash,setTrash] = useState(false);
  const [selected,setSelected] = useState<CreativeSkill | null>(null);
  const [opened,setOpened] = useState<CreativeSkill[]>([]);
  function openSkill(skill:CreativeSkill) {setSelected(skill);setOpened((current)=>current.some((entry)=>entry.id===skill.id) ? current.map((entry)=>entry.id===skill.id ? skill:entry) : [...current,skill]);}
  const [newTitle,setNewTitle] = useState("");
  const list = useInfiniteQuery({ queryKey: ["skills",query,trash], initialPageParam: undefined as string | undefined,
    queryFn: ({pageParam}) => listSkills(query,pageParam,trash), getNextPageParam: (page) => page.nextCursor ?? undefined });
  const create = useMutation({ mutationFn: () => createSkill(newTitle.trim()), onSuccess: (skill) => {
    openSkill(skill); setNewTitle(""); void client.invalidateQueries({queryKey:["skills"]});
  }});
  const loadedSkills = list.data?.pages.flatMap((page)=>page.items) ?? [];
  const catalogueGroups = trash
    ? [{label:t("skills.trash"),items:loadedSkills}]
    : [{label:t("skills.builtin"),items:loadedSkills.filter((skill)=>skill.builtin)},
      {label:t("skills.title"),items:loadedSkills.filter((skill)=>!skill.builtin)}];
  return <PageShell title={t("skills.title")} description={t("skills.description")}>
    <div className="skills-layout skills-workspace"><Panel title={trash ? t("skills.trash") : t("skills.active")} className="skills-sidebar" actions={list.data ? <Badge variant="secondary">{list.data.pages[0]?.total ?? 0}</Badge> : undefined}>
      <ToggleGroup className="skills-catalogue-tabs" type="single" variant="outline" size="sm" value={trash ? "trash":"active"} onValueChange={(value)=>{if(value)setTrash(value==="trash");}} aria-label={t("skills.title")}><ToggleGroupItem value="active"><BookOpen aria-hidden />{t("skills.active")}</ToggleGroupItem><ToggleGroupItem value="trash"><Trash aria-hidden />{t("skills.trash")}</ToggleGroupItem></ToggleGroup>
      <FieldGroup className="skills-search"><Field><FieldLabel className="sr-only" htmlFor="skill-search">{t("skills.search")}</FieldLabel><div className="skills-search-control"><MagnifyingGlass aria-hidden /><Input id="skill-search" placeholder={t("skills.search")} value={query} onChange={(event)=>setQuery(event.target.value)} /></div></Field></FieldGroup>
      {create.error ? <SkillError error={create.error} /> : null}
      {list.isPending ? <LoadingState label={t("common.loading")} /> : null}
      {list.error ? <SkillError error={list.error} onRefresh={()=>void list.refetch()} /> : null}
      <div className="skills-list">{catalogueGroups.filter((group)=>group.items.length>0).map((group)=><section className="skills-catalogue-group" key={group.label} aria-label={group.label}>
        <header className="skills-catalogue-group-heading"><h3>{group.label}</h3><span>{group.items.length}</span></header>
        {group.items.map((skill)=><Button variant="ghost" className="skills-list-item" key={skill.id} aria-label={skill.title} title={skill.title} aria-pressed={selected?.id===skill.id} onClick={()=>openSkill(skill)}>
          <span className="skills-list-item-icon" aria-hidden>{skill.builtin ? <Sparkle /> : <FileText />}</span>
          <span className="skills-list-item-copy"><span className="skills-list-item-title">{skill.title}</span><span className="skills-list-item-description">{skill.description || (skill.currentVersionId ? t("skills.versions") : t("skills.noVersion"))}</span></span>
          <CaretRight className="skills-list-item-chevron" aria-hidden />
        </Button>)}
      </section>)}</div>
      {list.data?.pages[0]?.total === 0 ? <EmptyState title={trash ? t("skills.trashEmpty") : t("skills.empty")} description={trash ? t("skills.trashHint") : t("skills.emptyHint")} /> : null}
      {list.hasNextPage ? <Button disabled={list.isFetchingNextPage} onClick={()=>void list.fetchNextPage()}>{t("projects.loadMore")}</Button> : null}
      {!trash ? <FieldGroup className="skills-create-form"><Field><FieldLabel htmlFor="skill-create-name">{t("skills.name")}</FieldLabel><Input id="skill-create-name" placeholder={t("skills.name")} maxLength={MAX_TITLE_LENGTH} value={newTitle} onChange={(event)=>setNewTitle(event.target.value)} />
        <Button variant="outline" disabled={!newTitle.trim() || create.isPending} onClick={()=>create.mutate()}><Plus data-icon="inline-start" />{t("skills.new")}</Button></Field></FieldGroup> : null}
    </Panel><div className="skills-detail">{opened.map((skill)=><div hidden={selected?.id!==skill.id} key={skill.id}><SkillEditor skill={skill} onSkillChanged={openSkill} /></div>)}{!selected ? <div className="skills-selection-empty"><EmptyState icon={<BookOpen />} title={t("skills.choose")} description={t("skills.emptyHint")} /></div> : null}</div></div>
  </PageShell>;
}
export function SkillError({error,onRefresh}:{error:Error;onRefresh?:()=>void}) {
  useLocale();
  return <Notice tone="danger"><p>{error instanceof ApiError && error.status === HTTP_STATUS.CONFLICT ? t("skills.conflict") : t("skills.errorPreserved",{"0":error.message})}</p>{onRefresh ? <Button variant="outline" type="button" onClick={onRefresh}>{t("skills.refresh")}</Button> : null}</Notice>;
}
function SkillEditor({skill,onSkillChanged}:{skill:CreativeSkill;onSkillChanged:(skill:CreativeSkill)=>void}) {
  return skill.builtin ? <BuiltinSkillViewer skill={skill} /> : <PersonalSkillEditor skill={skill} onSkillChanged={onSkillChanged} />;
}
function BuiltinSkillViewer({skill}:{skill:CreativeSkill}) {
  const version=useQuery({queryKey:["skill-version",skill.id,skill.currentVersionId],queryFn:()=>getSkillVersion(skill.id,skill.currentVersionId!),enabled:Boolean(skill.currentVersionId)});
  const [tryOpen,setTryOpen]=useState(false);
  return <Panel title={skill.title} description={skill.description} className="skills-detail-panel skills-builtin-viewer" actions={<StatusBadge>{t("skills.builtin")}</StatusBadge>}>
    <Notice><p>{t("skills.builtinHint")}</p><a href="https://github.com/zenstory-ai/drama-skills" target="_blank" rel="noreferrer">Drama Skills · MIT</a></Notice>
    {version.isPending ? <LoadingState label={t("common.loading")} /> : null}
    {version.error ? <SkillError error={version.error} onRefresh={()=>void version.refetch()} /> : null}
    {version.data ? <SkillVersionPreview version={version.data} actions={<Button onClick={()=>setTryOpen(true)}><ArrowUpRight data-icon="inline-start" />{t("skills.try")}</Button>} /> : null}
    {tryOpen && version.data ? <TrySkillDialog version={version.data} onClose={()=>setTryOpen(false)} /> : null}
  </Panel>;
}
/** Keep published documents and attachments in bounded, readable sections for both Skill kinds. */
function SkillVersionPreview({version,actions}:{version:SkillVersion;actions?:ReactNode}) {
  return <section className="skills-version-preview">
    <div className="skills-version-toolbar"><div className="skills-version-identity"><Badge variant="outline">{t("skills.version",{"0":version.versionNumber})}</Badge><p>{t("skills.immutable")}</p></div>{actions ? <div className="ui-form-actions">{actions}</div> : null}</div>
    <section className="skills-document"><h3><FileText aria-hidden />{t("skills.markdown")}</h3><pre className="skills-source-preview">{version.skillMd}</pre></section>
    {version.resources.length ? <section className="skills-resources"><h3><FolderSimple aria-hidden />{t("skills.resources")} <Badge variant="secondary">{version.resources.length}</Badge></h3>
      <ul className="skills-resource-list">{version.resources.map((resource)=><li key={resource.path}><details><summary>{resource.path}</summary><pre className="skills-source-preview">{resource.content}</pre></details></li>)}</ul>
    </section> : null}
    {version.assets.length ? <section className="skills-assets"><h3><ImageSquare aria-hidden />{t("skills.assets")} <Badge variant="secondary">{version.assets.length}</Badge></h3><div className="skills-published-assets">{version.assets.map((asset)=><figure key={asset.alias}><img src={skillAssetThumbnailUrl(version.skillId,version.id,asset.alias)} alt={asset.title} loading="lazy" /><figcaption>{asset.alias} · {asset.purpose}</figcaption></figure>)}</div></section> : null}
  </section>;
}

function PersonalSkillEditor({skill,onSkillChanged}:{skill:CreativeSkill;onSkillChanged:(skill:CreativeSkill)=>void}) {
  const draft = useQuery({queryKey:["skill-draft",skill.id],queryFn:()=>getSkillDraft(skill.id)});
  useEffect(()=>{if(skill.trashed)void draft.refetch();},[skill.trashed,draft.refetch]);
  if (!draft.data) return <Panel title={skill.title}>{draft.isPending ? <LoadingState label={t("common.loading")} /> : null}{draft.error ? <SkillError error={draft.error} onRefresh={()=>void draft.refetch()} /> : null}</Panel>;
  return <SkillEditorForm skill={skill} initial={draft.data} onSkillChanged={onSkillChanged} />;
}
function SkillEditorForm({skill,initial,onSkillChanged}:{skill:CreativeSkill;initial:SkillDraft;onSkillChanged:(skill:CreativeSkill)=>void}) {
  useLocale();
  const client = useQueryClient();
  const [fields,setFields] = useState(()=>editable(initial));
  const [baseVersion,setBaseVersion] = useState(initial.version);
  const [title,setTitle] = useState(skill.title);
  const currentTitle = useRef(title);
  currentTitle.current = title;
  const [dirty,setDirty] = useState(false);
  const [saveFailed,setSaveFailed] = useState(false);
  const [serverDraft,setServerDraft] = useState<SkillDraft | null>(null);
  const [assetPicker,setAssetPicker] = useState(false);
  const [localError,setLocalError] = useState<Error | null>(null);
  const [operation,setOperation] = useState<SkillOperation | null>(null);
  const [versionId,setVersionId] = useState("");
  const [tryVersion,setTryVersion] = useState<SkillVersion | null>(null);
  const publishKey = useRef<string | null>(null);
  const refreshedPublication = useRef<string | null>(null);
  const readOnly = skill.trashed;
  const shownFields = readOnly ? editable(initial):fields;
  const refreshMetadata = useMutation({mutationFn:()=>getSkill(skill.id),onSuccess:onSkillChanged});
  const trashChange = useMutation({mutationFn:(trashed:boolean)=>updateSkill(skill.id,{expectedVersion:skill.version,title:skill.title,description:skill.description,trashed}),onSuccess:(changed)=>{
    onSkillChanged(changed);if (!dirty) setTitle(changed.title);void client.invalidateQueries({queryKey:["skills"]});
  }});
  const versions = useQuery({queryKey:["skill-versions",skill.id],queryFn:()=>listSkillVersions(skill.id)});
  const version = useQuery({queryKey:["skill-version",skill.id,versionId],queryFn:()=>getSkillVersion(skill.id,versionId),enabled:Boolean(versionId)});
  const progress = useQuery({queryKey:["skill-operation",operation?.id],queryFn:()=>getSkillOperation(operation!.id),enabled:Boolean(operation?.id),
    refetchInterval:(query)=>operationPending(query.state.data ?? operation ?? undefined) ? OPERATION_POLL_MS : false});
  const displayedOperation = progress.data ?? operation;
  useEffect(()=>{
    if (displayedOperation?.status === "SUCCEEDED" && refreshedPublication.current!==displayedOperation.id) {
      refreshedPublication.current=displayedOperation.id;
      void client.invalidateQueries({queryKey:["skill-versions",skill.id]}); void client.invalidateQueries({queryKey:["skills"]});
      refreshMetadata.mutate();
      if (displayedOperation.resultVersionId) setVersionId(displayedOperation.resultVersionId);
    }
  },[displayedOperation?.id,displayedOperation?.status,displayedOperation?.resultVersionId,client,skill.id,refreshMetadata.mutate]);
  const submission = () => ({fields,title,catalogueTitle:skill.title,catalogueVersion:skill.version,draftVersion:baseVersion,description:skill.description});
  const save = useMutation({mutationFn:async(submitted:ReturnType<typeof submission>)=>{
    if (submitted.title.trim()!==submitted.catalogueTitle) onSkillChanged(await updateSkill(skill.id,{expectedVersion:submitted.catalogueVersion,title:submitted.title.trim(),description:submitted.description,trashed:false}));
    return saveSkillDraft(skill.id,{...submitted.fields,expectedVersion:submitted.draftVersion});
  },onSuccess:(saved,submitted)=>{
    setBaseVersion(saved.version); setSaveFailed(false);
    setFields((current)=>{ if (JSON.stringify(current)===JSON.stringify(submitted.fields) && currentTitle.current.trim()===submitted.title.trim()) {setDirty(false); return editable(saved);} return current; });
    client.setQueryData(["skill-draft",skill.id],saved);
  },onError:()=>setSaveFailed(true)});
  useEffect(()=>{
    if (readOnly || trashChange.isPending || refreshMetadata.isPending || !dirty || saveFailed || save.isPending || operationPending(displayedOperation ?? undefined)) return;
    const timer = window.setTimeout(()=>save.mutate(submission()),AUTOSAVE_DELAY_MS);
    return ()=>window.clearTimeout(timer);
  },[readOnly,trashChange.isPending,refreshMetadata.isPending,dirty,fields,title,saveFailed,save.isPending,displayedOperation,save]);
  const publish = useMutation({mutationFn:async()=>{
    const saved = dirty ? await save.mutateAsync(submission()) : initial;
    publishKey.current ??= crypto.randomUUID();
    return publishSkillVersion(skill.id,dirty ? saved.version : baseVersion,publishKey.current);
  },onSuccess:(result)=>{setOperation(result);publishKey.current=null;}});
  const retry = useMutation({mutationFn:()=>retrySkillOperation(displayedOperation!.id),onSuccess:setOperation});
  const copy = useMutation({mutationFn:()=>copySkillVersion(skill.id,versionId,baseVersion),onSuccess:(saved)=>{
    setFields(editable(saved));setBaseVersion(saved.version);setDirty(false);setSaveFailed(false);client.setQueryData(["skill-draft",skill.id],saved);
  }});
  const copyIdentity = useMutation({mutationFn:()=>copySkill(skill.id,versionId,title),onSuccess:(created)=>{onSkillChanged(created);void client.invalidateQueries({queryKey:["skills"]});}});
  const busy = trashChange.isPending || refreshMetadata.isPending || copyIdentity.isPending || save.isPending || publish.isPending || copy.isPending || operationPending(displayedOperation ?? undefined);
  function change(next:typeof fields) {setFields(next);setDirty(true);setSaveFailed(false);setLocalError(null);}
  function addAsset(entry:LibraryEntry) {
    if (fields.assets.length>=MAX_ASSETS) return;
    change({...fields,assets:[...fields.assets,{alias:`reference-${fields.assets.length+1}`,libraryEntryId:entry.id,expectedLibraryVersion:entry.version,
      usage:"GUIDE",required:true,purpose:entry.name}]});setAssetPicker(false);
  }
  async function uploadResource(file?:File) {
    if (!file || fields.resources.length>=MAX_RESOURCES) return;
    try {
      if (file.size>MAX_RESOURCE_BYTES) throw new Error(t("skills.resourceHint"));
      const content=new TextDecoder("utf-8",{fatal:true}).decode(await file.arrayBuffer());
      if (content.length>MAX_BODY_LENGTH || !/\.(md|txt)$/i.test(file.name)) throw new Error(t("skills.resourceHint"));
      change({...fields,resources:[...fields.resources,{path:`references/${file.name}`,content}]});
    } catch (error) {setLocalError(error instanceof Error ? error : new Error(t("skills.resourceHint")));}
  }
  return <Panel className="skills-detail-panel skills-personal-editor" title={skill.title} description={readOnly ? t("skills.trash") : t("skills.edit")} actions={readOnly ? <Button disabled={busy} onClick={()=>trashChange.mutate(false)}><ArrowCounterClockwise data-icon="inline-start" />{t("skills.restore")}</Button> : <><StatusBadge tone={dirty ? "warning" : "neutral"}>{dirty ? t("skills.unsaved") : t("skills.saved")}</StatusBadge><Button variant="outline" disabled={busy || !dirty} onClick={()=>save.mutate(submission())}><FloppyDisk data-icon="inline-start" />{t("skills.save")}</Button><Button disabled={busy || !title.trim()} onClick={()=>publish.mutate()}><UploadSimple data-icon="inline-start" />{t("skills.publish")}</Button><Button variant="ghost" size="icon-sm" aria-label={t("skills.moveToTrash")} title={t("skills.moveToTrash")} disabled={busy || dirty} onClick={()=>trashChange.mutate(true)}><Trash /></Button></>}>
    {readOnly ? <Notice>{t("skills.trashReadOnly")}</Notice> : null}
    {trashChange.error ? <SkillError error={trashChange.error} onRefresh={()=>refreshMetadata.mutate(undefined,{onSuccess:()=>trashChange.reset()})} /> : null}
    {refreshMetadata.error ? <SkillError error={refreshMetadata.error} onRefresh={()=>refreshMetadata.mutate()} /> : null}
    <FieldGroup className="skills-editor-identity"><Field><FieldLabel htmlFor={`skill-title-${skill.id}`}>{t("skills.name")}</FieldLabel><Input id={`skill-title-${skill.id}`} value={readOnly ? skill.title:title} readOnly={readOnly || trashChange.isPending} maxLength={MAX_TITLE_LENGTH} onChange={(event)=>{setTitle(event.target.value);setDirty(true);}} /></Field></FieldGroup>
    {save.error ? <SkillError error={save.error} onRefresh={()=>void Promise.all([getSkillDraft(skill.id),getSkill(skill.id)]).then(([latest,catalogue])=>{setBaseVersion(latest.version);setServerDraft(latest);setSaveFailed(true);onSkillChanged(catalogue);}).catch(setLocalError)} /> : null}
    {serverDraft ? <details><summary>{t("skills.refresh")} · {serverDraft.version}</summary><pre className="skills-source-preview">{serverDraft.skillMd}</pre></details> : null}
    {publish.error || copy.error || copyIdentity.error || retry.error || localError ? <SkillError error={(publish.error ?? copy.error ?? copyIdentity.error ?? retry.error ?? localError)!} /> : null}
    {displayedOperation ? <Notice tone={displayedOperation.status === "FAILED" ? "danger" : displayedOperation.status === "SUCCEEDED" ? "success" : "info"}>
      {operationPending(displayedOperation) ? t("skills.publishing") : displayedOperation.status === "SUCCEEDED" ? t("skills.publishSuccess") : displayedOperation.errorDetail ?? displayedOperation.errorCode}
      {displayedOperation.status === "FAILED" && !readOnly ? <Button disabled={retry.isPending} onClick={()=>retry.mutate()}>{t("common.retry")}</Button> : null}
    </Notice> : null}
    {progress.error ? <SkillError error={progress.error} onRefresh={()=>void progress.refetch()} /> : null}
    <Tabs defaultValue="markdown" className="skills-editor-tabs"><TabsList variant="line"><TabsTrigger value="markdown"><FileText aria-hidden />{t("skills.markdown")}</TabsTrigger><TabsTrigger value="resources" aria-label={t("skills.resources")}><FolderSimple aria-hidden />{t("skills.resources")}<Badge variant="secondary">{shownFields.resources.length}</Badge></TabsTrigger><TabsTrigger value="assets" aria-label={t("skills.assets")}><ImageSquare aria-hidden />{t("skills.assets")}<Badge variant="secondary">{shownFields.assets.length}</Badge></TabsTrigger></TabsList>
      <TabsContent value="markdown"><FieldSet disabled={readOnly || trashChange.isPending} className="skills-editor-fields"><FieldGroup><Field><FieldLabel htmlFor={`skill-md-${skill.id}`}>{t("skills.body")}</FieldLabel><Textarea className="skills-main-document" id={`skill-md-${skill.id}`} rows={18} readOnly={readOnly} maxLength={MAX_BODY_LENGTH} value={shownFields.skillMd} placeholder={EMPTY_SKILL_MD} onChange={(event)=>change({...fields,skillMd:event.target.value})} /><FieldDescription>{t("skills.bodyHint")}</FieldDescription></Field>
        <FieldSet className="skills-input-slots"><FieldLegend variant="label">{t("skills.inputs")}</FieldLegend><FieldGroup>{shownFields.inputSlots.map((slot,index)=><FieldGroup className="skills-input-row" key={index}><Field><FieldLabel htmlFor={`slot-${skill.id}-${index}`}>{t("skills.alias")}</FieldLabel><Input id={`slot-${skill.id}-${index}`} value={slot.alias} onChange={(event)=>change({...fields,inputSlots:fields.inputSlots.map((current,i)=>i===index ? {...current,alias:event.target.value}:current)})} /></Field><Field orientation="horizontal" className="skills-required-field"><Checkbox id={`slot-required-${skill.id}-${index}`} checked={slot.required} onCheckedChange={(checked)=>change({...fields,inputSlots:fields.inputSlots.map((current,i)=>i===index ? {...current,required:checked===true}:current)})} /><FieldLabel htmlFor={`slot-required-${skill.id}-${index}`}>{t("skills.required")}</FieldLabel></Field><Button variant="ghost" size="icon-sm" aria-label={t("skills.remove")} title={t("skills.remove")} onClick={()=>change({...fields,inputSlots:fields.inputSlots.filter((_,i)=>i!==index)})}><Trash /></Button></FieldGroup>)}<Button variant="outline" className="skills-add-action" disabled={fields.inputSlots.length>=MAX_SLOTS} onClick={()=>change({...fields,inputSlots:[...fields.inputSlots,{alias:`subject-${fields.inputSlots.length+1}`,kind:"IMAGE",required:true}]})}><Plus data-icon="inline-start" />{t("skills.inputAdd")}</Button></FieldGroup></FieldSet>
      </FieldGroup></FieldSet></TabsContent>
      <TabsContent value="resources"><FieldSet disabled={readOnly || trashChange.isPending} className="skills-editor-fields"><p className="skills-editor-hint">{t("skills.resourceHint")}</p><FieldGroup>{shownFields.resources.map((resource,index)=><FieldGroup className="skills-attachment-editor" key={index}><Field><FieldLabel htmlFor={`resource-path-${skill.id}-${index}`}>{t("skills.resourcePath")}</FieldLabel><Input id={`resource-path-${skill.id}-${index}`} value={resource.path} onChange={(event)=>change({...fields,resources:fields.resources.map((current,i)=>i===index ? {...current,path:event.target.value}:current)})} /></Field><Field><FieldLabel htmlFor={`resource-text-${skill.id}-${index}`}>{t("skills.resourceText")}</FieldLabel><Textarea id={`resource-text-${skill.id}-${index}`} rows={8} maxLength={MAX_BODY_LENGTH} value={resource.content} onChange={(event)=>change({...fields,resources:fields.resources.map((current,i)=>i===index ? {...current,content:event.target.value}:current)})} /></Field><Button variant="outline" className="skills-add-action" onClick={()=>change({...fields,resources:fields.resources.filter((_,i)=>i!==index)})}><Trash data-icon="inline-start" />{t("skills.remove")}</Button></FieldGroup>)}<div className="skills-resource-actions"><Button variant="outline" disabled={fields.resources.length>=MAX_RESOURCES} onClick={()=>change({...fields,resources:[...fields.resources,{path:`references/resource-${fields.resources.length+1}.md`,content:""}]})}><Plus data-icon="inline-start" />{t("skills.resourceAdd")}</Button><Input aria-label={t("skills.resourceAdd")} type="file" accept=".md,.txt" disabled={fields.resources.length>=MAX_RESOURCES} onChange={(event)=>{void uploadResource(event.target.files?.[0]);event.target.value="";}} /></div></FieldGroup></FieldSet></TabsContent>
      <TabsContent value="assets"><FieldSet disabled={readOnly || trashChange.isPending} className="skills-editor-fields"><p className="skills-editor-hint">{t("skills.fixedAssetsHint")}</p><FieldGroup>{shownFields.assets.map((asset,index)=><FieldGroup className="skills-asset-editor" key={index}><Field><FieldLabel htmlFor={`asset-alias-${skill.id}-${index}`}>{t("skills.alias")}</FieldLabel><Input id={`asset-alias-${skill.id}-${index}`} value={asset.alias} onChange={(event)=>change({...fields,assets:fields.assets.map((current,i)=>i===index ? {...current,alias:event.target.value}:current)})} /></Field><Field><FieldLabel htmlFor={`asset-purpose-${skill.id}-${index}`}>{t("skills.assetUsage")}</FieldLabel><Input id={`asset-purpose-${skill.id}-${index}`} value={asset.purpose} onChange={(event)=>change({...fields,assets:fields.assets.map((current,i)=>i===index ? {...current,purpose:event.target.value}:current)})} /></Field><Field orientation="horizontal" className="skills-required-field"><Checkbox id={`asset-required-${skill.id}-${index}`} checked={asset.required} onCheckedChange={(checked)=>change({...fields,assets:fields.assets.map((current,i)=>i===index ? {...current,required:checked===true}:current)})} /><FieldLabel htmlFor={`asset-required-${skill.id}-${index}`}>{t("skills.required")}</FieldLabel></Field><Button variant="ghost" size="icon-sm" aria-label={t("skills.remove")} title={t("skills.remove")} onClick={()=>change({...fields,assets:fields.assets.filter((_,i)=>i!==index)})}><Trash /></Button></FieldGroup>)}<Button variant="outline" className="skills-add-action" disabled={fields.assets.length>=MAX_ASSETS} onClick={()=>setAssetPicker(true)}><ImageSquare data-icon="inline-start" />{t("skills.assetAdd")}</Button></FieldGroup>{assetPicker && !readOnly ? <LibraryBrowser kinds={["IMAGE"]} pickAction="REFERENCE" onPick={addAsset} /> : null}</FieldSet></TabsContent>
    </Tabs>
    <FieldGroup className="skills-published-version-picker"><Field><FieldLabel htmlFor={`published-version-${skill.id}`}><Stack aria-hidden />{t("skills.versions")}</FieldLabel><Select id={`published-version-${skill.id}`} value={versionId} onChange={(event)=>setVersionId(event.target.value)}><option value="">{t("skills.noVersion")}</option>{versions.data?.map((item)=><option key={item.id} value={item.id}>{t("skills.version",{"0":item.versionNumber})}</option>)}</Select></Field></FieldGroup>
    {versions.error || version.error ? <SkillError error={(versions.error ?? version.error)!} onRefresh={()=>{void versions.refetch();if(versionId)void version.refetch();}} /> : null}
    {version.data ? <SkillVersionPreview version={version.data} actions={!readOnly ? <><Button variant="outline" disabled={busy || dirty} onClick={()=>copy.mutate()}><Copy data-icon="inline-start" />{t("skills.copy")}</Button><Button variant="outline" disabled={busy} onClick={()=>copyIdentity.mutate()}><Plus data-icon="inline-start" />{t("skills.copySkill")}</Button><Button disabled={busy} onClick={()=>setTryVersion(version.data!)}><ArrowUpRight data-icon="inline-start" />{t("skills.try")}</Button></> : undefined} /> : null}
    {tryVersion && !readOnly ? <TrySkillDialog version={tryVersion} onClose={()=>setTryVersion(null)} /> : null}
  </Panel>;
}
function TrySkillDialog({version,onClose}:{version:SkillVersion;onClose:()=>void}) {
  const navigate = useNavigate();
  const [projectId,setProjectId] = useState("");
  const [agentId,setAgentId] = useState("");
  const projects = useInfiniteQuery({queryKey:["projects","skill-try"],initialPageParam:undefined as string | undefined,queryFn:({pageParam})=>listProjects({cursor:pageParam}),getNextPageParam:(page)=>page.nextCursor??undefined});
  const agents = useQuery({queryKey:["agents",projectId],queryFn:()=>listAgents(projectId),enabled:Boolean(projectId)});
  return <Dialog title={t("skills.try")} description={t("skills.tryHint")} className="skills-dialog skills-try-dialog" onClose={onClose} onSubmit={(event)=>{
    event.preventDefault();if (!projectId || !agentId)return;
    const params=new URLSearchParams({agentId,skillId:version.skillId,skillVersionId:version.id});navigate(`/projects/${projectId}?${params}`);
  }} footer={<Button type="submit" disabled={!projectId || !agentId}>{t("skills.openAgent")}</Button>}>
    <FieldGroup><Field><FieldLabel htmlFor="skill-try-project">{t("skills.project")}</FieldLabel><Select id="skill-try-project" value={projectId} onChange={(event)=>{setProjectId(event.target.value);setAgentId("");}}><option value="">{t("skills.project")}</option>{projects.data?.pages.flatMap((page)=>page.items).filter((project)=>project.status==="ACTIVE").map((project)=><option key={project.id} value={project.id}>{project.name}</option>)}</Select></Field><Field><FieldLabel htmlFor="skill-try-agent">{t("skills.agent")}</FieldLabel><Select id="skill-try-agent" disabled={!projectId || agents.isPending} value={agentId} onChange={(event)=>setAgentId(event.target.value)}><option value="">{t("skills.agent")}</option>{agents.data?.items.map((agent)=><option key={agent.id} value={agent.id}>{agent.name}</option>)}</Select></Field></FieldGroup>
    {projects.hasNextPage ? <Button onClick={()=>void projects.fetchNextPage()}>{t("projects.loadMore")}</Button> : null}
    {projects.error || agents.error ? <SkillError error={(projects.error ?? agents.error)!} onRefresh={()=>{void projects.refetch();if(projectId)void agents.refetch();}} /> : null}
    <p>{t("skills.fees")}</p>
  </Dialog>;
}
