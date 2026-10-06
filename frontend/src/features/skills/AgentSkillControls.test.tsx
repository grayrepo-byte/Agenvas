import {useState} from "react";
import {QueryClientProvider} from "@tanstack/react-query";
import {cleanup,render,screen,waitFor,within} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import {http,HttpResponse} from "msw";
import {afterEach,expect,it,vi} from "vitest";
import {createQueryClient} from "../../app/queryClient";
import type {Agent,SaveAgentSkillBindingRequest} from "../../shared/api/client";
import {server} from "../../test/server";
import {AgentRunSkillControls,AgentSkillSettings,useAgentSkillSelection} from "./AgentSkillControls";

const agent:Agent={id:"agent-1",projectId:"project-1",profileKey:"creator",profileVersion:1,name:"Agent",instruction:"Create",outputGroupId:"group-1",bindings:[],version:3,createdAt:"2026-10-02T00:00:00Z",updatedAt:"2026-10-02T00:00:00Z"};
afterEach(cleanup);
it("keeps the edit's original CAS baseline when SSE brings a newer Agent configuration",async()=>{
  let liveVersion=agent.version;let submitted:SaveAgentSkillBindingRequest|undefined;const requests:SaveAgentSkillBindingRequest[]=[];
  server.use(http.get("/api/v1/auth/csrf",()=>HttpResponse.json({token:"synthetic-csrf",headerName:"X-CSRF-TOKEN"})),
    http.get("/api/v1/projects/project-1/agents/agent-1/skill-binding",()=>HttpResponse.json({agentVersion:liveVersion,skills:[]})),
    http.get("/api/v1/skills",()=>HttpResponse.json({items:[{id:"skill-1",title:"Style",currentVersionId:"version-1",trashed:false}],nextCursor:null,total:1})),
    http.get("/api/v1/skills/skill-1/versions",()=>HttpResponse.json([{id:"version-1",versionNumber:1}])),
    http.put("/api/v1/projects/project-1/agents/agent-1/skill-binding",async({request})=>{submitted=await request.json() as SaveAgentSkillBindingRequest;requests.push(submitted);return requests.length===1 ? HttpResponse.json({code:"AGENT_VERSION_CONFLICT",detail:"Synthetic conflict"},{status:409}) : HttpResponse.json({agentVersion:5,skills:submitted.skills});}));
  function Subject(){const[current,setCurrent]=useState(agent);const state=useAgentSkillSelection("project-1",current);return <><button onClick={()=>{liveVersion++;setCurrent({...current,version:liveVersion});}}>Simulate SSE</button><AgentSkillSettings projectId="project-1" agent={current} state={state} onChanged={()=>{}} /></>;}
  const client=createQueryClient();client.setDefaultOptions({queries:{retry:false}});
  render(<QueryClientProvider client={client}><Subject /></QueryClientProvider>);
  const user=userEvent.setup();await user.click(await screen.findByRole("combobox",{name:"选择 Skill"}));await user.click(await screen.findByRole("option",{name:"Style"}));
  await waitFor(()=>expect(screen.getByRole("combobox",{name:"发布版本"})).toBeEnabled());await user.click(screen.getByRole("combobox",{name:"发布版本"}));await user.click(await screen.findByRole("option",{name:"版本 1"}));
  await user.click(screen.getByRole("button",{name:"Simulate SSE"}));await waitFor(()=>expect(screen.getByRole("button",{name:"保存默认绑定"})).toBeEnabled());
  await user.click(screen.getByRole("button",{name:"保存默认绑定"}));await screen.findByText("服务器版本已变化。草稿已保留；刷新后核对并重试。");
  expect(submitted).toEqual({expectedAgentVersion:3,skills:[{skillId:"skill-1",skillVersionId:"version-1"}]});expect(screen.getByRole("combobox",{name:"发布版本"})).toHaveTextContent("版本 1");
  await user.click(screen.getByRole("button",{name:"刷新服务器版本"}));
  await waitFor(()=>expect(screen.queryByText("服务器版本已变化。草稿已保留；刷新后核对并重试。")).not.toBeInTheDocument());
  await user.click(screen.getByRole("button",{name:"保存默认绑定"}));
  await waitFor(()=>expect(requests).toHaveLength(2));
  expect(requests[1]).toEqual({expectedAgentVersion:4,skills:[{skillId:"skill-1",skillVersionId:"version-1"}]});
});

const skill={id:"skill-1",title:"温暖手绘",description:"柔和线条与暖色",currentVersionId:"version-1",trashed:false,version:1,createdAt:agent.createdAt,updatedAt:agent.updatedAt};
const skillVersion={id:"version-1",skillId:skill.id,versionNumber:1,name:"warm-art",description:skill.description,bundleHash:"synthetic-hash",skillMd:"# Synthetic method",outputKinds:["IMAGE"],inputSlots:[],resources:[],assets:[],createdAt:agent.createdAt};
function showRunPicker(value=agent) {
  const onChanged=vi.fn();
  function Subject(){const state=useAgentSkillSelection(value.projectId,value);return <>
    <output aria-label="active selection">{JSON.stringify(state.selection)}</output>
    <AgentRunSkillControls projectId={value.projectId} agent={value} state={state} onChanged={onChanged}/>
  </>;}
  const client=createQueryClient();client.setDefaultOptions({queries:{retry:false}});
  render(<QueryClientProvider client={client}><Subject/></QueryClientProvider>);
  return {onChanged};
}
function publishedSkillHandlers(){return [
  http.get("/api/v1/skills",()=>HttpResponse.json({items:[skill],nextCursor:null,total:1})),
  http.get("/api/v1/skills/skill-1/versions",()=>HttpResponse.json([skillVersion])),
  http.get("/api/v1/skills/skill-1/versions/version-1",()=>HttpResponse.json(skillVersion)),
];}
it("keeps modal choices local until applied and can clear a selected Skill",async()=>{
  server.use(...publishedSkillHandlers());
  const {onChanged}=showRunPicker();const user=userEvent.setup();
  expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  expect(screen.getByLabelText("active selection")).toHaveTextContent('{"mode":"NONE","skills":[]}');
  await user.click(screen.getByRole("button",{name:"选择 Skill"}));
  let dialog=await screen.findByRole("dialog",{name:"选择 Skill"});
  await user.click(await within(dialog).findByRole("button",{name:"温暖手绘"}));
  await waitFor(()=>expect(within(dialog).getByRole("button",{name:"使用 Skill"})).toBeEnabled());
  await user.click(within(dialog).getByRole("button",{name:"取消"}));
  expect(onChanged).not.toHaveBeenCalled();
  expect(screen.getByLabelText("active selection")).toHaveTextContent('{"mode":"NONE","skills":[]}');
  expect(screen.getByRole("button",{name:"选择 Skill"})).toHaveFocus();
  await user.click(screen.getByRole("button",{name:"选择 Skill"}));
  dialog=await screen.findByRole("dialog",{name:"选择 Skill"});
  await user.click(await within(dialog).findByRole("button",{name:"温暖手绘"}));
  await user.click(await within(dialog).findByRole("button",{name:"使用 Skill"}));
  await waitFor(()=>expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  expect(screen.getByLabelText("active selection")).toHaveTextContent('"skillVersionId":"version-1"');
  expect(onChanged).toHaveBeenCalledOnce();
  await user.click(screen.getByRole("button",{name:"选择 Skill"}));
  await user.click(within(await screen.findByRole("dialog")).getByRole("button",{name:"本次不使用 Skill"}));
  expect(screen.getByLabelText("active selection")).toHaveTextContent('{"mode":"NONE","skills":[]}');
  expect(onChanged).toHaveBeenCalledTimes(2);
});
it("validates required inputs in the modal and keeps their exact bound versions",async()=>{
  server.use(...publishedSkillHandlers());
  server.use(
    http.get("/api/v1/skills/skill-1/versions/version-1",()=>HttpResponse.json({...skillVersion,inputSlots:[{alias:"subject",kind:"IMAGE",required:true}]})),
    http.get("/api/v1/projects/project-1/artifacts",()=>HttpResponse.json({items:[{id:"image-1",kind:"IMAGE",title:"主体图片"}]})));
  showRunPicker({...agent,bindings:[{id:"binding-image-1",artifactId:"image-1",selectedVersionId:"image-version-1"}]});
  const user=userEvent.setup();await user.click(screen.getByRole("button",{name:"选择 Skill"}));
  const dialog=await screen.findByRole("dialog");
  await user.click(await within(dialog).findByRole("button",{name:"温暖手绘"}));
  expect(await within(dialog).findByText("请选择必需的主体图片")).toBeVisible();
  expect(within(dialog).getByRole("button",{name:"使用 Skill"})).toBeDisabled();
  const input=within(dialog).getByRole("combobox",{name:"subject · 精确输入版本"});
  await waitFor(()=>expect(input).toBeEnabled());await user.click(input);
  await user.click(await screen.findByRole("option",{name:"主体图片"}));
  await user.click(within(dialog).getByRole("button",{name:"使用 Skill"}));
  expect(screen.getByLabelText("active selection")).toHaveTextContent('"inputs":[{"alias":"subject","artifactVersionId":"image-version-1"}]');
});
it("shows catalog failures with retry and allows choosing no Skill without a catalog",async()=>{
  let reads=0;server.use(http.get("/api/v1/skills",()=>++reads===1
    ? HttpResponse.json({code:"SKILL_READ_FAILED",detail:"Synthetic catalog failure"},{status:503})
    : HttpResponse.json({items:[],nextCursor:null,total:0})));
  showRunPicker();const user=userEvent.setup();await user.click(screen.getByRole("button",{name:"选择 Skill"}));
  const dialog=await screen.findByRole("dialog");
  await user.click(await within(dialog).findByRole("button",{name:"刷新服务器版本"}));
  expect(await within(dialog).findByText("还没有 Skill")).toBeVisible();
  await user.click(within(dialog).getByRole("button",{name:"本次不使用 Skill"}));
  expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  expect(screen.getByLabelText("active selection")).toHaveTextContent('{"mode":"NONE","skills":[]}');
});

it("keeps project-reference preparation and its waiting state inside the modal",async()=>{
  server.use(...publishedSkillHandlers());
  let submitted:unknown;let release:()=>void=()=>{};
  const pending=new Promise<void>((resolve)=>{release=resolve;});
  const operation={id:"installation-1",skillId:skill.id,skillVersionId:skillVersion.id,status:"ACCEPTED",errorCode:null,errorDetail:null};
  server.use(
    http.get("/api/v1/auth/csrf",()=>HttpResponse.json({headerName:"X-CSRF-TOKEN",token:"synthetic-csrf"})),
    http.post("/api/v1/projects/project-1/agents/agent-1/skill-installations",async({request})=>{
      submitted=await request.json();return HttpResponse.json(operation,{status:202});}),
    http.get("/api/v1/projects/project-1/agents/agent-1/skill-installations/installation-1",async()=>{
      await pending;return HttpResponse.json({...operation,status:"SUCCEEDED"});}));
  showRunPicker();const user=userEvent.setup();await user.click(screen.getByRole("button",{name:"选择 Skill"}));
  const dialog=await screen.findByRole("dialog");await user.click(await within(dialog).findByRole("button",{name:"温暖手绘"}));
  await user.click(await within(dialog).findByRole("button",{name:"准备项目参考"}));
  expect(await within(dialog).findByText("正在准备项目参考文件…")).toBeVisible();
  expect(within(dialog).getByRole("button",{name:"使用 Skill"})).toBeDisabled();
  expect(submitted).toMatchObject({skillId:skill.id,skillVersionId:skillVersion.id});
  release();expect(await within(dialog).findByText("项目参考已准备")).toBeVisible();
  await user.click(within(dialog).getByRole("button",{name:"使用 Skill"}));
  expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  expect(screen.getByRole("button",{name:"选择 Skill"})).toHaveTextContent(skill.title);
});

it("keeps multiple fixed versions selected and toggles one without clearing the others",async()=>{
  const second={...skill,id:"skill-2",title:"叙事节奏",currentVersionId:"version-2"};
  const secondVersion={...skillVersion,id:"version-2",skillId:second.id,name:"story-rhythm"};
  server.use(...publishedSkillHandlers());
  server.use(http.get("/api/v1/skills",()=>HttpResponse.json({items:[skill,second],nextCursor:null,total:2})),
    http.get("/api/v1/skills/skill-2/versions",()=>HttpResponse.json([secondVersion])),
    http.get("/api/v1/skills/skill-2/versions/version-2",()=>HttpResponse.json(secondVersion)));
  const {onChanged}=showRunPicker();const user=userEvent.setup();await user.click(screen.getByRole("button",{name:"选择 Skill"}));
  const dialog=await screen.findByRole("dialog");
  await user.click(await within(dialog).findByRole("button",{name:skill.title}));
  await user.click(within(dialog).getByRole("button",{name:second.title}));
  expect(within(dialog).getByRole("button",{name:skill.title})).toHaveAttribute("aria-pressed","true");
  expect(within(dialog).getByRole("button",{name:second.title})).toHaveAttribute("aria-pressed","true");
  await waitFor(()=>expect(within(dialog).getByRole("button",{name:"使用 Skill"})).toBeEnabled());
  await user.click(within(dialog).getByRole("button",{name:"使用 Skill"}));
  expect(JSON.parse(screen.getByLabelText("active selection").textContent!)).toEqual({mode:"VERSIONS",skills:[
    {skillId:skill.id,skillVersionId:skillVersion.id,inputs:[]},{skillId:second.id,skillVersionId:secondVersion.id,inputs:[]}]});
  expect(screen.getByRole("button",{name:"选择 Skill"})).toHaveTextContent("已选 2 个 Skill");
  await user.click(screen.getByRole("button",{name:"选择 Skill"}));
  const reopened=await screen.findByRole("dialog");await user.click(within(reopened).getByRole("button",{name:skill.title}));
  await user.click(within(reopened).getByRole("button",{name:"使用 Skill"}));
  expect(JSON.parse(screen.getByLabelText("active selection").textContent!)).toEqual({mode:"VERSIONS",skills:[{skillId:second.id,skillVersionId:secondVersion.id,inputs:[]}]});
  expect(onChanged).toHaveBeenCalledTimes(2);
});

it("bounds selection to eight Skills and lets deselection free a slot",async()=>{
  const items=Array.from({length:9},(_,index)=>({...skill,id:`skill-${index}`,title:`Method ${index}`,currentVersionId:`version-${index}`}));
  server.use(http.get("/api/v1/skills",()=>HttpResponse.json({items,nextCursor:null,total:items.length})),
    http.get("/api/v1/skills/:skillId/versions",({params})=>HttpResponse.json([{...skillVersion,id:`version-${String(params.skillId).split("-")[1]}`,skillId:params.skillId}])),
    http.get("/api/v1/skills/:skillId/versions/:versionId",({params})=>HttpResponse.json({...skillVersion,id:params.versionId,skillId:params.skillId})));
  showRunPicker();const user=userEvent.setup();await user.click(screen.getByRole("button",{name:"选择 Skill"}));const dialog=await screen.findByRole("dialog");
  for(const item of items.slice(0,8))await user.click(await within(dialog).findByRole("button",{name:item.title}));
  expect(within(dialog).getByRole("button",{name:items[8]!.title})).toBeDisabled();
  await user.click(within(dialog).getByRole("button",{name:items[0]!.title}));
  expect(within(dialog).getByRole("button",{name:items[8]!.title})).toBeEnabled();
  await user.click(within(dialog).getByRole("button",{name:items[8]!.title}));
  expect(within(dialog).getByText("已选 8 / 8 个 Skill")).toBeVisible();
});
