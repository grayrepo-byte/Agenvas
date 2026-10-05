import { cleanup,render,screen } from "@testing-library/react";
import { afterEach,expect,it } from "vitest";
import { CreativeSkillSource } from "./CreativeSkillSource";
afterEach(cleanup);
it("shows fixed creative source content without dumping arbitrary archive or provider fields",()=>{
  render(<CreativeSkillSource source={{skillVersionId:"version-1",name:"warm-art",versionNumber:1,bundleHash:"synthetic-hash",skillMd:"# Fixed instructions",endpoint:"synthetic-private-endpoint",storageKey:"synthetic-private-path",resources:[{path:"references/style.md",content:"Fixed style guide",contentHash:"synthetic-resource-hash"}],assets:[{alias:"style-reference",purpose:"Warm palette",artifactVersionId:"image-version-1"}]}} />);
  expect(screen.getByText("# Fixed instructions")).toBeInTheDocument();expect(screen.getByText("Fixed style guide")).toBeInTheDocument();expect(screen.getByText(/style-reference/)).toHaveTextContent("image-version-1");expect(screen.queryByText(/synthetic-private/)).not.toBeInTheDocument();
});
