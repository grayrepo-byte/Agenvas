import drama from "../../../backend/src/main/resources/builtin-skills/drama-skills/manifest.json";
import h3 from "../../../backend/src/main/resources/builtin-skills/minimax-h3/manifest.json";
import type { CreativeSkill, SkillVersion } from "../shared/api/client";

const createdAt = "2026-10-02T00:00:00Z";
export const packagedSkills: CreativeSkill[] = [drama, h3].flatMap(source =>
  source.skills.map(skill => ({
    id: `builtin-${skill.name}`,
    title: skill.title,
    description: skill.description,
    builtin: true,
    builtinSource: {
      name: source.sourceName,
      repository: source.repository,
      revision: source.revision,
      license: source.license,
      licenseUrl: source.licenseUrl,
    },
    currentVersionId: `version-${skill.name}`,
    trashed: false,
    version: 0,
    createdAt,
    updatedAt: createdAt,
  })),
);

export const packagedSkillVersions: SkillVersion[] = [drama, h3].flatMap(source =>
  source.skills.map(skill => ({
    id: `version-${skill.name}`,
    skillId: `builtin-${skill.name}`,
    name: skill.name,
    description: skill.description,
    versionNumber: 1,
    bundleHash: "synthetic-bundle-hash",
    skillMd: "# 原始 Skill 正文\n保持原文与 runtime_name。",
    outputKinds: ["TEXT"],
    inputSlots: [],
    assets: [],
    createdAt,
    resources: [{ path: "references/原始资源.md", content: "原始参考正文", contentHash: "synthetic-resource-hash" }],
  })),
);
