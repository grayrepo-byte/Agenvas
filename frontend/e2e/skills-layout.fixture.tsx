import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createRoot } from "react-dom/client";
import { MemoryRouter } from "react-router";
import { SkillsPage } from "../src/features/skills/SkillsPage";
import "../src/styles.css";

// Synthetic HTTP data exercises the real page, shell and bundled CSS without a backend.
const now = "2026-10-06T00:00:00Z";
const titles = ["视觉资产设定 · short-drama-assets", "分集剧本 · short-drama-write", "原著分析 · drama-novel-analysis", "图片提示词 · short-drama-image-prompts", "创作审查 · short-drama-review", "故事开发 · short-drama-develop", "视频提示词 · short-drama-video-prompts", "分镜与分镜板 · short-drama-storyboard", "非常长的创作方法名称与版本说明 · " + "long-unbroken-name".repeat(7)];
const dramaSource = { name: "Drama Skills", repository: "https://github.com/zenstory-ai/drama-skills", revision: "synthetic-revision", license: "MIT", licenseUrl: "https://github.com/zenstory-ai/drama-skills/blob/main/LICENSE" };
const h3Source = { name: "MiniMax H3", repository: "https://github.com/MiniMax-AI/MiniMax-H3", revision: "synthetic-revision", license: "MiniMax H3 Community License", licenseUrl: "https://huggingface.co/MiniMaxAI/MiniMax-H3/blob/main/LICENSE" };
const skills = titles.map((title, i) => ({ id: `synthetic-skill-${i}`, title, description: "从剧本卡拆出角色、造型、场景、道具和连续性状态，产出视觉设定卡。用于资产拆解、复用与变体判断。", builtin: true, category: "STORY", builtinSource: dramaSource, trashed: false, currentVersionId: `synthetic-version-${i}`, version: 1, createdAt: now, updatedAt: now }));
skills.push(...[
  ["H3 提示词编写 · h3-prompt-writing", "PROMPT"], ["极简产品广告 · minimalist-product-ad-generator", "ADVERTISING"],
  ["品牌宣传短片 · brand-promo-video-generator", "ADVERTISING"], ["3D 动画短片 · 3d-animation-short-generator", "ANIMATION"],
  ["音乐 MV 与歌词贴字 · music-video-subtitle-generator", "MUSIC"], ["双人游戏开场 · co-op-game-intro-generator", "ANIMATION"],
  ["纸拼贴讲解动画 · paper-collage-explainer-generator", "EXPLAINER"], ["纸艺定格科普 · papercraft-stop-motion-explainer", "EXPLAINER"],
  ["手绘与实拍融合 · handdrawn-live-video-generator", "ANIMATION"],
].map(([title, category], i) => ({ ...skills[0]!, id: `synthetic-h3-${i}`, title: title!, category: category!, builtinSource: h3Source, description: "合成的 MiniMax H3 创作方法：按需读取参考资料，通过画布文字卡、能力发现与媒体审批组织创作。", currentVersionId: `synthetic-h3-version-${i}` })));
const personal = { ...skills[0], id: "synthetic-personal", title: "个人创作方法", builtin: false, category: null, builtinSource: null, currentVersionId: "synthetic-personal-version" };
const skillMd = "---\nname: synthetic-method\ndescription: 合成的创作方法，用于布局回归验证。\nlicense: MIT\n---\n\n# 视觉资产设定卡片\n\n" + "先读取卡片工作流，保存时读取卡片模板，读取已有视觉设定卡和明确的视觉方向。\n\n1. 逐场找出实体、可见描述、状态变化与连续性。\n2. 区分主体与变体，保持资产身份可追踪。\n\n".repeat(12);
const resources = ["references/agenvas-cards.md", "assets/card-templates.md", "references/asset-review-checklist.md", "references/" + "long-resource-name-".repeat(12) + ".md"].map(path => ({ path, contentHash: "synthetic-hash", content: "# 合成资料\n\n" + "资源文本应当完整可读，并且保持在容器内。\n".repeat(40) }));
window.fetch = async input => {
  const url = new URL(input instanceof Request ? input.url : String(input), window.location.href);
  let value: unknown;
  if (url.pathname.endsWith("/auth/me")) value = { id: "synthetic-user", loginName: "Mock user", role: "ADMIN" };
  else if (url.pathname === "/api/v1/skills") {
    const category = url.searchParams.get("category"), query = url.searchParams.get("query")?.toLocaleLowerCase() ?? "";
    const items = url.searchParams.get("trash") === "true" ? [] : [...skills, personal].filter((skill) => (!category || skill.category === category)
      && `${skill.title}\n${skill.description}`.toLocaleLowerCase().includes(query));
    value = { items, total: items.length, nextCursor: null };
  }
  else if (url.pathname.endsWith("/draft")) value = { skillId: personal.id, version: 1, skillMd, outputKinds: ["IMAGE"], inputSlots: [], resources, assets: [] };
  else if (url.pathname.endsWith("/versions")) value = [];
  else if (url.pathname.includes("/versions/")) value = { id: "synthetic-version-0", skillId: skills[0]!.id, versionNumber: 1, name: "synthetic-method", description: skills[0]!.description, bundleHash: "synthetic-hash", skillMd, outputKinds: ["IMAGE"], inputSlots: [], resources, assets: [], createdAt: now };
  else if (url.pathname === "/api/v1/projects") value = { items: [], total: 0, nextCursor: null };
  else throw new Error(`Unexpected synthetic request: ${url.pathname}`);
  return new Response(JSON.stringify(value), { headers: { "Content-Type": "application/json" } });
};
createRoot(document.getElementById("root")!).render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><MemoryRouter initialEntries={["/skills"]}><SkillsPage /></MemoryRouter></QueryClientProvider>);
