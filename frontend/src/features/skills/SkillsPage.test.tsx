import { QueryClientProvider } from "@tanstack/react-query";
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { MemoryRouter, Route, Routes, useLocation } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import type {
  CreativeSkill,
  LibraryEntry,
  SkillDraft,
  SkillVersion,
  SaveSkillDraftRequest,
} from "../../shared/api/client";
import { server } from "../../test/server";
import { selectValue } from "../../test/controls";
import { packagedSkills, packagedSkillVersions } from "../../test/builtinSkills";
import { setLocale, t } from "../../shared/i18n";
import { SkillsPage } from "./SkillsPage";

const SKILL_ID = "skill-1",
  VERSION_ID = "skill-version-1",
  NOW = "2026-10-02T00:00:00Z";
const SKILL: CreativeSkill = {
  id: SKILL_ID,
  title: "温暖手绘",
  description: "",
  trashed: false,
  builtin: false,
  currentVersionId: VERSION_ID,
  version: 1,
  createdAt: NOW,
  updatedAt: NOW,
};
const INITIAL: SkillDraft = {
  skillId: SKILL_ID,
  version: 1,
  skillMd: "---\nname: warm-art\ndescription: A warm illustration guide.\n---\n# Method",
  outputKinds: ["IMAGE"],
  inputSlots: [],
  resources: [],
  assets: [],
};
const VERSION: SkillVersion = {
  id: VERSION_ID,
  skillId: SKILL_ID,
  versionNumber: 1,
  name: "warm-art",
  description: "A warm illustration guide.",
  bundleHash: "synthetic-hash",
  skillMd: INITIAL.skillMd,
  outputKinds: ["IMAGE"],
  inputSlots: [],
  resources: [],
  assets: [],
  createdAt: NOW,
};
function Destination() {
  return <p data-testid="destination">{useLocation().search}</p>;
}
function mount() {
  const client = createQueryClient();
  client.setDefaultOptions({ queries: { retry: false } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={["/skills"]}>
        <Routes>
          <Route path="/skills" element={<SkillsPage />} />
          <Route path="/projects/:id" element={<Destination />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return client;
}
beforeEach(() => {
  server.use(
    http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "user-1", loginName: "mock-user", role: "ADMIN" })),
    http.get("/api/v1/auth/csrf", () => HttpResponse.json({ token: "synthetic-csrf", headerName: "X-CSRF-TOKEN" })),
    http.get("/api/v1/skills", () => HttpResponse.json({ items: [SKILL], nextCursor: null, total: 1 })),
    http.get(`/api/v1/skills/${SKILL_ID}`, () => HttpResponse.json(SKILL)),
    http.get(`/api/v1/skills/${SKILL_ID}/draft`, () => HttpResponse.json(INITIAL)),
    http.get(`/api/v1/skills/${SKILL_ID}/versions`, () =>
      HttpResponse.json([
        {
          id: VERSION_ID,
          skillId: SKILL_ID,
          versionNumber: 1,
          name: VERSION.name,
          description: VERSION.description,
          bundleHash: VERSION.bundleHash,
          createdAt: NOW,
        },
      ]),
    ),
    http.get(`/api/v1/skills/${SKILL_ID}/versions/${VERSION_ID}`, () => HttpResponse.json(VERSION)),
  );
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});
it("localizes installed Skill metadata while preserving personal labels and packaged source", async () => {
  setLocale("en");
  const personal = {
    ...packagedSkills[0]!,
    id: "personal-copy",
    builtin: false,
    builtinSource: null,
    currentVersionId: null,
  };
  const builtin = packagedSkills[0]!,
    version = packagedSkillVersions[0]!;
  server.use(
    http.get("/api/v1/skills", () =>
      HttpResponse.json({ items: [...packagedSkills, personal], nextCursor: null, total: 18 }),
    ),
    http.get(`/api/v1/skills/${builtin.id}/versions/${version.id}`, () => HttpResponse.json(version)),
  );
  mount();
  await screen.findByRole("button", { name: "Visual asset design · short-drama-assets" });
  const list = document.querySelector(".skills-list")!;
  expect(list.querySelectorAll(".skills-list-item")).toHaveLength(18);
  const builtinButtons = Array.from(list.querySelectorAll(".skills-list-item")).slice(0, 17);
  for (const button of builtinButtons) expect(button.textContent).not.toMatch(/\p{Script=Han}/u);
  expect(within(list as HTMLElement).getByRole("button", { name: personal.title })).toBeVisible();
  await userEvent.click(screen.getByRole("button", { name: "Visual asset design · short-drama-assets" }));
  expect(await screen.findByText(/# 原始 Skill 正文/)).toHaveTextContent("保持原文与 runtime_name。");
  expect(screen.getByText(version.resources[0]!.path)).toBeInTheDocument();
  act(() => {
    setLocale("ru");
  });
  expect(await screen.findByRole("button", { name: "Визуальные материалы · short-drama-assets" })).toBeVisible();
  expect(screen.getByText(/# 原始 Skill 正文/)).toBeInTheDocument();
});
it("refetches catalogue search in the new language while retaining the search draft", async () => {
  setLocale("en");
  const requests: { language: string | null; query: string | null }[] = [];
  server.use(
    http.get("/api/v1/skills", ({ request }) => {
      requests.push({
        language: request.headers.get("Accept-Language"),
        query: new URL(request.url).searchParams.get("query"),
      });
      return HttpResponse.json({ items: packagedSkills, nextCursor: null, total: 17 });
    }),
  );
  mount();
  const search = await screen.findByRole("textbox", { name: t("skills.search") });
  fireEvent.change(search, { target: { value: "design" } });
  await waitFor(() => expect(requests).toContainEqual({ language: "en", query: "design" }));
  act(() => {
    setLocale("ja");
  });
  await waitFor(() => expect(requests).toContainEqual({ language: "ja", query: "design" }));
  expect(screen.getByRole("textbox", { name: t("skills.search") })).toBe(search);
  expect(search).toHaveValue("design");
});
it("shows builtin content read-only with attribution and permits trying its fixed version", async () => {
  const builtin: CreativeSkill = {
    ...SKILL,
    title: "分集剧本 · short-drama-write",
    builtin: true,
    category: "STORY",
    builtinSource: {
      name: "Drama Skills",
      repository: "https://github.com/zenstory-ai/drama-skills",
      revision: "synthetic-revision",
      license: "MIT",
      licenseUrl: "https://github.com/zenstory-ai/drama-skills/blob/synthetic-revision/LICENSE",
    },
  };
  let draftReads = 0;
  server.use(
    http.get("/api/v1/skills", () => HttpResponse.json({ items: [builtin], nextCursor: null, total: 1 })),
    http.get(`/api/v1/skills/${SKILL_ID}/draft`, () => {
      draftReads++;
      return HttpResponse.json(INITIAL);
    }),
    http.get("/api/v1/projects", () => HttpResponse.json({ items: [], nextCursor: null, total: 0 })),
  );
  mount();
  await userEvent.click(await screen.findByRole("button", { name: builtin.title }));
  expect(await screen.findByText(/# Method/)).toHaveTextContent("name: warm-art");
  expect(screen.getByRole("link", { name: "Drama Skills" })).toHaveAttribute(
    "href",
    "https://github.com/zenstory-ai/drama-skills",
  );
  expect(screen.getByRole("link", { name: "MIT" })).toHaveAttribute("href", builtin.builtinSource!.licenseUrl);
  expect(screen.queryByRole("button", { name: "发布版本" })).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "移入回收站" })).not.toBeInTheDocument();
  expect(screen.queryByRole("textbox", { name: "Skill 正文" })).not.toBeInTheDocument();
  expect(draftReads).toBe(0);
  await userEvent.click(screen.getByRole("button", { name: "在导演中试用" }));
  expect(await screen.findByRole("dialog", { name: "在导演中试用" })).toBeVisible();
});
async function openEditor() {
  mount();
  await userEvent.click(await screen.findByRole("button", { name: "温暖手绘" }));
  return screen.findByRole("textbox", { name: "Skill 正文" });
}
describe("Skill editing and publishing", () => {
  it("adds distinct input aliases and resource paths after deleting earlier entries", async () => {
    const submitted: SaveSkillDraftRequest[] = [];
    server.use(
      http.put(`/api/v1/skills/${SKILL_ID}/draft`, async ({ request }) => {
        const saved = (await request.json()) as SaveSkillDraftRequest;
        submitted.push(saved);
        return HttpResponse.json({ ...INITIAL, ...saved, version: saved.expectedVersion + 1 });
      }),
    );
    await openEditor();
    await userEvent.click(screen.getByRole("button", { name: t("skills.inputAdd") }));
    await userEvent.click(screen.getByRole("button", { name: t("skills.inputAdd") }));
    await userEvent.click(screen.getAllByRole("button", { name: t("skills.remove") })[0]!);
    await userEvent.click(screen.getByRole("button", { name: t("skills.inputAdd") }));
    expect(
      screen
        .getAllByRole("textbox", { name: t("skills.alias") })
        .map(input => (input as HTMLInputElement).value)
        .sort(),
    ).toEqual(["subject-1", "subject-2"]);
    await userEvent.click(screen.getByRole("tab", { name: t("skills.resources") }));
    await userEvent.click(screen.getByRole("button", { name: t("skills.resourceAdd") }));
    await userEvent.click(screen.getByRole("button", { name: t("skills.resourceAdd") }));
    await userEvent.click(screen.getAllByRole("button", { name: t("skills.remove") })[0]!);
    await userEvent.click(screen.getByRole("button", { name: t("skills.resourceAdd") }));
    expect(
      screen
        .getAllByRole("textbox", { name: t("skills.resourcePath") })
        .map(input => (input as HTMLInputElement).value)
        .sort(),
    ).toEqual(["references/resource-1.md", "references/resource-2.md"]);
    await userEvent.click(screen.getByRole("button", { name: t("skills.save") }));
    await waitFor(() =>
      expect(submitted.at(-1)).toEqual(
        expect.objectContaining({
          inputSlots: expect.arrayContaining([
            expect.objectContaining({ alias: "subject-1" }),
            expect.objectContaining({ alias: "subject-2" }),
          ]),
          resources: expect.arrayContaining([
            expect.objectContaining({ path: "references/resource-1.md" }),
            expect.objectContaining({ path: "references/resource-2.md" }),
          ]),
        }),
      ),
    );
  });
  it("keeps newer document edits and both attachments when file reads finish out of order", async () => {
    const submitted: SaveSkillDraftRequest[] = [];
    server.use(
      http.put(`/api/v1/skills/${SKILL_ID}/draft`, async ({ request }) => {
        const saved = (await request.json()) as SaveSkillDraftRequest;
        submitted.push(saved);
        return HttpResponse.json({ ...INITIAL, ...saved, version: saved.expectedVersion + 1 });
      }),
    );
    await openEditor();
    await userEvent.click(screen.getByRole("tab", { name: "资源附件" }));
    const upload = screen.getByLabelText(t("skills.resourceAdd"), { selector: "input" });
    let finishFirst!: (value: ArrayBuffer) => void, finishSecond!: (value: ArrayBuffer) => void;
    const first = new File([], "first.md", { type: "text/markdown" }),
      second = new File([], "second.txt", { type: "text/plain" });
    Object.defineProperty(first, "arrayBuffer", {
      value: () =>
        new Promise<ArrayBuffer>(resolve => {
          finishFirst = resolve;
        }),
    });
    Object.defineProperty(second, "arrayBuffer", {
      value: () =>
        new Promise<ArrayBuffer>(resolve => {
          finishSecond = resolve;
        }),
    });
    await userEvent.upload(upload, first);
    await userEvent.upload(upload, second);
    expect(screen.getByRole("button", { name: t("skills.publish") })).toBeDisabled();
    expect(screen.getByRole("button", { name: t("skills.moveToTrash") })).toBeDisabled();
    await userEvent.click(screen.getByRole("tab", { name: "SKILL.md" }));
    const body = screen.getByRole("textbox", { name: "Skill 正文" });
    fireEvent.change(body, { target: { value: INITIAL.skillMd + "\nKeep this newer thought" } });
    await act(async () => {
      finishSecond(new TextEncoder().encode("Second reference").buffer);
    });
    await act(async () => {
      finishFirst(new TextEncoder().encode("First reference").buffer);
    });
    expect(body).toHaveValue(INITIAL.skillMd + "\nKeep this newer thought");
    await userEvent.click(screen.getByRole("tab", { name: "资源附件" }));
    expect(screen.getByDisplayValue("references/first.md")).toBeInTheDocument();
    expect(screen.getByDisplayValue("references/second.txt")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "保存草稿" }));
    await waitFor(() =>
      expect(submitted.at(-1)).toEqual(
        expect.objectContaining({
          skillMd: INITIAL.skillMd + "\nKeep this newer thought",
          resources: expect.arrayContaining([
            { path: "references/first.md", content: "First reference" },
            { path: "references/second.txt", content: "Second reference" },
          ]),
        }),
      ),
    );
  });
  it("keeps replacement draft fields unchanged while copying a published version", async () => {
    let finishCopy!: () => void;
    const copying = new Promise<void>(resolve => {
      finishCopy = resolve;
    });
    const copied = { ...INITIAL, version: 2, skillMd: INITIAL.skillMd + "\nCopied version" };
    server.use(
      http.post(`/api/v1/skills/${SKILL_ID}/versions/${VERSION_ID}/copy`, async () => {
        await copying;
        return HttpResponse.json(copied);
      }),
    );
    const body = await openEditor();
    await selectValue(screen.getByRole("combobox", { name: "发布版本" }), VERSION_ID);
    await userEvent.click(await screen.findByRole("button", { name: t("skills.copy") }));
    expect(body).toBeDisabled();
    expect(document.getElementById(`skill-title-${SKILL_ID}`)).toHaveAttribute("readonly");
    await userEvent.type(body, "This input must wait");
    expect(body).toHaveValue(INITIAL.skillMd);
    finishCopy();
    await waitFor(() => expect(body).toHaveValue(copied.skillMd));
    expect(body).not.toBeDisabled();
    await userEvent.type(body, "\nAfter copy");
    expect(body).toHaveValue(copied.skillMd + "\nAfter copy");
  });
  it("restores editing and retains the existing draft after copying fails", async () => {
    let finishCopy!: () => void;
    const copying = new Promise<void>(resolve => {
      finishCopy = resolve;
    });
    server.use(
      http.post(`/api/v1/skills/${SKILL_ID}/versions/${VERSION_ID}/copy`, async () => {
        await copying;
        return HttpResponse.json(
          { status: 409, code: "VERSION_CONFLICT", detail: "Synthetic draft conflict" },
          { status: 409 },
        );
      }),
    );
    const body = await openEditor();
    await selectValue(screen.getByRole("combobox", { name: t("skills.versions") }), VERSION_ID);
    await userEvent.click(await screen.findByRole("button", { name: t("skills.copy") }));
    expect(body).toBeDisabled();
    finishCopy();
    await waitFor(() => expect(body).not.toBeDisabled());
    expect(body).toHaveValue(INITIAL.skillMd);
    expect(document.getElementById(`skill-title-${SKILL_ID}`)).not.toHaveAttribute("readonly");
    expect(screen.getByText(t("skills.conflict"))).toBeInTheDocument();
    await userEvent.type(body, "\nRetained after failure");
    expect(body).toHaveValue(INITIAL.skillMd + "\nRetained after failure");
  });
  it("reserves the final attachment slot while reading and releases it after an invalid file", async () => {
    const resources = Array.from({ length: 7 }, (_, index) => ({
      path: `references/resource-${index + 1}.md`,
      content: "Existing reference",
    }));
    server.use(http.get(`/api/v1/skills/${SKILL_ID}/draft`, () => HttpResponse.json({ ...INITIAL, resources })));
    await openEditor();
    await userEvent.click(screen.getByRole("tab", { name: t("skills.resources") }));
    const upload = screen.getByLabelText(t("skills.resourceAdd"), { selector: "input" });
    let finishRead!: (value: ArrayBuffer) => void;
    const invalid = new File([], "broken.md", { type: "text/markdown" });
    Object.defineProperty(invalid, "arrayBuffer", {
      value: () =>
        new Promise<ArrayBuffer>(resolve => {
          finishRead = resolve;
        }),
    });
    await userEvent.upload(upload, invalid);
    expect(upload).toBeDisabled();
    expect(screen.getByRole("button", { name: t("skills.resourceAdd") })).toBeDisabled();
    await act(async () => {
      finishRead(new Uint8Array([0xff]).buffer);
    });
    expect(upload).not.toBeDisabled();
    expect(screen.getByRole("button", { name: t("skills.publish") })).not.toBeDisabled();
    expect(document.querySelector(".skills-personal-editor [role=alert]")).toBeInTheDocument();
    expect(screen.getAllByRole("textbox", { name: t("skills.resourcePath") })).toHaveLength(7);
    await userEvent.click(screen.getByRole("button", { name: t("skills.resourceAdd") }));
    expect(screen.getByDisplayValue("references/resource-8.md")).toBeInTheDocument();
    expect(upload).toBeDisabled();
  });
  it.each(["manual resource", "same-name upload"])(
    "keeps distinct paths when a pending upload meets a %s",
    async other => {
      const submitted: SaveSkillDraftRequest[] = [];
      server.use(
        http.put(`/api/v1/skills/${SKILL_ID}/draft`, async ({ request }) => {
          const saved = (await request.json()) as SaveSkillDraftRequest;
          submitted.push(saved);
          return HttpResponse.json({ ...INITIAL, ...saved, version: saved.expectedVersion + 1 });
        }),
      );
      await openEditor();
      await userEvent.click(screen.getByRole("tab", { name: t("skills.resources") }));
      const upload = screen.getByLabelText(t("skills.resourceAdd"), { selector: "input" });
      let finishRead!: (value: ArrayBuffer) => void;
      const slow = new File([], "resource-1.md", { type: "text/markdown" });
      Object.defineProperty(slow, "arrayBuffer", {
        value: () =>
          new Promise<ArrayBuffer>(resolve => {
            finishRead = resolve;
          }),
      });
      await userEvent.upload(upload, slow);
      if (other === "manual resource") {
        await userEvent.click(screen.getByRole("button", { name: t("skills.resourceAdd") }));
        fireEvent.change(screen.getByRole("textbox", { name: t("skills.resourceText") }), {
          target: { value: "Other reference" },
        });
      } else {
        const fast = new File([], "resource-1.md", { type: "text/markdown" });
        Object.defineProperty(fast, "arrayBuffer", {
          value: async () => new TextEncoder().encode("Other reference").buffer,
        });
        await userEvent.upload(upload, fast);
      }
      await act(async () => {
        finishRead(new TextEncoder().encode("Slow reference").buffer);
      });
      expect(
        screen
          .getAllByRole("textbox", { name: t("skills.resourcePath") })
          .map(input => (input as HTMLInputElement).value)
          .sort(),
      ).toEqual(["references/resource-1-1.md", "references/resource-1.md"]);
      await userEvent.click(screen.getByRole("button", { name: t("skills.save") }));
      await waitFor(() =>
        expect(submitted.at(-1)?.resources).toEqual([
          { path: "references/resource-1.md", content: "Other reference" },
          { path: "references/resource-1-1.md", content: "Slow reference" },
        ]),
      );
    },
  );
  it("allocates aliases across both input and fixed asset namespaces", async () => {
    const reference: LibraryEntry = {
      id: "library-image",
      name: "水彩风格参考",
      category: "OTHER",
      kind: "IMAGE",
      textContent: null,
      contentType: "image/png",
      byteSize: 120,
      width: 8,
      height: 8,
      durationMs: null,
      hasThumbnail: false,
      source: {},
      favorite: false,
      trashedAt: null,
      version: 7,
      createdAt: NOW,
      updatedAt: NOW,
    };
    const initial = {
      ...INITIAL,
      inputSlots: [{ alias: "reference-1", kind: "IMAGE", required: true }],
      assets: [
        {
          alias: "subject-1",
          libraryEntryId: reference.id,
          expectedLibraryVersion: 7,
          usage: "GUIDE",
          required: true,
          purpose: "Existing reference",
        },
      ],
    };
    const submitted: SaveSkillDraftRequest[] = [];
    server.use(
      http.get(`/api/v1/skills/${SKILL_ID}/draft`, () => HttpResponse.json(initial)),
      http.get("/api/v1/library/entries", () => HttpResponse.json({ items: [reference], nextCursor: null, total: 1 })),
      http.put(`/api/v1/skills/${SKILL_ID}/draft`, async ({ request }) => {
        const saved = (await request.json()) as SaveSkillDraftRequest;
        submitted.push(saved);
        return HttpResponse.json({ ...INITIAL, ...saved, version: saved.expectedVersion + 1 });
      }),
    );
    await openEditor();
    await userEvent.click(screen.getByRole("button", { name: t("skills.inputAdd") }));
    expect(screen.getByDisplayValue("subject-2")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("tab", { name: t("skills.assets") }));
    await userEvent.click(screen.getByRole("button", { name: t("skills.assetAdd") }));
    await userEvent.click(
      await screen.findByRole("button", { name: t("library.browser.referenceNamed", { "0": reference.name }) }),
    );
    expect(screen.getByDisplayValue("reference-2")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: t("skills.save") }));
    await waitFor(() =>
      expect(submitted.at(-1)?.assets).toContainEqual(
        expect.objectContaining({ alias: "reference-2", libraryEntryId: reference.id }),
      ),
    );
  });
  it("filters the catalogue on the server while retaining an opened personal draft", async () => {
    const story: CreativeSkill = { ...SKILL, id: "story", title: "短剧方法", builtin: true, category: "STORY" };
    const ad: CreativeSkill = { ...SKILL, id: "ad", title: "品牌宣传", builtin: true, category: "ADVERTISING" };
    const requests: URL[] = [];
    server.use(
      http.get("/api/v1/skills", ({ request }) => {
        const url = new URL(request.url);
        requests.push(url);
        const category = url.searchParams.get("category");
        const items = category ? [story, ad].filter(skill => skill.category === category) : [SKILL, story, ad];
        return HttpResponse.json({ items, total: items.length, nextCursor: null });
      }),
    );
    const body = await openEditor();
    expect(screen.getByRole("region", { name: "内置 · 短剧叙事" })).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "内置 · 品牌广告" })).toBeInTheDocument();
    fireEvent.change(body, { target: { value: INITIAL.skillMd + "\nLocal unsaved thought" } });
    await userEvent.click(screen.getByRole("combobox", { name: "Skill 分类" }));
    await userEvent.click(await screen.findByRole("option", { name: "品牌广告" }));
    await waitFor(() => expect(screen.queryByRole("button", { name: story.title })).not.toBeInTheDocument());
    expect(screen.getByRole("button", { name: ad.title })).toBeInTheDocument();
    expect(body).toHaveValue(INITIAL.skillMd + "\nLocal unsaved thought");
    expect(requests.at(-1)?.searchParams.get("category")).toBe("ADVERTISING");
    expect(requests.at(-1)?.searchParams.has("cursor")).toBe(false);
  });
  it("saves a selected reference image for model context without a provider usage selector", async () => {
    const reference: LibraryEntry = {
      id: "library-image",
      name: "水彩风格参考",
      category: "OTHER",
      kind: "IMAGE",
      textContent: null,
      contentType: "image/png",
      byteSize: 120,
      width: 8,
      height: 8,
      durationMs: null,
      hasThumbnail: false,
      source: {},
      favorite: false,
      trashedAt: null,
      version: 7,
      createdAt: NOW,
      updatedAt: NOW,
    };
    let submitted: SaveSkillDraftRequest | undefined;
    server.use(
      http.get("/api/v1/library/entries", () =>
        HttpResponse.json({ items: [reference], nextCursor: null, total: 1, categoryCounts: { OTHER: 1 } }),
      ),
      http.put(`/api/v1/skills/${SKILL_ID}/draft`, async ({ request }) => {
        submitted = (await request.json()) as SaveSkillDraftRequest;
        return HttpResponse.json({ ...INITIAL, ...submitted, version: 2 });
      }),
    );
    await openEditor();
    const user = userEvent.setup();
    await user.click(screen.getByRole("tab", { name: "引用资产" }));
    await user.click(screen.getByRole("button", { name: "从我的资产选择图片" }));
    await user.click(await screen.findByRole("button", { name: "用作参考：水彩风格参考" }));
    expect(screen.getByRole("textbox", { name: "用途" })).toHaveValue(reference.name);
    expect(screen.queryByRole("combobox", { name: "用途" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "保存草稿" }));
    await waitFor(() =>
      expect(submitted?.assets).toEqual([
        {
          alias: "reference-1",
          libraryEntryId: reference.id,
          expectedLibraryVersion: 7,
          usage: "GUIDE",
          required: true,
          purpose: reference.name,
        },
      ]),
    );
  });
  it("moves a saved Skill to trash, lists it there, and restores editing with metadata CAS", async () => {
    let stored = SKILL;
    const filters: string[] = [];
    const changes: { expectedVersion: number; trashed: boolean }[] = [];
    server.use(
      http.get("/api/v1/skills", ({ request }) => {
        const trash = new URL(request.url).searchParams.get("trash") ?? "false";
        filters.push(trash);
        const items = stored.trashed === (trash === "true") ? [stored] : [];
        return HttpResponse.json({ items, nextCursor: null, total: items.length });
      }),
      http.patch(`/api/v1/skills/${SKILL_ID}`, async ({ request }) => {
        const changed = (await request.json()) as {
          expectedVersion: number;
          title: string;
          description: string;
          trashed: boolean;
        };
        changes.push(changed);
        stored = { ...stored, ...changed, version: stored.version + 1 };
        return HttpResponse.json(stored);
      }),
    );
    const body = await openEditor();
    await userEvent.click(screen.getByRole("button", { name: "移入回收站" }));
    await screen.findByText("此 Skill 已在回收站。恢复后才能编辑、发布或试用；历史运行保留固定版本。");
    expect(body).toHaveValue(INITIAL.skillMd);
    expect(body).toHaveAttribute("readonly");
    expect(screen.queryByRole("button", { name: "发布版本" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "在导演中试用" })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole("radio", { name: "回收站" }));
    expect(await screen.findByRole("button", { name: "温暖手绘" })).toHaveAttribute("aria-pressed", "true");
    await userEvent.click(screen.getByRole("button", { name: "恢复 Skill" }));
    await waitFor(() => expect(body).not.toHaveAttribute("readonly"));
    expect(changes.map(({ expectedVersion, trashed }) => ({ expectedVersion, trashed }))).toEqual([
      { expectedVersion: 1, trashed: true },
      { expectedVersion: 2, trashed: false },
    ]);
    expect(filters).toContain("true");
    await userEvent.click(screen.getByRole("radio", { name: "可用 Skill" }));
    expect(await screen.findByRole("button", { name: "温暖手绘" })).toHaveAttribute("aria-pressed", "true");
  });
  it("keeps the selected saved draft after a trash conflict and refreshes metadata before retrying", async () => {
    const changes: number[] = [];
    server.use(
      http.get(`/api/v1/skills/${SKILL_ID}`, () => HttpResponse.json({ ...SKILL, version: 4 })),
      http.patch(`/api/v1/skills/${SKILL_ID}`, async ({ request }) => {
        const changed = (await request.json()) as { expectedVersion: number; trashed: boolean };
        changes.push(changed.expectedVersion);
        return changed.expectedVersion === 4
          ? HttpResponse.json({ ...SKILL, ...changed, version: 5 })
          : HttpResponse.json({ code: "SKILL_VERSION_CONFLICT", detail: "synthetic conflict" }, { status: 409 });
      }),
    );
    const body = await openEditor();
    await userEvent.click(screen.getByRole("button", { name: "移入回收站" }));
    await screen.findByText("服务器版本已变化。草稿已保留；刷新后核对并重试。");
    expect(body).toHaveValue(INITIAL.skillMd);
    expect(body).not.toHaveAttribute("readonly");
    expect(screen.getByRole("button", { name: "温暖手绘" })).toHaveAttribute("aria-pressed", "true");
    await userEvent.click(screen.getByRole("button", { name: "刷新服务器版本" }));
    await waitFor(() =>
      expect(screen.queryByText("服务器版本已变化。草稿已保留；刷新后核对并重试。")).not.toBeInTheDocument(),
    );
    await userEvent.click(screen.getByRole("button", { name: "移入回收站" }));
    await waitFor(() => expect(body).toHaveAttribute("readonly"));
    expect(changes).toEqual([1, 4]);
  });
  it("refreshes the catalogue version after publication before moving it to trash", async () => {
    const changes: number[] = [];
    server.use(
      http.post(`/api/v1/skills/${SKILL_ID}/versions`, () =>
        HttpResponse.json({
          id: "published-cas",
          skillId: SKILL_ID,
          status: "SUCCEEDED",
          resultVersionId: VERSION_ID,
          errorCode: null,
          errorDetail: null,
        }),
      ),
      http.get("/api/v1/skills/operations/published-cas", () =>
        HttpResponse.json({
          id: "published-cas",
          skillId: SKILL_ID,
          status: "SUCCEEDED",
          resultVersionId: VERSION_ID,
          errorCode: null,
          errorDetail: null,
        }),
      ),
      http.get(`/api/v1/skills/${SKILL_ID}`, () => HttpResponse.json({ ...SKILL, version: 2 })),
      http.patch(`/api/v1/skills/${SKILL_ID}`, async ({ request }) => {
        const changed = (await request.json()) as { expectedVersion: number; trashed: boolean };
        changes.push(changed.expectedVersion);
        return changed.expectedVersion === 2
          ? HttpResponse.json({ ...SKILL, ...changed, version: 3 })
          : HttpResponse.json(
              { code: "SKILL_VERSION_CONFLICT", detail: "synthetic publication changed catalogue" },
              { status: 409 },
            );
      }),
    );
    const body = await openEditor();
    await userEvent.click(screen.getByRole("button", { name: "发布版本" }));
    await screen.findByText("版本已发布");
    await waitFor(() => expect(screen.getByRole("button", { name: "移入回收站" })).toBeEnabled());
    await userEvent.click(screen.getByRole("button", { name: "移入回收站" }));
    await waitFor(() => expect(body).toHaveAttribute("readonly"));
    expect(changes).toEqual([2]);
  });
  it("preserves title and document edits made during publication and requires saving them before trashing", async () => {
    let finishPublication: () => void = () => {},
      finishSave: () => void = () => {};
    const publicationResponse = new Promise<void>(resolve => {
      finishPublication = resolve;
    });
    const draftResponse = new Promise<void>(resolve => {
      finishSave = resolve;
    });
    const titles: { expectedVersion: number; title: string }[] = [];
    const drafts: SaveSkillDraftRequest[] = [];
    server.use(
      http.post(`/api/v1/skills/${SKILL_ID}/versions`, () =>
        HttpResponse.json({
          id: "publishing-edits",
          skillId: SKILL_ID,
          status: "ACCEPTED",
          resultVersionId: null,
          errorCode: null,
          errorDetail: null,
        }),
      ),
      http.get("/api/v1/skills/operations/publishing-edits", async () => {
        await publicationResponse;
        return HttpResponse.json({
          id: "publishing-edits",
          skillId: SKILL_ID,
          status: "SUCCEEDED",
          resultVersionId: VERSION_ID,
          errorCode: null,
          errorDetail: null,
        });
      }),
      http.get(`/api/v1/skills/${SKILL_ID}`, () => HttpResponse.json({ ...SKILL, version: 2 })),
      http.patch(`/api/v1/skills/${SKILL_ID}`, async ({ request }) => {
        const changed = (await request.json()) as { expectedVersion: number; title: string };
        titles.push(changed);
        return HttpResponse.json({ ...SKILL, ...changed, version: 3 });
      }),
      http.put(`/api/v1/skills/${SKILL_ID}/draft`, async ({ request }) => {
        const changed = (await request.json()) as SaveSkillDraftRequest;
        drafts.push(changed);
        await draftResponse;
        return HttpResponse.json({ ...INITIAL, ...changed, version: 2 });
      }),
    );
    const body = await openEditor();
    await userEvent.click(screen.getByRole("button", { name: "发布版本" }));
    await screen.findByText("正在归档并发布…");
    const title = document.getElementById(`skill-title-${SKILL_ID}`)!;
    fireEvent.change(title, { target: { value: "A newer display name" } });
    fireEvent.change(body, { target: { value: "# Later document edit" } });
    expect(screen.getByRole("button", { name: "移入回收站" })).toBeDisabled();
    finishPublication();
    await waitFor(() => expect(drafts).toHaveLength(1), { timeout: 4000 });
    expect(titles).toEqual([expect.objectContaining({ expectedVersion: 2, title: "A newer display name" })]);
    expect(drafts[0]?.skillMd).toBe("# Later document edit");
    expect(screen.getByRole("button", { name: "移入回收站" })).toBeDisabled();
    finishSave();
    await waitFor(() => expect(screen.getByRole("button", { name: "移入回收站" })).toBeEnabled());
    expect(body).toHaveValue("# Later document edit");
    expect(title).toHaveValue("A newer display name");
  });
  it("retains edited document after a draft CAS conflict", async () => {
    let submitted: SaveSkillDraftRequest | undefined;
    server.use(
      http.put(`/api/v1/skills/${SKILL_ID}/draft`, async ({ request }) => {
        submitted = (await request.json()) as SaveSkillDraftRequest;
        return HttpResponse.json(
          { code: "SKILL_DRAFT_VERSION_CONFLICT", detail: "synthetic conflict" },
          { status: 409 },
        );
      }),
    );
    const input = await openEditor();
    fireEvent.change(input, { target: { value: "# My edited creative method" } });
    await screen.findByText("服务器版本已变化。草稿已保留；刷新后核对并重试。");
    expect(input).toHaveValue("# My edited creative method");
    expect(submitted?.expectedVersion).toBe(1);
    expect(submitted?.skillMd).toBe("# My edited creative method");
  });
  it("saves a newer title typed while an earlier draft save is pending", async () => {
    let releaseFirst: () => void = () => {};
    const firstResponse = new Promise<void>(resolve => {
      releaseFirst = resolve;
    });
    const titles: string[] = [];
    let draftCalls = 0;
    let catalogueVersion = SKILL.version;
    server.use(
      http.patch(`/api/v1/skills/${SKILL_ID}`, async ({ request }) => {
        const change = (await request.json()) as { title: string; expectedVersion: number };
        titles.push(change.title);
        catalogueVersion++;
        return HttpResponse.json({ ...SKILL, title: change.title, version: catalogueVersion });
      }),
      http.put(`/api/v1/skills/${SKILL_ID}/draft`, async ({ request }) => {
        const saved = (await request.json()) as SaveSkillDraftRequest;
        draftCalls++;
        if (draftCalls === 1) await firstResponse;
        return HttpResponse.json({ ...INITIAL, ...saved, version: saved.expectedVersion + 1 });
      }),
    );
    await openEditor();
    const title = document.getElementById(`skill-title-${SKILL_ID}`)!;
    fireEvent.change(title, { target: { value: "First title" } });
    await waitFor(() => expect(draftCalls).toBe(1));
    fireEvent.change(title, { target: { value: "Latest title" } });
    releaseFirst();
    await waitFor(() => expect(titles).toEqual(["First title", "Latest title"]), { timeout: 4000 });
    await waitFor(() => expect(draftCalls).toBe(2));
    expect(title).toHaveValue("Latest title");
  });
  it("publishes a fixed version through a durable operation without model calls", async () => {
    const publish = vi.fn();
    let queries = 0;
    const run = vi.fn();
    server.use(
      http.post(`/api/v1/skills/${SKILL_ID}/versions`, async ({ request }) => {
        publish(await request.json());
        return HttpResponse.json({
          id: "operation-1",
          skillId: SKILL_ID,
          status: "ACCEPTED",
          resultVersionId: null,
          errorCode: null,
          errorDetail: null,
        });
      }),
      http.get("/api/v1/skills/operations/operation-1", () => {
        queries++;
        return HttpResponse.json({
          id: "operation-1",
          skillId: SKILL_ID,
          status: "SUCCEEDED",
          resultVersionId: VERSION_ID,
          errorCode: null,
          errorDetail: null,
        });
      }),
      http.post("/api/v1/projects/:id/runs", () => {
        run();
        return HttpResponse.json({});
      }),
    );
    await openEditor();
    await userEvent.click(screen.getByRole("button", { name: "发布版本" }));
    await screen.findByText("版本已发布");
    expect(publish).toHaveBeenCalledWith(
      expect.objectContaining({ expectedDraftVersion: 1, commandKey: expect.any(String) }),
    );
    expect(queries).toBeGreaterThan(0);
    expect(run).not.toHaveBeenCalled();
    expect(await screen.findByText("这是不可变发布版本；编辑不会改变它。")).toBeInTheDocument();
  });
  it("opens the project director with a fixed Skill version without selecting an Agent or submitting a Run", async () => {
    const run = vi.fn();
    server.use(
      http.post("/api/v1/projects/:id/runs", () => {
        run();
        return HttpResponse.json({});
      }),
      http.get("/api/v1/projects", () =>
        HttpResponse.json({ items: [{ id: "project-1", name: "Mock canvas", status: "ACTIVE" }], nextCursor: null }),
      ),
    );
    await openEditor();
    await selectValue(screen.getByRole("combobox", { name: "发布版本" }), VERSION_ID);
    await userEvent.click(await screen.findByRole("button", { name: "在导演中试用" }));
    const project = screen.getByRole("combobox", { name: "项目" });
    await userEvent.click(project);
    await userEvent.click(await screen.findByRole("option", { name: "Mock canvas" }));
    expect(within(screen.getByRole("dialog", { name: "在导演中试用" })).getAllByRole("combobox")).toHaveLength(1);
    await userEvent.click(screen.getByRole("button", { name: "打开创作导演" }));
    expect(await screen.findByTestId("destination")).toHaveTextContent(
      "skillId=skill-1&skillVersionId=skill-version-1",
    );
    expect(run).not.toHaveBeenCalled();
  });
});
