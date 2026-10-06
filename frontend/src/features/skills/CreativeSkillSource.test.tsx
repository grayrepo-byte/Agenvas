import { cleanup,render,screen } from "@testing-library/react";
import { afterEach,expect,it } from "vitest";
import { CreativeSkillSource } from "./CreativeSkillSource";
afterEach(cleanup);
it("shows fixed creative source content without dumping arbitrary archive or provider fields",()=>{
  render(<CreativeSkillSource source={{skillVersionId:"version-1",name:"warm-art",versionNumber:1,bundleHash:"synthetic-hash",skillMd:"# Fixed instructions",endpoint:"synthetic-private-endpoint",storageKey:"synthetic-private-path",resources:[{path:"references/style.md",content:"Fixed style guide",contentHash:"synthetic-resource-hash"}],assets:[{alias:"style-reference",purpose:"Warm palette",artifactVersionId:"image-version-1"}]}} />);
  expect(screen.getByText("# Fixed instructions")).toBeInTheDocument();expect(screen.getByText("Fixed style guide")).toBeInTheDocument();expect(screen.getByText(/style-reference/)).toHaveTextContent("image-version-1");expect(screen.queryByText(/synthetic-private/)).not.toBeInTheDocument();
});
it("shows each activated Skill's fixed source separately even when attachment paths match",()=>{
  render(<CreativeSkillSource source={{schemaVersion:2,skills:[
    {skillVersionId:"version-1",name:"first",versionNumber:1,skillMd:"First method",resources:[{path:"references/shared.md",content:"First guide",contentHash:"first-hash"}]},
    {skillVersionId:"version-2",name:"second",versionNumber:2,skillMd:"Second method",resources:[{path:"references/shared.md",content:"Second guide",contentHash:"second-hash"}]},
  ]}} />);
  expect(screen.getByText("First method")).toBeInTheDocument();expect(screen.getByText("Second method")).toBeInTheDocument();
  expect(screen.getByText("First guide")).toBeInTheDocument();expect(screen.getByText("Second guide")).toBeInTheDocument();
  expect(screen.getByText(/references\/shared.md · first-hash/)).toBeInTheDocument();expect(screen.getByText(/references\/shared.md · second-hash/)).toBeInTheDocument();
});
