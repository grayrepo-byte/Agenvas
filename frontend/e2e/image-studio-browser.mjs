import assert from "node:assert/strict";
import { mkdir, writeFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { createServer } from "vite";
import { createBrowserSession } from "./browser-session.mjs";

const root = fileURLToPath(new URL("../", import.meta.url));
const server = await createServer({
  root,
  configFile: join(root, "vite.config.ts"),
  server: { host: "127.0.0.1", port: 0, open: false },
});
const viewports = [
  { width: 1440, height: 1000 },
  { width: 390, height: 844 },
  { width: 844, height: 390 },
];
let browser;
try {
  await server.listen();
  browser = await createBrowserSession({ timeoutMs: 60_000 });
  const { cdp, page, waitFor } = browser;
  const base = server.resolvedUrls.local[0];
  async function navigate(url) {
    const previous = await page(() => performance.timeOrigin);
    await cdp("Page.navigate", { url });
    await waitFor(previous => performance.timeOrigin !== previous, previous);
  }
  async function click(selector) {
    const point = await page(async selector => {
      const e = [...document.querySelectorAll(selector)].find(e => e.getClientRects().length);
      if (!e) throw new Error(`Missing visible control: ${selector}`);
      const animations = [];
      for (let parent = e; parent; parent = parent.parentElement)
        animations.push(
          ...parent.getAnimations().filter(animation => animation.effect.getTiming().iterations !== Infinity),
        );
      await Promise.all(animations.map(animation => animation.finished.catch(() => {})));
      e.scrollIntoView({ block: "nearest", inline: "nearest" });
      await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
      const r = e.getBoundingClientRect();
      const point = { x: r.x + r.width / 2, y: r.y + r.height / 2 };
      if (!e.contains(document.elementFromPoint(point.x, point.y))) throw new Error(`Control is obscured: ${selector}`);
      return point;
    }, selector);
    for (const type of ["mousePressed", "mouseReleased"])
      await cdp("Input.dispatchMouseEvent", { type, ...point, button: "left", clickCount: 1 });
  }
  async function escape() {
    for (const type of ["keyDown", "keyUp"])
      await cdp("Input.dispatchKeyEvent", { type, key: "Escape", code: "Escape", windowsVirtualKeyCode: 27 });
  }
  async function openHistory() {
    if (
      !(await page(
        () => document.querySelector(".image-studio-history-toggle").getAttribute("aria-expanded") === "true",
      ))
    )
      await click(".image-studio-history-toggle");
    await waitFor(() =>
      [...document.querySelectorAll(".image-studio-history-item")].some(e => e.getClientRects().length),
    );
  }
  async function chooseHistory(title) {
    await openHistory();
    await click(`.image-studio-history-item[title^="${title}"]`);
  }
  for (const viewport of viewports) {
    await cdp("Emulation.setDeviceMetricsOverride", { ...viewport, deviceScaleFactor: 1, mobile: false });
    for (const locale of ["zh", "en", "ru", "ja"]) {
      await navigate(new URL(`/e2e/image-studio.html?locale=${locale}`, base).href);
      await waitFor(() => document.querySelectorAll(".image-studio-result-card").length === 4);
      await waitFor(() =>
        [...document.querySelectorAll(".image-studio-result-card img")].every(i => i.complete && i.naturalWidth > 0),
      );
      const layout = await page(() => ({
        overflow: document.documentElement.scrollWidth > innerWidth,
        composer: document.querySelector(".image-studio-controls").getBoundingClientRect().toJSON(),
        results: document.querySelector(".image-studio-results").getBoundingClientRect().toJSON(),
        images: [...document.querySelectorAll(".image-studio-image-button img")].map(i => ({
          width: i.clientWidth,
          height: i.clientHeight,
          fit: getComputedStyle(i).objectFit,
        })),
        downloads: [...document.querySelectorAll(".image-studio-result-card a")].map(a => a.getAttribute("href")),
      }));
      assert.equal(layout.overflow, false, `No horizontal overflow: ${viewport.width}/${locale}`);
      assert.ok(layout.images.every(i => i.width > 70 && i.height > 70 && i.fit === "contain"));
      assert.ok(
        layout.downloads.every(
          url => url.startsWith("/api/v1/projects/synthetic-image-studio/assets/") && url.endsWith("/content"),
        ),
      );
      assert.ok(layout.results.bottom <= layout.composer.top + 1, "Composer reserves space below results");
      if (viewport.width === 1440) {
        assert.ok(layout.composer.bottom <= viewport.height + 1, "Desktop composer stays visible");
        assert.ok(layout.results.height > viewport.height * 0.5, "Results own most desktop height");
        await click(".image-studio-history-toggle");
        await waitFor(
          () => document.querySelector(".image-studio-history-toggle").getAttribute("aria-expanded") === "false",
        );
        assert.ok(
          (await page(() => document.querySelector(".image-studio-results").getBoundingClientRect().width)) >
            layout.results.width,
          "Collapsing history returns width to results",
        );
      }
      await page(() => {
        const e = document.querySelector("textarea");
        Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, "value").set.call(e, "Unsaved synthetic draft");
        e.dispatchEvent(new Event("input", { bubbles: true }));
      });
      await openHistory();
      if (viewport.width <= 900) {
        await waitFor(() => Boolean(document.querySelector(".image-studio-history-drawer[role=dialog]")));
        assert.equal(await page(() => Boolean(document.activeElement.closest(".image-studio-history-drawer"))), true);
        await escape();
        await waitFor(() => !document.querySelector(".image-studio-history-drawer[role=dialog]"));
        await waitFor(() => document.activeElement.classList.contains("image-studio-history-toggle"));
        assert.equal(
          await page(() => document.activeElement.classList.contains("image-studio-history-toggle")),
          true,
          "Escape restores trigger focus",
        );
        await openHistory();
      } else {
        assert.ok(
          await page(
            () =>
              document.querySelector(".image-studio-history").getBoundingClientRect().left >=
              document.querySelector(".image-studio-results").getBoundingClientRect().right - 1,
          ),
          "History sits right of results",
        );
      }
      assert.equal(
        await page(() =>
          Boolean(document.querySelector('.image-studio-history-item[title^="清晨"] img[src$="/thumbnail"]')),
        ),
        true,
      );
      assert.equal(
        await page(() => document.querySelector('.image-studio-history-item[title^="Uncertain"] img') === null),
        true,
      );
      await chooseHistory("Uncertain");
      await waitFor(() => document.querySelectorAll(".image-studio-result-card").length === 0);
      assert.equal(
        await page(() => document.querySelector("textarea").value),
        "Unsaved synthetic draft",
        "History preserves draft",
      );
      await chooseHistory("Partial success");
      await waitFor(
        () =>
          document.querySelectorAll(".image-studio-result-card").length === 1 &&
          document.body.innerText.includes("SYNTHETIC_PROVIDER_REJECTED"),
      );
      await chooseHistory("清晨");
      await waitFor(() => document.querySelectorAll(".image-studio-result-card").length === 4);
      await click(".image-studio-results-header button");
      await waitFor(() => document.querySelector("textarea").value.startsWith("清晨海边的小屋"));
      if (locale === "zh" && viewport.width === 1440) {
        await click(".image-studio-advanced-toggle");
        await waitFor(() => Boolean(document.querySelector('button[role="checkbox"]')));
        await click(".image-studio-advanced-toggle");
        await page(() => {
          const input = document.querySelector('input[type="file"]');
          const transfer = new DataTransfer();
          transfer.items.add(new File([new Uint8Array([1, 2, 3])], "synthetic-reference.png", { type: "image/png" }));
          input.files = transfer.files;
          input.dispatchEvent(new Event("change", { bubbles: true }));
        });
        await waitFor(() => document.querySelectorAll(".image-studio-reference").length === 1);
        await click('.image-studio-controls button[type="submit"]');
        await waitFor(() => [...document.querySelectorAll("button")].some(b => b.textContent.trim() === "重试受理"));
        await page(() => {
          [...document.querySelectorAll("button")].find(b => b.textContent.trim() === "重试受理").click();
        });
        await waitFor(
          () => window.__imageStudio.evidence().writes.length === 2 && !document.querySelector("fieldset").disabled,
        );
        const evidence = await page(() => window.__imageStudio.evidence());
        assert.deepEqual(evidence.writes[0], evidence.writes[1]);
        assert.equal(evidence.uploads, 1);
        assert.equal(evidence.writes[0].request.parameters.generationCount, 4);
        assert.deepEqual(evidence.writes[0].request.referenceVersionIds, ["synthetic-reference-version"]);
        assert.equal(JSON.stringify(evidence.writes).includes("canvasItemId"), false);
        assert.deepEqual(evidence.unexpected, []);
        await click(".image-studio-image-button");
        await waitFor(() => Boolean(document.querySelector(".yarl__root")));
        await escape();
        await waitFor(() => !document.querySelector(".yarl__root"));
        if (process.env.AGENVAS_STUDIO_SCREENSHOT) {
          await mkdir(dirname(process.env.AGENVAS_STUDIO_SCREENSHOT), { recursive: true, mode: 0o700 });
          const capture = await cdp("Page.captureScreenshot", { format: "png", captureBeyondViewport: false });
          await writeFile(process.env.AGENVAS_STUDIO_SCREENSHOT, Buffer.from(capture.data, "base64"), { mode: 0o600 });
        }
      }
    }
    for (const scenario of ["portrait", "landscape"]) {
      await navigate(new URL(`/e2e/image-studio.html?scenario=${scenario}&locale=zh`, base).href);
      await waitFor(() => document.querySelectorAll(".image-studio-result-card").length === 1);
      await waitFor(() => {
        const i = document.querySelector(".image-studio-image-button img");
        return i?.complete && i.naturalWidth > 0;
      });
      assert.equal(
        await page(() => document.querySelector('.image-studio-result-grid[data-single="true"]') !== null),
        true,
      );
      const image = await page(() => {
        const i = document.querySelector(".image-studio-image-button img");
        return {
          ratio: i.naturalWidth / i.naturalHeight,
          fit: getComputedStyle(i).objectFit,
          width: i.clientWidth,
          height: i.clientHeight,
          overflow: document.documentElement.scrollWidth > innerWidth,
          download: document.querySelector(".image-studio-result-card a").getBoundingClientRect().toJSON(),
          stage: document.querySelector(".image-studio-results").getBoundingClientRect().toJSON(),
        };
      });
      assert.equal(image.fit, "contain");
      assert.equal(image.overflow, false);
      assert.ok(image.width > 70 && image.height > 70);
      assert.ok(Math.abs(image.ratio - (scenario === "portrait" ? 9 / 16 : 16 / 9)) < 0.01);
      assert.ok(
        image.download.top >= image.stage.top && image.download.bottom <= image.stage.bottom + 1,
        `Single image download stays visible: ${viewport.width}/${scenario} ${JSON.stringify(image)}`,
      );
      if (viewport.height < 560) {
        assert.equal(
          await page(() => {
            const controls = [
              ...document.querySelectorAll(
                ".image-studio-settings button[role=combobox], .image-studio-advanced-toggle, .image-studio-generate",
              ),
            ];
            return controls.every(button => {
              button.focus();
              const rect = button.getBoundingClientRect();
              return rect.left >= 0 && rect.right <= innerWidth + 1 && rect.top >= 0 && rect.bottom <= innerHeight + 1;
            });
          }),
          true,
          "Short viewport controls remain reachable by keyboard",
        );
      }
    }
    console.log(`Workspace viewport passed: ${viewport.width}×${viewport.height}`);
  }
  assert.deepEqual(browser.exceptions, []);
  console.log(
    "Image Studio browser passed: 3 viewports × 4 languages, plus 6 single-image aspect cases; results/composer separation, history drawer focus/Escape, private media, draft retention, partial/UNKNOWN, upload, immutable retry, preview. Synthetic API/media only.",
  );
} finally {
  await browser?.close();
  await server.close();
}
