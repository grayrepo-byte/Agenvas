import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import ts from "typescript";

const root = path.resolve(import.meta.dirname, "../src");
const directory = path.join(root, "shared/i18n/locales");
const source = JSON.parse(fs.readFileSync(path.join(directory, "zh.json"), "utf8"));
const keys = Object.keys(source).sort();
const tokens = (text) => JSON.stringify((text.match(/\{\w+\}/g) ?? []).sort());
const failures = [];
for (const locale of ["en", "ru", "ja"]) {
  const catalog = JSON.parse(fs.readFileSync(path.join(directory, `${locale}.json`), "utf8"));
  if (JSON.stringify(Object.keys(catalog).sort()) !== JSON.stringify(keys)) failures.push(`${locale}: catalog keys differ from zh`);
  for (const key of keys) {
    const value = catalog[key];
    if (typeof value !== "string" || !value.trim()) failures.push(`${locale}: missing ${key}`);
    else if (tokens(key) !== tokens(value)) failures.push(`${locale}: interpolation tokens differ for ${key}`);
  }
}

/** Source IDs can also be deferred display metadata; provider values and fixed prompts stay literal. */
const deferredIds = new Set(["VIEW_ANGLE_OPTIONS", "DEFAULT_TEXT_CARD_TITLE", "READ_ERROR", "SAVE_ERROR", "LOCALE_NAMES"]);
function permittedLiteral(node) {
  for (let ancestor = node; ancestor; ancestor = ancestor.parent) {
    if (ts.isTypeNode(ancestor)) return true;
    if (ts.isPropertyAssignment(ancestor)
        && ["prompt", "instruction", "text", "value"].includes(ancestor.name.getText().replaceAll('"', ""))) return true;
    if (ts.isJsxAttribute(ancestor) && ancestor.name.getText() === "value") return true;
    if (ts.isVariableDeclaration(ancestor) && (deferredIds.has(ancestor.name.getText())
        || ["AUDIO_AGENT_INSTRUCTION", "instruction"].includes(ancestor.name.getText()))) return true;
  }
  const text = node.text ?? node.getText();
  return text.includes("只输出提示词") || text.startsWith("把下列音频生成提示词")
    || text === "根据明确绑定的输入创作内容。"
    || (text === "英文" && ts.isCallExpression(node.parent) && node.parent.expression.getText() === "useState");
}

function inspect(directory) {
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    const file = path.join(directory, entry.name);
    if (entry.isDirectory()) {
      if (entry.name !== "test" && entry.name !== "locales") inspect(file);
      continue;
    }
    if (!/\.(ts|tsx)$/.test(file) || /\.(test|spec)\./.test(file) || entry.name === "schema.ts") continue;
    // These are reviewed provider catalogs, not UI components. Their labels are translated at use sites.
    if (["voiceCatalog.ts", "autodlWorkflows.ts"].includes(entry.name)) continue;
    const ast = ts.createSourceFile(file, fs.readFileSync(file, "utf8"), ts.ScriptTarget.Latest, true);
    function visit(node) {
      if (ts.isCallExpression(node) && node.expression.getText() === "t" && node.arguments[0]
          && ts.isStringLiteral(node.arguments[0])) {
        if (!Object.hasOwn(source, node.arguments[0].text)) failures.push(`${path.relative(root, file)}: missing source ID ${node.arguments[0].text}`);
      }
      if ((ts.isStringLiteral(node) || ts.isNoSubstitutionTemplateLiteral(node) || ts.isJsxText(node))
          && /\p{Script=Han}/u.test(node.text) && !permittedLiteral(node)) {
        const translated = ts.isCallExpression(node.parent) && node.parent.expression.getText() === "t" && node.parent.arguments[0] === node;
        if (!translated) failures.push(`${path.relative(root, file)}:${ast.getLineAndCharacterOfPosition(node.getStart()).line + 1}: untranslated UI copy`);
      }
      ts.forEachChild(node, visit);
    }
    visit(ast);
  }
}
inspect(root);
if (failures.length) {
  process.stderr.write(`${failures.join("\n")}\n`);
  process.exitCode = 1;
} else process.stdout.write(`i18n check passed: ${keys.length} messages in four catalogs; UI copy uses shared resources.\n`);
