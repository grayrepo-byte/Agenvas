import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import { pathToFileURL } from "node:url";
import ts from "typescript";

const MESSAGE_KEY_PATTERN = /^[a-z][a-zA-Z0-9]*(?:\.[a-z][a-zA-Z0-9]*)+$/;
const LOCALES = ["zh", "en", "ru", "ja"];
const tokens = (text) => JSON.stringify((text.match(/\{\w+\}/g) ?? []).sort());

/** Keys identify UI intent; interpolation belongs to the translated values, never to an ID. */
export function checkCatalogs(catalogs) {
  const source = catalogs.zh;
  const keys = Object.keys(source).sort();
  const failures = [];
  for (const key of keys) {
    if (!MESSAGE_KEY_PATTERN.test(key)) failures.push(`zh: invalid semantic message key ${key}`);
  }
  for (const locale of LOCALES) {
    const catalog = catalogs[locale];
    if (JSON.stringify(Object.keys(catalog).sort()) !== JSON.stringify(keys)) failures.push(`${locale}: catalog keys differ from zh`);
    for (const key of keys) {
      const value = catalog[key];
      if (typeof value !== "string" || !value.trim()) failures.push(`${locale}: missing ${key}`);
      else if (typeof source[key] === "string" && tokens(source[key]) !== tokens(value)) failures.push(`${locale}: interpolation tokens differ for ${key}`);
    }
  }
  return failures;
}

/** Native language names and fixed provider prompts are data, not translatable UI templates. */
const literalData = new Set(["LOCALE_NAMES", "AUDIO_AGENT_INSTRUCTION", "instruction"]);
function permittedLiteral(node) {
  for (let ancestor = node; ancestor; ancestor = ancestor.parent) {
    if (ts.isTypeNode(ancestor)) return true;
    if (ts.isPropertyAssignment(ancestor)
        && ["prompt", "instruction", "text", "value"].includes(ancestor.name.getText().replaceAll('"', ""))) return true;
    if (ts.isJsxAttribute(ancestor) && ancestor.name.getText() === "value") return true;
    if (ts.isVariableDeclaration(ancestor) && literalData.has(ancestor.name.getText())) return true;
  }
  const text = node.text ?? node.getText();
  return text.includes("只输出提示词") || text.startsWith("把下列音频生成提示词")
    || text === "根据明确绑定的输入创作内容。"
    || (text === "英文" && ts.isCallExpression(node.parent) && node.parent.expression.getText() === "useState");
}

export function checkSource(file, sourceText, catalog) {
  const failures = [];
  const ast = ts.createSourceFile(file, sourceText, ts.ScriptTarget.Latest, true);
  const translators = new Map([["t", 0], ["translate", 1]]);
  // Keep checks effective if a component renames one of the shared translation imports.
  function collectImports(node) {
    if (ts.isImportSpecifier(node)) {
      const imported = (node.propertyName ?? node.name).text;
      if (imported === "t" || imported === "translate") translators.set(node.name.text, imported === "t" ? 0 : 1);
    }
    ts.forEachChild(node, collectImports);
  }
  collectImports(ast);
  function visit(node) {
    if (ts.isCallExpression(node)) {
      const position = translators.get(node.expression.getText());
      const argument = position === undefined ? undefined : node.arguments[position];
      if (argument && (ts.isStringLiteral(argument) || ts.isNoSubstitutionTemplateLiteral(argument))) {
        if (!Object.hasOwn(catalog, argument.text)) failures.push(`${file}: unknown message key ${argument.text}`);
        else if (!MESSAGE_KEY_PATTERN.test(argument.text)) failures.push(`${file}: invalid semantic message key ${argument.text}`);
      }
    }
    if ((ts.isStringLiteral(node) || ts.isNoSubstitutionTemplateLiteral(node) || ts.isJsxText(node))
        && /\p{Script=Han}/u.test(node.text) && !permittedLiteral(node)) {
      failures.push(`${file}:${ast.getLineAndCharacterOfPosition(node.getStart()).line + 1}: untranslated UI copy`);
    }
    ts.forEachChild(node, visit);
  }
  visit(ast);
  return failures;
}

export function parseCatalog(file, contents) {
  const catalog = JSON.parse(contents);
  // JSON.parse silently overwrites duplicate keys; reject them before checking catalog parity.
  const ast = ts.parseJsonText(file, contents);
  const keys = new Set();
  const object = ast.statements[0]?.expression;
  if (object && ts.isObjectLiteralExpression(object)) {
    for (const property of object.properties) {
      if (!ts.isPropertyAssignment(property) || !ts.isStringLiteral(property.name)) continue;
      const key = property.name.text;
      if (keys.has(key)) throw new Error(`${path.basename(file)}: duplicate message key ${key}`);
      keys.add(key);
    }
  }
  return catalog;
}

function run() {
  const root = path.resolve(import.meta.dirname, "../src");
  const directory = path.join(root, "shared/i18n/locales");
  const catalogs = Object.fromEntries(LOCALES.map((locale) => {
    const file = path.join(directory, `${locale}.json`);
    return [locale, parseCatalog(file, fs.readFileSync(file, "utf8"))];
  }));
  const failures = checkCatalogs(catalogs);
  function inspect(directory) {
    for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
      const file = path.join(directory, entry.name);
      if (entry.isDirectory()) {
        if (entry.name !== "test" && entry.name !== "locales") inspect(file);
        continue;
      }
      if (!/\.(ts|tsx)$/.test(file) || /\.(test|spec)\./.test(file) || entry.name === "schema.ts") continue;
      // Reviewed protocol catalogs keep provider names and parameter values literal.
      if (["voiceCatalog.ts", "autodlWorkflows.ts"].includes(entry.name)) continue;
      failures.push(...checkSource(path.relative(root, file), fs.readFileSync(file, "utf8"), catalogs.zh));
    }
  }
  inspect(root);
  if (failures.length) {
    process.stderr.write(`${failures.join("\n")}\n`);
    process.exitCode = 1;
  } else process.stdout.write(`i18n check passed: ${Object.keys(catalogs.zh).length} semantic messages in four catalogs; UI copy uses shared resources.\n`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) run();
