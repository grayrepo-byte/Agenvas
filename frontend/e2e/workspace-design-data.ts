import type { CreativeSkill, LibraryEntry, ResourceResult, SaveSkillDraftRequest, SkillDraft, SkillOperation, SkillVersion } from "../src/shared/api/client";

// Synthetic catalogue data for the real studio pages. No external service or media request is sent.
const now = "2026-10-20T08:30:00Z";
const names = ["雨夜霓虹 · 场景气氛", "主角服装 · 秋日造型", "机械怀表 · 道具细节", "片头配乐 · 温暖钢琴", "森林环境声 · 远景", "预告片 · 第三版", "产品转场 · 运镜参考", "海边清晨 · 色彩参考", "角色独白 · 旁白音色"];
let entries: LibraryEntry[] = names.map((name, index) => ({
  id: `synthetic-library-${index}`, name, category: (["SCENE", "CHARACTER", "PROP", "OTHER"] as const)[index % 4]!,
  kind: index === 3 || index === 4 || index === 8 ? "AUDIO" : index === 5 || index === 6 ? "VIDEO" : "IMAGE",
  textContent: null, contentType: null, byteSize: 240000 + index * 10000, width: 640, height: 360,
  durationMs: [3, 4, 8].includes(index) ? 500 : [5, 6].includes(index) ? 1000 : null,
  hasThumbnail: ![3, 4, 8].includes(index), source: { origin: "UPLOAD", title: "合成素材预览" }, favorite: index === 0 || index === 3,
  trashedAt: index === 8 ? now : null, version: 1, createdAt: now, updatedAt: now,
}));
const titles = ["视觉资产设定", "分集剧本", "故事开发", "图片提示词", "视频提示词", "分镜与分镜板", "创作审查", "品牌视觉方法", "人物小传写作", "镜头语言助手"];
let skills: CreativeSkill[] = titles.map((title, index) => ({
  id: `synthetic-skill-${index}`, title,
  description: index % 2 ? "将创作意图整理成有结构、可复用的故事与镜头方案。" : "从人物、场景与道具出发，建立一致的视觉创作方法。",
  builtin: index < 6, trashed: index === 9, currentVersionId: `synthetic-skill-version-${index}`,
  version: 1, createdAt: now, updatedAt: now,
}));
const skillText = "---\nname: visual-storytelling\ndescription: A synthetic method for deliberate visual storytelling.\nlicense: MIT\n---\n\n# 视觉叙事方法\n\n先确认创作目标与已有素材，再确定故事的视觉语言。\n\n## 创作流程\n\n1. 拆解主体、场景与关键动作。\n2. 为每个镜头指定明确的构图与光线。\n3. 校对角色、服装、道具和时间的一致性。\n4. 将可复用的设定与参考资料一同保留。\n\n## 输出检查\n\n让每一处画面选择都服务于叙事意图。保持清晰、具体，避免重复的信息。\n\n以上内容为独立合成的设计验证素材。";
const resources = ["references/visual-continuity.md", "references/shot-plan-template.md", "references/character-checklist.md"]
  .map((path) => ({ path, content: "# 合成参考资料\n\n" + "记录主体、视线、动作与场景状态；让相邻镜头之间保持可追踪的连续性。\n\n".repeat(15), contentHash: "synthetic-hash" }));
const drafts = new Map<string, SkillDraft>(skills.map((skill) => [skill.id, {
  skillId: skill.id, version: 1, skillMd: skillText, outputKinds: ["IMAGE"], inputSlots: [], resources, assets: [],
}]));
const versions = new Map<string, SkillVersion>(skills.map((skill) => [skill.currentVersionId!, {
  id: skill.currentVersionId!, skillId: skill.id, versionNumber: 1, bundleHash: "synthetic-hash",
  name: "visual-storytelling", description: skill.description, skillMd: skillText, outputKinds: ["IMAGE"],
  inputSlots: [], resources, assets: [], createdAt: now,
}]));
const operations = new Map<string, SkillOperation>();
const problem = (detail: string, status = 400) => Response.json({ status, title: "Synthetic preview", detail }, { status });
const textField = (body: Record<string, unknown>, key: string, fallback = "") => typeof body[key] === "string" ? body[key] as string : fallback;

/** Copies use the selected immutable bundle, including archived reference bindings. */
function copiedVersionContent(version: SkillVersion): Pick<SkillDraft, "skillMd" | "outputKinds" | "inputSlots" | "resources" | "assets"> {
  return {
    skillMd: version.skillMd, outputKinds: [...version.outputKinds], inputSlots: version.inputSlots.map((slot) => ({ ...slot })),
    resources: version.resources.map(({ path, content }) => ({ path, content })),
    assets: version.assets.map((asset) => ({ alias: asset.alias, libraryEntryId: null, expectedLibraryVersion: null,
      sourceVersionId: version.id, sourceAlias: asset.alias, contentHash: asset.contentHash,
      usage: asset.usage, required: asset.required, purpose: asset.purpose })),
  };
}

function publishedDraftAssets(draft: SkillDraft): SkillVersion["assets"] | Response {
  const assets: SkillVersion["assets"] = [];
  for (const binding of draft.assets) {
    const source = binding.sourceVersionId ? versions.get(binding.sourceVersionId)?.assets.find((asset) => asset.alias === binding.sourceAlias) : undefined;
    const entry = binding.libraryEntryId ? entries.find((asset) => asset.id === binding.libraryEntryId) : undefined;
    if (!source && (!entry || entry.trashedAt || entry.version !== binding.expectedLibraryVersion)) return problem("Skill 引用资产已变化或不存在。", 409);
    assets.push({ alias: binding.alias, kind: source?.kind ?? entry!.kind, title: source?.title ?? entry!.name,
      contentHash: source?.contentHash ?? binding.contentHash ?? "synthetic-hash", usage: binding.usage, required: binding.required, purpose: binding.purpose });
  }
  return assets;
}

/** This fixture validates basic versions, while actual API behavior is covered by application tests. */
export function workspaceDesignResponse(url: URL, method: string, body: Record<string, unknown>): Response | null {
  const path = url.pathname;
  if (path === "/api/v1/library/entries") {
    const query = (url.searchParams.get("query") ?? "").toLowerCase();
    const category = url.searchParams.get("category");
    const kind = url.searchParams.get("kind");
    const trash = url.searchParams.get("trash") === "true";
    const base = entries.filter((entry) => Boolean(entry.trashedAt) === trash);
    const items = base.filter((entry) => (!category || entry.category === category) && (!kind || entry.kind === kind)
      && (url.searchParams.get("favorite") !== "true" || entry.favorite) && entry.name.toLowerCase().includes(query));
    if (url.searchParams.get("sort") === "NAME") items.sort((a, b) => a.name.localeCompare(b.name));
    return Response.json({ items, total: items.length, nextCursor: null,
      categoryCounts: Object.fromEntries(["CHARACTER", "SCENE", "PROP", "OTHER"].map((value) => [value, base.filter((entry) => entry.category === value).length])) });
  }
  if (path.startsWith("/api/v1/library/entries/")) {
    const [, , , , , id, action] = path.split("/");
    const entry = entries.find((item) => item.id === id);
    if (!entry) return problem("此合成资产不存在。", 404);
    if (method === "GET") return Response.json(entry);
    const expected = method === "DELETE" ? Number(url.searchParams.get("expectedVersion")) : body.expectedVersion;
    if (entry.version !== expected) return problem("资产版本已变化，请载入最新信息。", 409);
    if (method === "DELETE") { entries = entries.filter((item) => item.id !== id); return new Response(null, { status: 204 }); }
    const saved: LibraryEntry = { ...entry, name: textField(body, "name", entry.name),
      category: ["CHARACTER", "SCENE", "PROP", "OTHER"].includes(String(body.category)) ? body.category as LibraryEntry["category"] : entry.category,
      favorite: typeof body.favorite === "boolean" ? body.favorite : entry.favorite,
      trashedAt: action === "trash" ? now : action === "restore" ? null : entry.trashedAt, version: entry.version + 1 };
    entries = entries.map((item) => item.id === id ? saved : item);
    return Response.json(saved);
  }
  if (path === "/api/v1/resources") {
    const items: ResourceResult[] = entries.filter((entry) => entry.kind === "AUDIO").map((entry) => ({
      versionId: `synthetic-resource-${entry.id}`, artifactId: entry.id, projectId: "synthetic-project", projectName: "秋日短片 · 设计预览",
      title: entry.name, kind: entry.kind, versionNo: 2, content: { sourceType: "UPLOAD", assetId: entry.id }, createdAt: now,
    }));
    return Response.json({ items: items.filter((entry) => (!url.searchParams.get("kind") || entry.kind === url.searchParams.get("kind"))
      && entry.title.includes(url.searchParams.get("query") ?? "")), nextCursor: null });
  }
  if (path === "/api/v1/skills") {
    if (method === "POST") {
      const skill: CreativeSkill = { id: `synthetic-skill-${skills.length}`, title: textField(body, "title", "新创作方法"), description: "",
        builtin: false, trashed: false, currentVersionId: null, version: 1, createdAt: now, updatedAt: now };
      skills = [...skills, skill];
      drafts.set(skill.id, { skillId: skill.id, version: 1, skillMd: "---\nname: new-method\ndescription: A new creative method.\n---\n\n# 创作方法", outputKinds: ["IMAGE"], inputSlots: [], resources: [], assets: [] });
      return Response.json(skill);
    }
    const query = (url.searchParams.get("query") ?? "").toLowerCase();
    const items = skills.filter((skill) => skill.trashed === (url.searchParams.get("trash") === "true") && `${skill.title} ${skill.description}`.toLowerCase().includes(query));
    return Response.json({ items, total: items.length, nextCursor: null });
  }
  if (path.startsWith("/api/v1/skills/operations/")) return Response.json(operations.get(path.split("/")[5] ?? "") ?? null);
  if (path.startsWith("/api/v1/skills/")) {
    const [, , , , skillId, section, versionId, action] = path.split("/");
    const skill = skills.find((item) => item.id === skillId);
    if (!skill) return problem("此合成 Skill 不存在。", 404);
    const draft = drafts.get(skill.id)!;
    if (section === "draft") {
      if (method === "PUT") {
        if (body.expectedVersion !== draft.version) return problem("Skill 草稿已变化。", 409);
        const input = body as SaveSkillDraftRequest;
        const saved: SkillDraft = { ...draft, skillMd: input.skillMd, inputSlots: input.inputSlots, outputKinds: input.outputKinds,
          resources: input.resources, assets: input.assets, version: draft.version + 1 };
        drafts.set(skill.id, saved); return Response.json(saved);
      }
      return Response.json(draft);
    }
    if (section === "versions") {
      if (method === "POST" && !versionId) {
        if (body.expectedDraftVersion !== draft.version) return problem("发布版本基线已变化。", 409);
        const assets = publishedDraftAssets(draft);
        if (assets instanceof Response) return assets;
        const version: SkillVersion = { id: `synthetic-version-${versions.size}`, skillId: skill.id, versionNumber: [...versions.values()].filter((item) => item.skillId === skill.id).length + 1,
          name: "synthetic-method", description: skill.description, bundleHash: "synthetic-hash", skillMd: draft.skillMd, outputKinds: [...draft.outputKinds],
          inputSlots: draft.inputSlots.map((slot) => ({ ...slot })), resources: draft.resources.map((resource) => ({ ...resource, contentHash: "synthetic-hash" })), assets, createdAt: now };
        versions.set(version.id, version); skills = skills.map((item) => item.id === skill.id ? { ...item, currentVersionId: version.id, version: item.version + 1 } : item);
        const operation: SkillOperation = { id: `synthetic-operation-${operations.size}`, skillId: skill.id, status: "SUCCEEDED", resultVersionId: version.id, errorCode: null, errorDetail: null };
        operations.set(operation.id, operation); return Response.json(operation);
      }
      if (!versionId) return Response.json([...versions.values()].filter((version) => version.skillId === skill.id)
        .map((version) => ({ id: version.id, skillId: version.skillId, versionNumber: version.versionNumber, name: version.name,
          description: version.description, bundleHash: version.bundleHash, createdAt: version.createdAt })));
      const version = versions.get(versionId);
      if (!version || version.skillId !== skill.id) return problem("此合成版本不存在。", 404);
      if (action === "copy") {
        if (body.expectedDraftVersion !== draft.version) return problem("Skill 草稿已变化。", 409);
        const saved: SkillDraft = { skillId: skill.id, ...copiedVersionContent(version), version: draft.version + 1 };
        drafts.set(skill.id, saved); return Response.json(saved);
      }
      return Response.json(version);
    }
    if (section === "copy") {
      const source = versions.get(textField(body, "skillVersionId"));
      if (!source || source.skillId !== skill.id) return problem("此合成版本不存在。", 404);
      const copy: CreativeSkill = { id: `synthetic-skill-${skills.length}`, title: textField(body, "title", `${skill.title} · 副本`),
        description: source.description, builtin: false, trashed: false, currentVersionId: null, version: 1, createdAt: now, updatedAt: now };
      skills = [...skills, copy]; drafts.set(copy.id, { skillId: copy.id, version: 1, ...copiedVersionContent(source) }); return Response.json(copy);
    }
    if (method === "PATCH") {
      if (body.expectedVersion !== skill.version) return problem("Skill 信息已变化。", 409);
      const saved = { ...skill, title: textField(body, "title", skill.title), description: textField(body, "description", skill.description),
        trashed: typeof body.trashed === "boolean" ? body.trashed : skill.trashed, version: skill.version + 1 };
      skills = skills.map((item) => item.id === skill.id ? saved : item); return Response.json(saved);
    }
    return Response.json(skill);
  }
  return null;
}
