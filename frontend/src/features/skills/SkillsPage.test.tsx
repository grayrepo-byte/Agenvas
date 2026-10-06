import { QueryClientProvider } from "@tanstack/react-query";
import { cleanup,fireEvent,render,screen,waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http,HttpResponse } from "msw";
import { MemoryRouter,Route,Routes,useLocation } from "react-router";
import { afterEach,beforeEach,describe,expect,it,vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import type { CreativeSkill,SkillDraft,SkillVersion,SaveSkillDraftRequest } from "../../shared/api/client";
import { server } from "../../test/server";
import { SkillsPage } from "./SkillsPage";

const SKILL_ID="skill-1",VERSION_ID="skill-version-1",NOW="2026-10-02T00:00:00Z";
const SKILL:CreativeSkill={id:SKILL_ID,title:"温暖手绘",description:"",trashed:false,builtin:false,currentVersionId:VERSION_ID,version:1,createdAt:NOW,updatedAt:NOW};
const INITIAL:SkillDraft={skillId:SKILL_ID,version:1,skillMd:"---\nname: warm-art\ndescription: A warm illustration guide.\n---\n# Method",outputKinds:["IMAGE"],inputSlots:[],resources:[],assets:[]};
const VERSION:SkillVersion={id:VERSION_ID,skillId:SKILL_ID,versionNumber:1,name:"warm-art",description:"A warm illustration guide.",bundleHash:"synthetic-hash",skillMd:INITIAL.skillMd,outputKinds:["IMAGE"],inputSlots:[],resources:[],assets:[],createdAt:NOW};
function Destination(){return <p data-testid="destination">{useLocation().search}</p>;}
function mount(){const client=createQueryClient();client.setDefaultOptions({queries:{retry:false}});render(<QueryClientProvider client={client}><MemoryRouter initialEntries={["/skills"]}><Routes><Route path="/skills" element={<SkillsPage />} /><Route path="/projects/:id" element={<Destination />} /></Routes></MemoryRouter></QueryClientProvider>);return client;}
beforeEach(()=>{server.use(
  http.get("/api/v1/auth/me",()=>HttpResponse.json({id:"user-1",loginName:"mock-user",role:"ADMIN"})),
  http.get("/api/v1/auth/csrf",()=>HttpResponse.json({token:"synthetic-csrf",headerName:"X-CSRF-TOKEN"})),
  http.get("/api/v1/skills",()=>HttpResponse.json({items:[SKILL],nextCursor:null,total:1})),
  http.get(`/api/v1/skills/${SKILL_ID}`,()=>HttpResponse.json(SKILL)),
  http.get(`/api/v1/skills/${SKILL_ID}/draft`,()=>HttpResponse.json(INITIAL)),
  http.get(`/api/v1/skills/${SKILL_ID}/versions`,()=>HttpResponse.json([{id:VERSION_ID,skillId:SKILL_ID,versionNumber:1,name:VERSION.name,description:VERSION.description,bundleHash:VERSION.bundleHash,createdAt:NOW}])),
  http.get(`/api/v1/skills/${SKILL_ID}/versions/${VERSION_ID}`,()=>HttpResponse.json(VERSION)),
);});
afterEach(()=>{cleanup();vi.restoreAllMocks();});
it("shows builtin content read-only with attribution and permits trying its fixed version",async()=>{
  const builtin={...SKILL,title:"分集剧本 · short-drama-write",builtin:true};let draftReads=0;
  server.use(http.get("/api/v1/skills",()=>HttpResponse.json({items:[builtin],nextCursor:null,total:1})),
    http.get(`/api/v1/skills/${SKILL_ID}/draft`,()=>{draftReads++;return HttpResponse.json(INITIAL);}),
    http.get("/api/v1/projects",()=>HttpResponse.json({items:[],nextCursor:null,total:0})));
  mount();await userEvent.click(await screen.findByRole("button",{name:builtin.title}));
  expect(await screen.findByText(/# Method/)).toHaveTextContent("name: warm-art");
  expect(screen.getByRole("link",{name:"Drama Skills · MIT"})).toHaveAttribute("href","https://github.com/zenstory-ai/drama-skills");
  expect(screen.queryByRole("button",{name:"发布版本"})).not.toBeInTheDocument();
  expect(screen.queryByRole("button",{name:"移入回收站"})).not.toBeInTheDocument();
  expect(screen.queryByRole("textbox",{name:"Skill 正文"})).not.toBeInTheDocument();
  expect(draftReads).toBe(0);
  await userEvent.click(screen.getByRole("button",{name:"在 Agent 中试用"}));
  expect(await screen.findByRole("dialog",{name:"在 Agent 中试用"})).toBeVisible();
});
async function openEditor(){mount();await userEvent.click(await screen.findByRole("button",{name:"温暖手绘"}));return screen.findByRole("textbox",{name:"Skill 正文"});}
describe("Skill editing and publishing",()=>{
  it("moves a saved Skill to trash, lists it there, and restores editing with metadata CAS",async()=>{
    let stored=SKILL;const filters:string[]=[];const changes:{expectedVersion:number;trashed:boolean}[]=[];
    server.use(http.get("/api/v1/skills",({request})=>{
      const trash=new URL(request.url).searchParams.get("trash")??"false";filters.push(trash);
      const items=stored.trashed===(trash==="true")?[stored]:[];
      return HttpResponse.json({items,nextCursor:null,total:items.length});
    }),http.patch(`/api/v1/skills/${SKILL_ID}`,async({request})=>{
      const changed=await request.json() as {expectedVersion:number;title:string;description:string;trashed:boolean};changes.push(changed);
      stored={...stored,...changed,version:stored.version+1};return HttpResponse.json(stored);
    }));
    const body=await openEditor();await userEvent.click(screen.getByRole("button",{name:"移入回收站"}));
    await screen.findByText("此 Skill 已在回收站。恢复后才能编辑、发布或试用；历史运行保留固定版本。");
    expect(body).toHaveValue(INITIAL.skillMd);expect(body).toHaveAttribute("readonly");
    expect(screen.queryByRole("button",{name:"发布版本"})).not.toBeInTheDocument();
    expect(screen.queryByRole("button",{name:"在 Agent 中试用"})).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole("radio",{name:"回收站"}));
    expect(await screen.findByRole("button",{name:"温暖手绘"})).toHaveAttribute("aria-pressed","true");
    await userEvent.click(screen.getByRole("button",{name:"恢复 Skill"}));
    await waitFor(()=>expect(body).not.toHaveAttribute("readonly"));
    expect(changes.map(({expectedVersion,trashed})=>({expectedVersion,trashed}))).toEqual([{expectedVersion:1,trashed:true},{expectedVersion:2,trashed:false}]);
    expect(filters).toContain("true");await userEvent.click(screen.getByRole("radio",{name:"可用 Skill"}));
    expect(await screen.findByRole("button",{name:"温暖手绘"})).toHaveAttribute("aria-pressed","true");
  });
  it("keeps the selected saved draft after a trash conflict and refreshes metadata before retrying",async()=>{
    const changes:number[]=[];
    server.use(http.get(`/api/v1/skills/${SKILL_ID}`,()=>HttpResponse.json({...SKILL,version:4})),
      http.patch(`/api/v1/skills/${SKILL_ID}`,async({request})=>{
        const changed=await request.json() as {expectedVersion:number;trashed:boolean};changes.push(changed.expectedVersion);
        return changed.expectedVersion===4 ? HttpResponse.json({...SKILL,...changed,version:5}) : HttpResponse.json({code:"SKILL_VERSION_CONFLICT",detail:"synthetic conflict"},{status:409});
      }));
    const body=await openEditor();await userEvent.click(screen.getByRole("button",{name:"移入回收站"}));
    await screen.findByText("服务器版本已变化。草稿已保留；刷新后核对并重试。");
    expect(body).toHaveValue(INITIAL.skillMd);expect(body).not.toHaveAttribute("readonly");
    expect(screen.getByRole("button",{name:"温暖手绘"})).toHaveAttribute("aria-pressed","true");
    await userEvent.click(screen.getByRole("button",{name:"刷新服务器版本"}));
    await waitFor(()=>expect(screen.queryByText("服务器版本已变化。草稿已保留；刷新后核对并重试。")).not.toBeInTheDocument());
    await userEvent.click(screen.getByRole("button",{name:"移入回收站"}));
    await waitFor(()=>expect(body).toHaveAttribute("readonly"));expect(changes).toEqual([1,4]);
  });
  it("refreshes the catalogue version after publication before moving it to trash",async()=>{
    const changes:number[]=[];
    server.use(http.post(`/api/v1/skills/${SKILL_ID}/versions`,()=>HttpResponse.json({id:"published-cas",skillId:SKILL_ID,status:"SUCCEEDED",resultVersionId:VERSION_ID,errorCode:null,errorDetail:null})),
      http.get("/api/v1/skills/operations/published-cas",()=>HttpResponse.json({id:"published-cas",skillId:SKILL_ID,status:"SUCCEEDED",resultVersionId:VERSION_ID,errorCode:null,errorDetail:null})),
      http.get(`/api/v1/skills/${SKILL_ID}`,()=>HttpResponse.json({...SKILL,version:2})),
      http.patch(`/api/v1/skills/${SKILL_ID}`,async({request})=>{
        const changed=await request.json() as {expectedVersion:number;trashed:boolean};changes.push(changed.expectedVersion);
        return changed.expectedVersion===2 ? HttpResponse.json({...SKILL,...changed,version:3}) : HttpResponse.json({code:"SKILL_VERSION_CONFLICT",detail:"synthetic publication changed catalogue"},{status:409});
      }));
    const body=await openEditor();await userEvent.click(screen.getByRole("button",{name:"发布版本"}));await screen.findByText("版本已发布");
    await waitFor(()=>expect(screen.getByRole("button",{name:"移入回收站"})).toBeEnabled());
    await userEvent.click(screen.getByRole("button",{name:"移入回收站"}));
    await waitFor(()=>expect(body).toHaveAttribute("readonly"));expect(changes).toEqual([2]);
  });
  it("preserves title and document edits made during publication and requires saving them before trashing",async()=>{
    let finishPublication:()=>void=()=>{},finishSave:()=>void=()=>{};
    const publicationResponse=new Promise<void>((resolve)=>{finishPublication=resolve;});
    const draftResponse=new Promise<void>((resolve)=>{finishSave=resolve;});
    const titles:{expectedVersion:number;title:string}[]=[];const drafts:SaveSkillDraftRequest[]=[];
    server.use(http.post(`/api/v1/skills/${SKILL_ID}/versions`,()=>HttpResponse.json({id:"publishing-edits",skillId:SKILL_ID,status:"ACCEPTED",resultVersionId:null,errorCode:null,errorDetail:null})),
      http.get("/api/v1/skills/operations/publishing-edits",async()=>{await publicationResponse;return HttpResponse.json({id:"publishing-edits",skillId:SKILL_ID,status:"SUCCEEDED",resultVersionId:VERSION_ID,errorCode:null,errorDetail:null});}),
      http.get(`/api/v1/skills/${SKILL_ID}`,()=>HttpResponse.json({...SKILL,version:2})),
      http.patch(`/api/v1/skills/${SKILL_ID}`,async({request})=>{const changed=await request.json() as {expectedVersion:number;title:string};titles.push(changed);return HttpResponse.json({...SKILL,...changed,version:3});}),
      http.put(`/api/v1/skills/${SKILL_ID}/draft`,async({request})=>{const changed=await request.json() as SaveSkillDraftRequest;drafts.push(changed);await draftResponse;return HttpResponse.json({...INITIAL,...changed,version:2});}));
    const body=await openEditor();await userEvent.click(screen.getByRole("button",{name:"发布版本"}));await screen.findByText("正在归档并发布…");
    const title=document.getElementById(`skill-title-${SKILL_ID}`)!;
    fireEvent.change(title,{target:{value:"A newer display name"}});fireEvent.change(body,{target:{value:"# Later document edit"}});
    expect(screen.getByRole("button",{name:"移入回收站"})).toBeDisabled();finishPublication();
    await waitFor(()=>expect(drafts).toHaveLength(1),{timeout:4000});
    expect(titles).toEqual([expect.objectContaining({expectedVersion:2,title:"A newer display name"})]);
    expect(drafts[0]?.skillMd).toBe("# Later document edit");expect(screen.getByRole("button",{name:"移入回收站"})).toBeDisabled();
    finishSave();await waitFor(()=>expect(screen.getByRole("button",{name:"移入回收站"})).toBeEnabled());
    expect(body).toHaveValue("# Later document edit");expect(title).toHaveValue("A newer display name");
  });
  it("retains edited document after a draft CAS conflict",async()=>{
    let submitted:SaveSkillDraftRequest|undefined;
    server.use(http.put(`/api/v1/skills/${SKILL_ID}/draft`,async({request})=>{submitted=await request.json() as SaveSkillDraftRequest;return HttpResponse.json({code:"SKILL_DRAFT_VERSION_CONFLICT",detail:"synthetic conflict"},{status:409});}));
    const input=await openEditor();fireEvent.change(input,{target:{value:"# My edited creative method"}});
    await screen.findByText("服务器版本已变化。草稿已保留；刷新后核对并重试。");
    expect(input).toHaveValue("# My edited creative method");expect(submitted?.expectedVersion).toBe(1);expect(submitted?.skillMd).toBe("# My edited creative method");
  });
  it("saves a newer title typed while an earlier draft save is pending",async()=>{
    let releaseFirst:()=>void=()=>{};
    const firstResponse=new Promise<void>((resolve)=>{releaseFirst=resolve;});
    const titles:string[]=[];let draftCalls=0;let catalogueVersion=SKILL.version;
    server.use(http.patch(`/api/v1/skills/${SKILL_ID}`,async({request})=>{
      const change=await request.json() as {title:string;expectedVersion:number};
      titles.push(change.title);catalogueVersion++;
      return HttpResponse.json({...SKILL,title:change.title,version:catalogueVersion});
    }),http.put(`/api/v1/skills/${SKILL_ID}/draft`,async({request})=>{
      const saved=await request.json() as SaveSkillDraftRequest;draftCalls++;
      if(draftCalls===1)await firstResponse;
      return HttpResponse.json({...INITIAL,...saved,version:saved.expectedVersion+1});
    }));
    await openEditor();const title=document.getElementById(`skill-title-${SKILL_ID}`)!;
    fireEvent.change(title,{target:{value:"First title"}});
    await waitFor(()=>expect(draftCalls).toBe(1));
    fireEvent.change(title,{target:{value:"Latest title"}});releaseFirst();
    await waitFor(()=>expect(titles).toEqual(["First title","Latest title"]),{timeout:4000});
    await waitFor(()=>expect(draftCalls).toBe(2));expect(title).toHaveValue("Latest title");
  });
  it("publishes a fixed version through a durable operation without model calls",async()=>{
    const publish=vi.fn();let queries=0;const run=vi.fn();
    server.use(http.post(`/api/v1/skills/${SKILL_ID}/versions`,async({request})=>{publish(await request.json());return HttpResponse.json({id:"operation-1",skillId:SKILL_ID,status:"ACCEPTED",resultVersionId:null,errorCode:null,errorDetail:null});}),
      http.get("/api/v1/skills/operations/operation-1",()=>{queries++;return HttpResponse.json({id:"operation-1",skillId:SKILL_ID,status:"SUCCEEDED",resultVersionId:VERSION_ID,errorCode:null,errorDetail:null});}),
      http.post("/api/v1/projects/:id/runs",()=>{run();return HttpResponse.json({});}));
    await openEditor();await userEvent.click(screen.getByRole("button",{name:"发布版本"}));await screen.findByText("版本已发布");
    expect(publish).toHaveBeenCalledWith(expect.objectContaining({expectedDraftVersion:1,commandKey:expect.any(String)}));expect(queries).toBeGreaterThan(0);expect(run).not.toHaveBeenCalled();expect(await screen.findByText("这是不可变发布版本；编辑不会改变它。")).toBeInTheDocument();
  });
  it("opens an Agent try selection without submitting a Run",async()=>{
    const run=vi.fn();server.use(http.post("/api/v1/projects/:id/runs",()=>{run();return HttpResponse.json({});}),
      http.get("/api/v1/projects",()=>HttpResponse.json({items:[{id:"project-1",name:"Mock canvas",status:"ACTIVE"}],nextCursor:null})),
      http.get("/api/v1/projects/project-1/canvas/items",()=>HttpResponse.json({items:[{id:"agent-item",agent:{id:"agent-1",name:"Mock Agent"}}]})));
    await openEditor();const native=document.querySelector<HTMLSelectElement>('select[aria-hidden="true"]');expect(native).not.toBeNull();fireEvent.change(native!,{target:{value:VERSION_ID}});
    await userEvent.click(await screen.findByRole("button",{name:"在 Agent 中试用"}));
    const project=screen.getByRole("combobox",{name:"项目"});await userEvent.click(project);await userEvent.click(await screen.findByRole("option",{name:"Mock canvas"}));
    const agent=screen.getByRole("combobox",{name:"Agent 节点"});await waitFor(()=>expect(agent).toBeEnabled());await userEvent.click(agent);await userEvent.click(await screen.findByRole("option",{name:"Mock Agent"}));
    await userEvent.click(screen.getByRole("button",{name:"打开 Agent"}));expect(await screen.findByTestId("destination")).toHaveTextContent("agentId=agent-1&skillId=skill-1&skillVersionId=skill-version-1");expect(run).not.toHaveBeenCalled();
  });
});
