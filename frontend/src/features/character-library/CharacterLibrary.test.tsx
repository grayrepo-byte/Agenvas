import { QueryClientProvider } from "@tanstack/react-query";
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { expect, it, vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import { changeControl } from "../../test/controls";
import { server } from "../../test/server";
import { CharacterManagerDialog } from "../characters/CharacterManagerDialog";
import { setLocale } from "../../shared/i18n";
import { CharacterLibraryMediaPicker } from "./CharacterLibraryMediaPicker";
import { PublishCharacterDialog } from "./PublishCharacterDialog";
import { CharacterLibrary } from "./CharacterLibrary";
import { CharacterLibraryImportDialog } from "./CharacterLibraryImportDialog";
const ROOT = "/api/v1/character-library";
const content = { name: "Mira", persona: "Detective", appearance: "Blue eyes", identityViews: [], looks: [], voice: null };
const profile = { id: "library-one", content, version: 2, archived: false, createdAt: "2026-11-06T00:00:00Z", updatedAt: "2026-11-06T00:00:00Z" };
function setup() {
  server.use(http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
    http.get(`${ROOT}/profiles/:id`, () => HttpResponse.json(profile)),
    http.get("/api/v1/projects/project-one/characters", () => HttpResponse.json({characters:[{id:"project-role",version:4,archived:false}],bindings:[]})),
    http.get(`${ROOT}/profiles`, () => HttpResponse.json({ items: [profile], nextCursor: null })));
}
it("preserves a failed draft and explicitly replays the original save command", async () => {
  setup(); const writes: Array<{commandId: string; content: typeof content}> = [];
  server.use(http.put(`${ROOT}/profiles/:id`, async ({ request }) => {
    const input = await request.json() as typeof writes[number]; writes.push(input);
    return writes.length === 1 ? HttpResponse.error() : HttpResponse.json({ ...profile, content: input.content, version: 3 });
  }));
  render(<QueryClientProvider client={createQueryClient()}><CharacterLibrary /></QueryClientProvider>);
  await userEvent.click(await screen.findByRole("button", { name: "Mira" }));
  fireEvent.change(screen.getByLabelText("人设与身份"), { target: { value: "Edited detective" } });
  await userEvent.click(screen.getByRole("button", { name: "保存角色库档案" }));
  expect(await screen.findByText("结果尚未确认。核对会重放同一命令，不会重复创建。" )).toBeVisible();
  expect(screen.getByLabelText("人设与身份")).toHaveValue("Edited detective");
  await userEvent.click(screen.getByRole("button", { name: "核对原命令" }));
  await screen.findByText("角色库档案已保存");
  expect(writes).toHaveLength(2); expect(writes[1]).toEqual(writes[0]);
});
it("requires preview before import and requires a new preview after a sync conflict", async () => {
  setup(); let previews = 0; let commits = 0;
  server.use(http.post("/api/v1/projects/project-one/character-library/imports/preview", async ({ request }) => {
    previews++; return HttpResponse.json({ ...await request.json() as object, previewHash: `hash-${previews}`, content, currentCharacter:{id:"project-role",projectId:"project-one",version:4,archived:false,createdAt:profile.createdAt,updatedAt:profile.updatedAt,content:{name:"Mira",description:"Prior persona",looks:[],voice:null}}, affectedItems: [{canvasItemId: "shot", title: "Shot 1", bindingVersion: 1}], changes: ["persona"] });
  }), http.post("/api/v1/projects/project-one/character-library/imports", () => {
    commits++; return HttpResponse.json({code: "VERSION_CONFLICT", detail: "Stale preview"}, {status: 409, headers: {"Content-Type":"application/problem+json"}});
  }));
  render(<QueryClientProvider client={createQueryClient()}><CharacterLibraryImportDialog projectId="project-one" source={profile} characterId="project-role" expectedCharacterVersion={4} onClose={() => {}} /></QueryClientProvider>);
  expect(screen.queryByRole("button", { name: "确认导入或同步" })).not.toBeInTheDocument();
  await userEvent.click(screen.getByRole("button", { name: "预览导入或同步" }));
  await screen.findByText("Shot 1"); expect(commits).toBe(0);
  await userEvent.click(screen.getByRole("button", { name: "确认导入或同步" }));
  await screen.findByText(/Stale preview/);
  expect(screen.queryByRole("button", { name: "确认导入或同步" })).not.toBeInTheDocument();
  await userEvent.click(screen.getByRole("button", { name: "预览导入或同步" }));
  await waitFor(() => expect(previews).toBe(2));
});
it("creates a complete profile without converting it into an ordinary asset", async () => {
  setup(); let saved: {commandId: string; expectedVersion: number; content: typeof content & {looks: Array<{name:string;clothing:string}>}} | undefined;
  server.use(http.get(`${ROOT}/profiles`, () => HttpResponse.json({items:[],nextCursor:null})), http.put(`${ROOT}/profiles/:id`, async ({request,params}) => {
    saved = await request.json() as typeof saved; return HttpResponse.json({...profile,id:params.id,version:1,content:saved?.content});
  }), http.get(`${ROOT}/profiles/:id`, ({params}) => HttpResponse.json({...profile,id:params.id,version:1,content:saved?.content})));
  render(<QueryClientProvider client={createQueryClient()}><CharacterLibrary /></QueryClientProvider>);
  await userEvent.click(screen.getByRole("button", {name:"新建库角色"}));
  fireEvent.change(screen.getByLabelText("角色名称"),{target:{value:"Ada"}});
  fireEvent.change(screen.getByLabelText("人设与身份"),{target:{value:"Explorer"}});
  fireEvent.change(screen.getByLabelText("外观描述"),{target:{value:"Freckles"}});
  await userEvent.click(screen.getByRole("button",{name:"添加造型"}));
  fireEvent.change(screen.getByLabelText("造型名称"),{target:{value:"Winter"}});
  fireEvent.change(screen.getByLabelText("衣着"),{target:{value:"Red scarf"}});
  await userEvent.click(screen.getByRole("button",{name:"保存角色库档案"}));
  await screen.findByText("角色库档案已保存");
  expect(saved).toMatchObject({expectedVersion:0,content:{name:"Ada",persona:"Explorer",appearance:"Freckles",identityViews:[],looks:[{name:"Winter",clothing:"Red scarf"}],voice:null}});
  expect(saved).not.toHaveProperty("mode");
});
it("replays an unknown import with the exact preview and command identity", async () => {
  setup(); const writes: unknown[]=[];
  server.use(http.post("/api/v1/projects/project-one/character-library/imports/preview",async({request})=>HttpResponse.json({...await request.json() as object,previewHash:"frozen-hash",content,affectedItems:[],changes:[]})),
    http.post("/api/v1/projects/project-one/character-library/imports",async({request})=>{const input=await request.json() as {commandId:string};writes.push(input);return writes.length===1?HttpResponse.error():HttpResponse.json({id:input.commandId,status:"SUCCEEDED",result:{characterId:"copied-role"},errorCode:null,errorDetail:null});}),
    http.get(`${ROOT}/commands/:id`,({params})=>HttpResponse.json({id:params.id,status:"SUCCEEDED",result:{characterId:"copied-role"},errorCode:null,errorDetail:null})),
    http.get("/api/v1/projects/project-one/artifacts",()=>HttpResponse.json({items:[]})));
  render(<QueryClientProvider client={createQueryClient()}><CharacterLibraryImportDialog projectId="project-one" source={profile} onClose={()=>{}} /></QueryClientProvider>);
  await userEvent.click(screen.getByRole("button",{name:"预览导入或同步"}));
  await userEvent.click(await screen.findByRole("button",{name:"确认导入或同步"}));
  await screen.findByText("结果尚未确认。核对会重放同一命令，不会重复创建。");
  expect(screen.getByRole("button",{name:"预览导入或同步"})).toBeDisabled();
  expect(screen.getByRole("button",{name:"关闭窗口"})).toBeDisabled();
  await userEvent.click(screen.getByRole("button",{name:"核对原命令"}));
  await screen.findByText("项目角色已更新");
  expect(writes).toHaveLength(2);expect(writes[1]).toEqual(writes[0]);
});
it("shows an unauthorized library read without exposing cached profiles", async()=>{
  setup();server.use(http.get(`${ROOT}/profiles`,()=>HttpResponse.json({code:"UNAUTHORIZED",detail:"Sign in required"},{status:401,headers:{"Content-Type":"application/problem+json"}})));
  render(<QueryClientProvider client={createQueryClient()}><CharacterLibrary /></QueryClientProvider>);
  expect(await screen.findByText("Sign in required")).toBeVisible();
  expect(screen.queryByRole("button",{name:"Mira"})).not.toBeInTheDocument();
});
it("rejects local video and archives an image before choosing its library media identity", async()=>{
  setup(); const selected=vi.fn(); const writes: Array<{id:string;kind:unknown;file:File}>=[];
  server.use(http.get("/api/v1/projects",()=>HttpResponse.json({items:[],nextCursor:null})),http.get(`${ROOT}/commands/:id`,({params})=>HttpResponse.json({id:params.id,status:"SUCCEEDED",result:{mediaId:"independent-image"},errorCode:null,errorDetail:null})));
  // Inspect the browser multipart body before Node fetch serializes jsdom File objects.
  const originalFetch=globalThis.fetch;
  vi.spyOn(globalThis,"fetch").mockImplementation((path,init)=>{
    if(path===`${ROOT}/media/uploads`){const form=init?.body as FormData;const commandId=String(form.get("commandId"));writes.push({id:commandId,kind:form.get("kind"),file:form.get("file") as File});expect(new Headers(init?.headers).has("Content-Type")).toBe(false);expect(init?.credentials).toBe("same-origin");return Promise.resolve(Response.json({id:commandId,status:"SUCCEEDED",result:{mediaId:"independent-image"},errorCode:null,errorDetail:null}));}
    return originalFetch(path,init);
  });
  render(<QueryClientProvider client={createQueryClient()}><CharacterLibraryMediaPicker kind="IMAGE" onSelect={selected} onClose={()=>{}} /></QueryClientProvider>);
  const input=screen.getByLabelText("图片或音频文件");
  fireEvent.change(input,{target:{files:[new File(["video"],"wrong.mp4",{type:"video/mp4"})]}});
  expect(screen.getByRole("button",{name:"上传并独立归档"})).toBeDisabled();
  fireEvent.change(input,{target:{files:[new File(["synthetic image"],"front.png",{type:"image/png"})]}});
  await userEvent.click(screen.getByRole("button",{name:"上传并独立归档"}));
  await userEvent.click(await screen.findByRole("button",{name:"选用"}));
  expect(selected).toHaveBeenCalledWith("independent-image");expect(writes).toHaveLength(1);expect(writes[0]).toMatchObject({kind:"IMAGE",file:{name:"front.png",type:"image/png"}});
});
it("switches all four interface locales without clearing an unsaved character",async()=>{
 setup();render(<QueryClientProvider client={createQueryClient()}><CharacterLibrary /></QueryClientProvider>);
 await userEvent.click(await screen.findByRole("button",{name:"Mira"}));
 fireEvent.change(screen.getByLabelText("人设与身份"),{target:{value:"Local draft"}});
 for(const [locale,label] of [["en","Persona and identity"],["ru","Личность и характер"],["ja","人物設定とアイデンティティ"],["zh","人设与身份"]] as const){act(()=>{setLocale(locale);});expect(screen.getByLabelText(label)).toHaveValue("Local draft");}
});
it("publishes the exact saved project revision as an independent library command",async()=>{
 setup();let published:unknown;
 server.use(http.post(`${ROOT}/profiles/:id/from-project`,async({request})=>{const input=await request.json() as {commandId:string};published=input;return HttpResponse.json({id:input.commandId,status:"SUCCEEDED",result:{profile},errorCode:null,errorDetail:null});}),http.get(`${ROOT}/commands/:id`,({params})=>HttpResponse.json({id:params.id,status:"SUCCEEDED",result:{profile},errorCode:null,errorDetail:null})));
 const projectRole={id:"project-role",projectId:"project-one",version:7,archived:false,createdAt:profile.createdAt,updatedAt:profile.updatedAt,content:{name:"Mira",description:"Project persona",looks:[],voice:null}};
 render(<QueryClientProvider client={createQueryClient()}><PublishCharacterDialog projectId="project-one" profile={projectRole} onClose={()=>{}} /></QueryClientProvider>);
 await userEvent.click(screen.getByRole("button",{name:"发布已保存角色到库"}));
 await screen.findByText("已发布到角色库");
 expect(published).toMatchObject({expectedVersion:0,sourceProjectId:"project-one",sourceCharacterId:"project-role",sourceCharacterVersion:7,identityVersionIds:[]});
});
it("verifies durable status after a lost retry receipt before permitting another transfer",async()=>{
 setup();let retried=false;let retries=0;let submits=0;
 const receipt=()=>({id:"durable-command",status:retried?"SUCCEEDED":"FAILED",result:retried?{characterId:"project-role"}:null,errorCode:retried?null:"CHARACTER_LIBRARY_TRANSFER_FAILED",errorDetail:retried?null:"Temporary copy failure"});
 server.use(http.post("/api/v1/projects/project-one/character-library/imports/preview",async({request})=>HttpResponse.json({...await request.json() as object,previewHash:"same-preview",content,affectedItems:[],changes:[]})),http.post("/api/v1/projects/project-one/character-library/imports",()=>{submits++;return HttpResponse.json(receipt());}),http.get(`${ROOT}/commands/durable-command`,()=>HttpResponse.json(receipt())),http.post(`${ROOT}/commands/durable-command/retry`,()=>{retries++;retried=true;return HttpResponse.error();}));
 render(<QueryClientProvider client={createQueryClient()}><CharacterLibraryImportDialog projectId="project-one" source={profile} onClose={()=>{}} /></QueryClientProvider>);
 await userEvent.click(screen.getByRole("button",{name:"预览导入或同步"}));await userEvent.click(await screen.findByRole("button",{name:"确认导入或同步"}));
 await userEvent.click(await screen.findByRole("button",{name:"重试原转移"}));
 await screen.findByRole("button",{name:"核对命令状态"});expect(screen.queryByRole("button",{name:"重试原转移"})).not.toBeInTheDocument();expect(screen.getByRole("button",{name:"预览导入或同步"})).toBeDisabled();
 await userEvent.click(screen.getByRole("button",{name:"核对命令状态"}));await screen.findByText("项目角色已更新");expect(retries).toBe(1);expect(submits).toBe(1);
});
it("refreshes a clean project editor after explicit sync while preserving dirty project fields",async()=>{
 setup();let current={id:"project-role",projectId:"project-one",version:1,archived:false,createdAt:profile.createdAt,updatedAt:profile.updatedAt,content:{name:"Mira",description:"Original project persona",looks:[],voice:null}};
 server.use(http.get("/api/v1/projects/project-one/characters",()=>HttpResponse.json({characters:[current],bindings:[]})),http.get("/api/v1/projects/project-one/canvas/items",()=>HttpResponse.json({items:[]})),http.get("/api/v1/projects/project-one/artifacts",()=>HttpResponse.json({items:[]})),http.get("/api/v1/projects/project-one/character-library/links",()=>HttpResponse.json({items:[{characterId:"project-role",libraryId:profile.id,libraryVersion:1,currentLibraryVersion:2,libraryArchived:false,projectCharacterVersion:current.version,hasUpdate:current.version===1}]})),
 http.post("/api/v1/projects/project-one/character-library/imports/preview",async({request})=>HttpResponse.json({...await request.json() as object,previewHash:"sync-hash",content,currentCharacter:current,affectedItems:[],changes:[]})),http.post("/api/v1/projects/project-one/character-library/imports",async({request})=>{const input=await request.json() as {commandId:string};current={...current,version:current.version+1,content:{...current.content,description:"Synchronized persona"}};return HttpResponse.json({id:input.commandId,status:"SUCCEEDED",result:{characterId:current.id},errorCode:null,errorDetail:null});}),http.get(`${ROOT}/commands/:id`,({params})=>HttpResponse.json({id:params.id,status:"SUCCEEDED",result:{characterId:current.id},errorCode:null,errorDetail:null})));
 render(<QueryClientProvider client={createQueryClient()}><CharacterManagerDialog projectId="project-one" onClose={()=>{}} /></QueryClientProvider>);
 await userEvent.click(await screen.findByRole("button",{name:"Mira"}));
 async function sync(){await userEvent.click(screen.getByRole("button",{name:"预览库角色更新"}));await userEvent.click(await screen.findByRole("button",{name:"预览导入或同步"}));await userEvent.click(await screen.findByRole("button",{name:"确认导入或同步"}));await screen.findByText("项目角色已更新");await userEvent.click(screen.getByRole("button",{name:"关闭窗口"}));}
 await sync();await waitFor(()=>expect(screen.getByLabelText("角色设定")).toHaveValue("Synchronized persona"));
 fireEvent.change(screen.getByLabelText("角色设定"),{target:{value:"Unsaved project edit"}});
 await sync();await waitFor(()=>expect(current.version).toBe(3));expect(screen.getByLabelText("角色设定")).toHaveValue("Unsaved project edit");
});
it("archives the selected canvas revision even when a newer artifact version exists",async()=>{
 setup();let submitted:unknown;const command={id:"canvas-media-command",status:"SUCCEEDED",result:{mediaId:"canvas-archive"},errorCode:null,errorDetail:null};
 server.use(http.get("/api/v1/projects",()=>HttpResponse.json({items:[{id:"source-project",name:"Source",status:"ACTIVE"}],nextCursor:null})),http.get("/api/v1/projects/source-project/artifacts",()=>HttpResponse.json({items:[{id:"image-artifact",kind:"IMAGE",title:"Portrait"}]})),http.get("/api/v1/projects/source-project/canvas/items",()=>HttpResponse.json({items:[{selectedVersionId:"fixed-version"}]})),http.get("/api/v1/projects/source-project/artifacts/image-artifact/versions",()=>HttpResponse.json({items:[{id:"latest-version",versionNo:2,content:{assetId:"latest-asset"}},{id:"fixed-version",versionNo:1,content:{assetId:"fixed-asset"}}]})),http.post(`${ROOT}/media`,async({request})=>{submitted=await request.json();return HttpResponse.json(command);}),http.get(`${ROOT}/commands/${command.id}`,()=>HttpResponse.json(command)));
 render(<QueryClientProvider client={createQueryClient()}><CharacterLibraryMediaPicker kind="IMAGE" onSelect={()=>{}} onClose={()=>{}} /></QueryClientProvider>);
 await userEvent.click(screen.getByRole("tab",{name:"项目精确版本"}));await screen.findByRole("combobox",{name:"来源项目"});await changeControl(screen.getByRole("combobox",{name:"来源项目"}),{target:{value:"source-project"}});await userEvent.click(screen.getByRole("button",{name:"项目精确版本"}));
 expect(await screen.findByText("Portrait · v1")).toBeVisible();expect(screen.queryByText("Portrait · v2")).not.toBeInTheDocument();await userEvent.click(screen.getByRole("button",{name:"选用此版本"}));
 await screen.findByRole("button",{name:"选用"});expect(submitted).toMatchObject({sourceType:"PROJECT_VERSION",projectId:"source-project",versionId:"fixed-version",entryId:null,expectedEntryVersion:null});
});
it("archives a personal asset using its observed version without treating it as a full profile",async()=>{
 setup();let submitted:unknown;const command={id:"asset-media-command",status:"SUCCEEDED",result:{mediaId:"asset-archive"},errorCode:null,errorDetail:null};
 server.use(http.get("/api/v1/projects",()=>HttpResponse.json({items:[],nextCursor:null})),http.get("/api/v1/library/entries",()=>HttpResponse.json({items:[{id:"ordinary-entry",version:7,name:"Identity photo",kind:"IMAGE",category:"CHARACTER",source:{},hasThumbnail:false,favorite:false,createdAt:profile.createdAt,updatedAt:profile.updatedAt}],total:1,nextCursor:null,categoryCounts:{CHARACTER:1}})),http.post(`${ROOT}/media`,async({request})=>{submitted=await request.json();return HttpResponse.json(command);}),http.get(`${ROOT}/commands/${command.id}`,()=>HttpResponse.json(command)));
 render(<QueryClientProvider client={createQueryClient()}><CharacterLibraryMediaPicker kind="IMAGE" onSelect={()=>{}} onClose={()=>{}} /></QueryClientProvider>);
 await userEvent.click(screen.getByRole("tab",{name:"个人资产"}));await userEvent.click(await screen.findByRole("button",{name:"查看 Identity photo"}));await screen.findByRole("button",{name:"选用"});
 expect(submitted).toMatchObject({sourceType:"LIBRARY_ENTRY",entryId:"ordinary-entry",expectedEntryVersion:7,projectId:null,versionId:null});
});
it.each(["escape", "close"] as const)("protects unsaved library edits when closing through %s",async(method)=>{
 setup();render(<QueryClientProvider client={createQueryClient()}><CharacterLibrary /></QueryClientProvider>);
 await userEvent.click(await screen.findByRole("button",{name:"Mira"}));fireEvent.change(screen.getByLabelText("人设与身份"),{target:{value:"Unsaved identity"}});
 if(method==="escape")await userEvent.keyboard("{Escape}");else await userEvent.click(screen.getByRole("button",{name:"关闭窗口"}));
 expect(await screen.findByRole("dialog",{name:"有未保存的修改"})).toBeVisible();
 expect(screen.getByRole("button",{name:"继续编辑"})).toHaveFocus();await userEvent.keyboard("{Escape}");expect(screen.getByLabelText("人设与身份")).toHaveValue("Unsaved identity");
 await userEvent.click(screen.getByRole("button",{name:"关闭窗口"}));await userEvent.click(screen.getByRole("button",{name:"放弃修改"}));expect(screen.queryByLabelText("人设与身份")).not.toBeInTheDocument();
});
it("preserves a conflicted draft when cancelling its close confirmation",async()=>{
 setup();server.use(http.put(`${ROOT}/profiles/:id`,()=>HttpResponse.json({code:"VERSION_CONFLICT",detail:"Profile changed"},{status:409,headers:{"Content-Type":"application/problem+json"}})));
 render(<QueryClientProvider client={createQueryClient()}><CharacterLibrary /></QueryClientProvider>);await userEvent.click(await screen.findByRole("button",{name:"Mira"}));fireEvent.change(screen.getByLabelText("外观描述"),{target:{value:"New appearance"}});await userEvent.click(screen.getByRole("button",{name:"保存角色库档案"}));await screen.findByText(/Profile changed/);await userEvent.click(screen.getByRole("button",{name:"关闭窗口"}));await userEvent.click(await screen.findByRole("button",{name:"继续编辑"}));expect(screen.getByLabelText("外观描述")).toHaveValue("New appearance");
});
it("closes an unmodified library profile without discard confirmation",async()=>{
 setup();render(<QueryClientProvider client={createQueryClient()}><CharacterLibrary /></QueryClientProvider>);await userEvent.click(await screen.findByRole("button",{name:"Mira"}));await userEvent.keyboard("{Escape}");expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
});
it("shows the customized project profile beside the incoming library content before sync approval",async()=>{
 setup();let commits=0;
 const previous={id:"project-role",projectId:"project-one",version:4,archived:false,createdAt:profile.createdAt,updatedAt:profile.updatedAt,content:{name:"Project Mira",description:"Project-only scar",looks:[{id:"project-look",name:"Project-only coat",clothing:"Green scarf",expression:"Serious",description:"Custom project styling",views:[{versionId:"old-image",purpose:"Left side",description:"Project close-up"}]}],voice:{versionId:"old-audio",speakerId:null,description:"Quiet project voice"}}};
 const incoming={...content,name:"Library Mira",appearance:"Golden hair",identityViews:[{mediaId:"new-image",purpose:"Front identity",description:"Library baseline"}],looks:[{id:"new-look",name:"Library winter",clothing:"Blue coat",expression:"Calm",description:"Library styling",views:[]}],voice:{mediaId:"new-audio",speakerId:null,description:"Bright library voice"}};
 server.use(http.get("/api/v1/projects/project-one/artifacts",()=>HttpResponse.json({items:[{id:"old-image-artifact",kind:"IMAGE",title:"Project photo"},{id:"old-audio-artifact",kind:"AUDIO",title:"Project voice"}]})),http.get("/api/v1/projects/project-one/artifacts/old-image-artifact/versions",()=>HttpResponse.json({items:[{id:"old-image",versionNo:1,content:{assetId:"old-image-asset"}}]})),http.get("/api/v1/projects/project-one/artifacts/old-audio-artifact/versions",()=>HttpResponse.json({items:[{id:"old-audio",versionNo:1,content:{assetId:"old-audio-asset"}}]})),http.post("/api/v1/projects/project-one/character-library/imports/preview",async({request})=>HttpResponse.json({...await request.json() as object,previewHash:"comparison-hash",content:incoming,currentCharacter:previous,affectedItems:[],changes:["UPDATE_PROJECT_CHARACTER","COPY_INDEPENDENT_MEDIA","EXISTING_SHOTS_REQUIRE_REVIEW"]})),http.post("/api/v1/projects/project-one/character-library/imports",async({request})=>{commits++;const input=await request.json() as {commandId:string};return HttpResponse.json({id:input.commandId,status:"SUCCEEDED",result:{characterId:previous.id},errorCode:null,errorDetail:null});}),http.get(`${ROOT}/commands/:id`,({params})=>HttpResponse.json({id:params.id,status:"SUCCEEDED",result:{characterId:previous.id},errorCode:null,errorDetail:null})));
 render(<QueryClientProvider client={createQueryClient()}><CharacterLibraryImportDialog projectId="project-one" source={{...profile,content:incoming}} characterId="project-role" expectedCharacterVersion={4} onClose={()=>{}} /></QueryClientProvider>);
 await userEvent.click(screen.getByRole("button",{name:"预览导入或同步"}));
 const before=await screen.findByRole("group",{name:"当前项目副本（同步前）"});const after=screen.getByRole("group",{name:"库修订内容（同步后）"});
 expect(within(before).getByText("Project-only scar")).toBeVisible();expect(within(before).getByText("Project-only coat")).toBeVisible();expect(within(before).getByText("Quiet project voice")).toBeVisible();expect(within(after).getByText("Golden hair")).toBeVisible();expect(within(after).getByText("Library winter")).toBeVisible();expect(within(after).getByText("Bright library voice")).toBeVisible();
 await waitFor(()=>expect(before.querySelector("audio")?.src).toContain("/assets/old-audio-asset/content"));expect(after.querySelector("audio")?.src).toContain("/character-library/media/new-audio/content");expect(screen.getByText("替换项目角色档案")).toBeVisible();expect(screen.queryByText("UPDATE_PROJECT_CHARACTER")).not.toBeInTheDocument();expect(screen.getByText(/右侧没有的内容将被移除/)).toBeVisible();expect(commits).toBe(0);
 await userEvent.click(screen.getByRole("button",{name:"确认导入或同步"}));await screen.findByText("项目角色已更新");expect(commits).toBe(1);
});
