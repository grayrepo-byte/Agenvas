import { readdir, readFile } from "node:fs/promises";
import { fileURLToPath, URL } from "node:url";
import { join, relative } from "node:path";
import console from "node:console";
import process from "node:process";

const sourceRoot = fileURLToPath(new URL("../src/", import.meta.url));
const tokenFile = join(sourceRoot, "shared/ui/design-tokens.css");
// Blue, purple and magenta belong to the shared brand palette. Red error and
// green/amber status colors, image paint and reference identities are separate.
const BRAND_HUE_MIN = 180;
const BRAND_HUE_MAX = 340;
const MIN_SATURATION = 0.15;
const failures = [];

function isBrandColor(color) {
  let rgb;
  if (color.startsWith("#")) {
    const digits = color.slice(1);
    const expanded = digits.length <= 4 ? [...digits].map((digit) => digit.repeat(2)).join("") : digits;
    rgb = [0, 2, 4].map((offset) => Number.parseInt(expanded.slice(offset, offset + 2), 16) / 255);
  } else {
    const channels = color.match(/[\d.]+%?/g) ?? [];
    if (channels.length < 3) return false;
    if (color.startsWith("hsl")) {
      const hue = (Number.parseFloat(channels[0]) % 360 + 360) % 360;
      return Number.parseFloat(channels[1]) / 100 >= MIN_SATURATION && hue >= BRAND_HUE_MIN && hue <= BRAND_HUE_MAX;
    }
    rgb = channels.slice(0, 3).map((channel) => Number.parseFloat(channel) / (channel.endsWith("%") ? 100 : 255));
  }
  const [r, g, b] = rgb;
  const max = Math.max(r, g, b);
  const min = Math.min(r, g, b);
  const delta = max - min;
  if (delta === 0 || delta / max < MIN_SATURATION) return false;
  const sector = max === r ? (g - b) / delta : max === g ? (b - r) / delta + 2 : (r - g) / delta + 4;
  const hue = (sector * 60 + 360) % 360;
  return hue >= BRAND_HUE_MIN && hue <= BRAND_HUE_MAX;
}

async function checkDirectory(directory) {
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) {
      await checkDirectory(path);
    } else if (entry.name.endsWith(".css") && path !== tokenFile) {
      const content = (await readFile(path, "utf8")).replace(/\/\*[\s\S]*?\*\//g, (comment) => comment.replace(/[^\n]/g, " "));
      content.split("\n").forEach((line, index) => {
        const colors = [...line.matchAll(/#[\da-f]{3,8}\b|(?:rgb|hsl)a?\([^)]*\)/gi)].map(([color]) => color.toLowerCase());
        if (colors.some(isBrandColor) || /--(?:ui-(?:accent|focus)[\w-]*|accent)\s*:\s*(?!var\()[^\s]/.test(line)) {
          failures.push(`${relative(sourceRoot, path)}:${index + 1}: Use shared design tokens for brand colors.`);
        }
      });
    }
  }
}

await checkDirectory(sourceRoot);
if (failures.length) {
  console.error(failures.join("\n"));
  process.exitCode = 1;
} else {
  console.log("Theme color check passed: brand colors are centralized.");
}
