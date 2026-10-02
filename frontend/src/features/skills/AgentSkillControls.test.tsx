import {useState} from "react";
import {QueryClientProvider} from "@tanstack/react-query";
import {cleanup,render,screen,waitFor} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import {http,HttpResponse} from "msw";
import {afterEach,expect,it} from "vitest";
import {createQueryClient} from "../../app/queryClient";
import type {Agent,SaveAgentSkillBindingRequest} from "../../shared/api/client";
import {server} from "../../test/server";
import {AgentSkillSettings,useAgentSkillSelection} from "./AgentSkillControls";

const agent:Agent={id:"agent-1",projectId:"project-1",profileKey:"creator",profileVersion:1,name:"Agent",instruction:"Create",outputGroupId:"group-1",bindings:[],version:3,createdAt:"2026-10-02T00:00:00Z",updatedAt:"2026-10-02T00:00:00Z"};
afterEach(cleanup);
it("keeps the edit's original CAS baseline when SSE brings a newer Agent configuration",async()=>{
  let liveVersion=agent.version;let submitted:SaveAgentSkillBindingRequest|undefined;const requests:SaveAgentSkillBindingRequest[]=[];
  server.use(http.get("/api/v1/auth/csrf",()=>HttpResponse.json({token:"synthetic-csrf",headerName:"X-CSRF-TOKEN"})),
    http.get("/api/v1/projects/project-1/agents/agent-1/skill-binding",()=>HttpResponse.json({agentVersion:liveVersion,skillId:null,skillVersionId:null})),
    http.get("/api/v1/skills",()=>HttpResponse.json({items:[{id:"skill-1",title:"Style",currentVersionId:"version-1",trashed:false}],nextCursor:null,total:1})),
    http.get("/api/v1/skills/skill-1/versions",()=>HttpResponse.json([{id:"version-1",versionNumber:1}])),
    http.put("/api/v1/projects/project-1/agents/agent-1/skill-binding",async({request})=>{submitted=await request.json() as SaveAgentSkillBindingRequest;requests.push(submitted);return requests.length===1 ? HttpResponse.json({code:"AGENT_VERSION_CONFLICT",detail:"Synthetic conflict"},{status:409}) : HttpResponse.json({agentVersion:5,skillId:submitted.skillId,skillVersionId:submitted.skillVersionId});}));
  function Subject(){const[current,setCurrent]=useState(agent);const state=useAgentSkillSelection("project-1",current);return <><button onClick={()=>{liveVersion++;setCurrent({...current,version:liveVersion});}}>Simulate SSE</button><AgentSkillSettings projectId="project-1" agent={current} state={state} onChanged={()=>{}} /></>;}
  const client=createQueryClient();client.setDefaultOptions({queries:{retry:false}});
  render(<QueryClientProvider client={client}><Subject /></QueryClientProvider>);
  const user=userEvent.setup();await user.click(await screen.findByRole("combobox",{name:"选择 Skill"}));await user.click(await screen.findByRole("option",{name:"Style"}));
  await waitFor(()=>expect(screen.getByRole("combobox",{name:"发布版本"})).toBeEnabled());await user.click(screen.getByRole("combobox",{name:"发布版本"}));await user.click(await screen.findByRole("option",{name:"版本 1"}));
  await user.click(screen.getByRole("button",{name:"Simulate SSE"}));await waitFor(()=>expect(screen.getByRole("button",{name:"保存默认绑定"})).toBeEnabled());
  await user.click(screen.getByRole("button",{name:"保存默认绑定"}));await screen.findByText("服务器版本已变化。草稿已保留；刷新后核对并重试。");
  expect(submitted).toEqual({expectedAgentVersion:3,skillId:"skill-1",skillVersionId:"version-1"});expect(screen.getByRole("combobox",{name:"发布版本"})).toHaveTextContent("版本 1");
  await user.click(screen.getByRole("button",{name:"刷新服务器版本"}));
  await waitFor(()=>expect(screen.queryByText("服务器版本已变化。草稿已保留；刷新后核对并重试。")).not.toBeInTheDocument());
  await user.click(screen.getByRole("button",{name:"保存默认绑定"}));
  await waitFor(()=>expect(requests).toHaveLength(2));
  expect(requests[1]).toEqual({expectedAgentVersion:4,skillId:"skill-1",skillVersionId:"version-1"});
});
