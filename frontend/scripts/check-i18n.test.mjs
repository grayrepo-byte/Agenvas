import assert from "node:assert/strict";
import { test } from "node:test";
import { checkCatalogs, checkSource, parseCatalog } from "./check-i18n.mjs";

const catalogs = () => ({
  zh: { "canvas.selection.count": "已选 {count} 个节点" },
  en: { "canvas.selection.count": "{count} nodes selected" },
  ru: { "canvas.selection.count": "Выбрано узлов: {count}" },
  ja: { "canvas.selection.count": "{count} 個のノードを選択" },
});

test("validates placeholders in values with language-neutral semantic IDs", () => {
  assert.deepEqual(checkCatalogs(catalogs()), []);
  const modifiedCopy = catalogs();
  modifiedCopy.zh["canvas.selection.count"] = "选中节点：{count}";
  assert.deepEqual(checkCatalogs(modifiedCopy), []);
});

test("rejects raw text IDs, including English source sentences", () => {
  for (const key of ["已选 {count} 个节点", "Selected nodes", "Save"]) {
    const invalid = Object.fromEntries(Object.keys(catalogs()).map((locale) => [locale, { [key]: "Copy" }]));
    assert.match(checkCatalogs(invalid).join("\n"), /invalid semantic message key/);
  }
});

test("rejects duplicate catalog entries before JSON parsing can hide a translation", () => {
  assert.throws(() => parseCatalog("en.json", '{"common.save":"Save","common.save":"Overwrite"}'), /duplicate message key common.save/);
  assert.deepEqual(parseCatalog("en.json", '{"common.save":"Save"}'), { "common.save": "Save" });
});

test("rejects missing keys, blank source values, and changed placeholder counts", () => {
  const invalid = catalogs();
  invalid.en = {};
  invalid.zh["auth.login.title"] = "";
  invalid.ru["canvas.selection.count"] = "{count} {count}";
  invalid.ja["canvas.selection.count"] = "{total}";
  const errors = checkCatalogs(invalid).join("\n");
  assert.match(errors, /catalog keys differ/);
  assert.match(errors, /zh: missing auth.login.title/);
  assert.match(errors, /ru: interpolation tokens differ/);
  assert.match(errors, /ja: interpolation tokens differ/);
});

test("checks both translators, aliases, and template literals for unknown keys", () => {
  const source = `import { t as label, translate as localize } from "../i18n";
    t("auth.missing"); translate("en", "auth.other");
    label(\`auth.template\`); localize("ja", "auth.alias");`;
  assert.equal(checkSource("example.ts", source, catalogs().zh).filter((failure) => failure.includes("unknown message key")).length, 4);
  assert.deepEqual(checkSource("example.ts", 't("canvas.selection.count", {count: 3});', catalogs().zh), []);
});

test("keeps UI literals checked while preserving native names and user prompts", () => {
  assert.match(checkSource("view.tsx", '<button>保存</button>', catalogs().zh).join("\n"), /untranslated UI copy/);
  assert.match(checkSource("view.ts", 'const READ_ERROR = "读取失败";', catalogs().zh).join("\n"), /untranslated UI copy/);
  assert.deepEqual(checkSource("data.ts", 'const LOCALE_NAMES = { zh: "中文" }; const input = { prompt: "用户内容" };', catalogs().zh), []);
});
