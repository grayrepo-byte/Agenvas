import assert from "node:assert/strict";
import { mkdir, writeFile } from "node:fs/promises";
import { createServer } from "vite";
import { fileURLToPath } from "node:url";
import { join } from "node:path";
import { createBrowserSession } from "./browser-session.mjs";

const root = fileURLToPath(new URL("../", import.meta.url));
const syntheticMedia = "/api/v1/projects/synthetic-image-studio/assets/synthetic-studio-";
const mediaGates = new Map();
const mediaReads = new Map();
const unavailableMedia = new Set();
const screenshots = process.env.AGENVAS_E2E_SCREENSHOT_DIR;
function holdMedia(path) {
  const gate = Promise.withResolvers();
  mediaGates.set(path, gate);
  return () => {
    mediaGates.delete(path);
    gate.resolve();
  };
}
const server = await createServer({
  root,
  configFile: join(root, "vite.config.ts"),
  plugins: [
    {
      name: "studio-reveal-media-gates",
      enforce: "pre",
      configureServer({ middlewares }) {
        middlewares.use((request, _response, next) => {
          const path = request.url?.split("?", 1)[0];
          if (!path?.startsWith(syntheticMedia)) return next();
          mediaReads.set(path, (mediaReads.get(path) ?? 0) + 1);
          if (unavailableMedia.has(path)) {
            _response.writeHead(503, { "Cache-Control": "no-store" }).end();
            return;
          }
          const gate = mediaGates.get(path);
          if (gate) void gate.promise.then(() => next());
          else next();
        });
      },
    },
  ],
  server: { host: "127.0.0.1", port: 0, open: false },
});
const viewports = [
  { width: 1487, height: 1058 },
  { width: 390, height: 844 },
  { width: 844, height: 390 },
];
let browser;
try {
  await server.listen();
  browser = await createBrowserSession({ timeoutMs: 60_000 });
  const { cdp, page, waitFor } = browser;
  const base = server.resolvedUrls.local[0];
  async function navigate(scenario = "grid", locale = "zh", extra = "") {
    const before = await page(() => performance.timeOrigin);
    await cdp("Page.navigate", {
      url: new URL(`/e2e/image-studio.html?scenario=${scenario}&locale=${locale}&${extra}`, base).href,
    });
    await waitFor(
      before => performance.timeOrigin !== before && Boolean(document.querySelector(".image-studio-controls")),
      before,
    );
  }
  async function click(selector) {
    const point = await page(async selector => {
      const element = [...document.querySelectorAll(selector)].find(e => e.getClientRects().length);
      if (!element) throw new Error(`Missing control: ${selector}`);
      const animations = [];
      for (let p = element; p; p = p.parentElement)
        animations.push(...p.getAnimations().filter(a => a.effect.getTiming().iterations !== Infinity));
      await Promise.all(animations.map(a => a.finished.catch(() => {})));
      element.scrollIntoView({ block: "nearest", inline: "nearest" });
      await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
      const r = element.getBoundingClientRect();
      const point = { x: r.x + r.width / 2, y: r.y + r.height / 2 };
      if (!element.contains(document.elementFromPoint(point.x, point.y)))
        throw new Error(`Obscured control: ${selector}`);
      return point;
    }, selector);
    for (const type of ["mousePressed", "mouseReleased"])
      await cdp("Input.dispatchMouseEvent", { type, ...point, button: "left", clickCount: 1 });
  }
  async function key(key, code, virtualKeyCode, text) {
    await cdp("Input.dispatchKeyEvent", {
      type: "keyDown",
      key,
      code,
      windowsVirtualKeyCode: virtualKeyCode,
      ...(text ? { text, unmodifiedText: text } : {}),
    });
    await cdp("Input.dispatchKeyEvent", { type: "keyUp", key, code, windowsVirtualKeyCode: virtualKeyCode });
  }
  async function fill(text) {
    await page(text => {
      const input = document.querySelector("textarea");
      Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, "value").set.call(input, text);
      input.dispatchEvent(new Event("input", { bubbles: true }));
    }, text);
  }
  async function imageReady(count) {
    await waitFor(
      count =>
        document.querySelectorAll(".image-studio-variant").length === count &&
        document.querySelectorAll(".image-studio-image-button").length === 1,
      count,
    );
    await waitFor(() =>
      [...document.querySelectorAll(".image-studio-image-button img,.image-studio-filmstrip img")].every(
        i => i.complete && i.naturalWidth > 0,
      ),
    );
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
  async function chooseBatch(id, keyboard = false) {
    await openHistory();
    const selector = `.image-studio-history-item[data-batch-id="${id}"]`;
    if (keyboard) {
      let focused = false;
      for (let step = 0; step < 12; step++) {
        if (await page(selector => document.activeElement.matches(selector), selector)) {
          focused = true;
          break;
        }
        await key("Tab", "Tab", 9);
      }
      assert.equal(focused, true, "History card can be reached with Tab");
      await key("Enter", "Enter", 13, "\r");
    } else {
      await click(selector);
    }
    await waitFor(
      id =>
        document.querySelector(`.image-studio-history-item[data-batch-id="${id}"]`)?.getAttribute("aria-pressed") ===
          "true" || !document.querySelector(".image-studio-history-drawer"),
      id,
    );
  }
  async function observeReveal() {
    await page(() => {
      window.__studioReveal = { animations: [] };
      document.addEventListener("animationstart", event => {
        if (event.animationName === "image-studio-number-reveal") {
          window.__studioReveal.animations.push(`${event.animationName}:${event.target.textContent.trim()}`);
          return;
        }
        if (event.animationName !== "image-studio-frame-exit") return;
        window.__studioReveal.animations.push(event.animationName);
        const frame = event.target;
        const button = document.querySelector(".image-studio-image-button");
        const photo = button.querySelector(".image-studio-photo");
        const original = photo.querySelector("img");
        const a = frame.getBoundingClientRect();
        const b = button.getBoundingClientRect();
        window.__studioReveal.frame = {
          originalLoaded: original.naturalWidth > 0,
          thumbnailShown: getComputedStyle(photo).backgroundImage.includes("/thumbnail"),
          sameCenter:
            Math.abs(a.x + a.width / 2 - b.x - b.width / 2) < 1 &&
            Math.abs(a.y + a.height / 2 - b.y - b.height / 2) < 1,
          fit: getComputedStyle(original).objectFit,
          uncovered: document.elementFromPoint(b.x + b.width / 2, b.y + b.height / 2)?.closest("button") === button,
        };
        function sample() {
          if (!frame.isConnected) return;
          const alpha = Number(getComputedStyle(frame).opacity);
          if (alpha > 0 && alpha < 1) window.__studioReveal.blended = true;
          else requestAnimationFrame(sample);
        }
        requestAnimationFrame(sample);
      });
    });
  }
  async function waitingMotion() {
    return page(() =>
      [".image-studio-stage", ".image-studio-generate"].flatMap(selector =>
        document
          .querySelector(selector)
          .getAnimations({ subtree: true })
          .filter(animation => animation.effect.getTiming().iterations === Infinity)
          .map(animation => animation.animationName),
      ),
    );
  }
  async function capture(name) {
    if (!screenshots) return;
    const { data } = await cdp("Page.captureScreenshot", { format: "png" });
    await writeFile(join(screenshots, name), Buffer.from(data, "base64"));
  }
  if (screenshots) await mkdir(screenshots, { recursive: true });
  for (const viewport of viewports) {
    await cdp("Emulation.setDeviceMetricsOverride", { ...viewport, deviceScaleFactor: 1, mobile: false });
    for (const locale of ["zh", "en", "ru", "ja"]) {
      await navigate("grid", locale);
      await imageReady(4);
      const layout = await page(() => {
        const input = document.querySelector("textarea");
        const css = getComputedStyle(input);
        const strip = document.querySelector(".image-studio-filmstrip");
        const bounds = strip.getBoundingClientRect();
        const actions = [...document.querySelector(".image-studio-result-actions").children];
        return {
          overflow: document.documentElement.scrollWidth > innerWidth,
          sidebar: !!document.querySelector(".app-sidebar"),
          rows: input.rows,
          contentHeight: input.clientHeight - parseFloat(css.paddingTop) - parseFloat(css.paddingBottom),
          line: parseFloat(css.lineHeight),
          fit: getComputedStyle(document.querySelector(".image-studio-image-button img")).objectFit,
          refs: [...document.querySelectorAll(".image-studio-variant img")].map(i => i.getAttribute("src")),
          header: document.querySelector(".image-studio-header").getBoundingClientRect().height,
          allVariantsVisible: [...strip.children].every(variant => {
            const { left, right } = variant.getBoundingClientRect();
            return left >= bounds.left && right <= bounds.right;
          }),
          touchActionsOnOwnRow: actions.every(action => {
            const { top, width, height } = action.getBoundingClientRect();
            return top >= bounds.bottom && width >= 44 && height >= 44;
          }),
        };
      });
      assert.equal(layout.overflow, false, `${viewport.width}/${locale}: no page overflow`);
      assert.equal(layout.sidebar, false);
      assert.equal(layout.rows, 3);
      assert.ok(layout.contentHeight >= layout.line * 3 - 1, "Three editable lines stay visible");
      assert.equal(layout.fit, "contain");
      assert.equal(layout.allVariantsVisible, true, `${viewport.width}/${locale}: four variants fit without scrolling`);
      if (viewport.width <= 640)
        assert.equal(layout.touchActionsOnOwnRow, true, "Narrow actions stay below the filmstrip with 44px targets");
      if (viewport.width === 390) {
        assert.equal(
          await page(() => {
            const model = document.querySelector(".image-studio-model").getBoundingClientRect();
            return [...document.querySelectorAll(".image-studio-parameter")].every(parameter => {
              const label = parameter.querySelector(".image-studio-setting-label");
              return (
                !!label?.innerText.trim() &&
                label.getBoundingClientRect().height > 1 &&
                parameter.getBoundingClientRect().top >= model.bottom
              );
            });
          }),
          true,
          "Mobile shows parameter labels beneath the model",
        );
        if (locale === "zh") await capture("studio-mobile-gallery.png");
      }
      if (viewport.width > 900 && locale === "zh") {
        const metadataVisible = await page(() => {
          const rows = [...document.querySelectorAll(".image-studio-history .image-studio-history-item")];
          return (
            rows.length > 0 &&
            rows.every(row => {
              const thumb = row.querySelector(".image-studio-history-thumb").getBoundingClientRect();
              const caption = row.querySelector(".image-studio-history-caption");
              return (
                caption.getBoundingClientRect().top >= thumb.bottom - 1 &&
                getComputedStyle(caption).opacity === "1" &&
                caption.querySelector("strong").innerText.trim() &&
                caption.querySelector("time").getBoundingClientRect().width > 0
              );
            })
          );
        });
        assert.equal(metadataVisible, true, "Desktop history shows each prompt and date below its thumbnail");
        await capture("studio-desktop-history.png");
      }
      assert.ok(
        layout.refs.every(
          url => url.startsWith("/api/v1/projects/synthetic-image-studio/assets/") && url.endsWith("/thumbnail"),
        ),
      );
      await fill("独立草稿\n第二行\n第三行");
      await click('.image-studio-variant[data-version-id="synthetic-version-1"]');
      await waitFor(
        () => document.querySelector(".image-studio-image-button").dataset.versionId === "synthetic-version-1",
      );
      await openHistory();
      assert.equal(
        await page(
          () =>
            document
              .querySelector(".image-studio-history-item[aria-pressed=true] img")
              .getAttribute("src")
              .replace("/thumbnail", "/content") ===
            document.querySelector(".image-studio-image-button img").getAttribute("src"),
        ),
        true,
        "Current batch thumbnail matches the active variant",
      );
      if (viewport.width <= 900) {
        assert.equal(await page(() => !!document.activeElement.closest(".image-studio-history-drawer")), true);
        await key("Escape", "Escape", 27);
        await waitFor(() => !document.querySelector(".image-studio-history-drawer"));
        await waitFor(() => document.activeElement.classList.contains("image-studio-history-toggle"));
      }
      await chooseBatch("synthetic-previous-batch", viewport.width <= 900);
      await imageReady(1);
      if (viewport.width <= 900) {
        await waitFor(() => !document.querySelector(".image-studio-history-drawer"));
        await waitFor(() => document.activeElement.classList.contains("image-studio-history-toggle"));
      }
      assert.equal(await page(() => document.querySelector("textarea").value), "光影摄影练习 · 公开授权示例 · Mock");
      const cue = await page(() => document.querySelector(".image-studio-history-prompt [role=status]")?.textContent);
      assert.match(cue, /#SYNTHETI/);
      if (locale === "zh") assert.match(cue, /其他设置未变/);
      await click(".image-studio-undo-history-prompt");
      assert.equal(await page(() => document.querySelector("textarea").value), "独立草稿\n第二行\n第三行");
      assert.equal(
        await page(() => document.querySelector(".image-studio-image-button").dataset.versionId),
        "synthetic-previous-version",
      );
      assert.equal(await page(() => !!document.querySelector(".image-studio-undo-history-prompt")), false);
      await chooseBatch("synthetic-saved-batch");
      await imageReady(4);
      await waitFor(
        () => document.querySelector(".image-studio-image-button").dataset.versionId === "synthetic-version-1",
      );
      const savedPrompt = await page(() => document.querySelector("textarea").value);
      assert.match(savedPrompt, /^清晨海边的小屋/);
      await chooseBatch("synthetic-pending-batch");
      await waitFor(() => !document.querySelector(".image-studio-image-button"));
      assert.equal(await page(() => document.querySelector("textarea").value), savedPrompt);
      assert.equal(
        (await page(() => window.__imageStudio.evidence())).writes.length,
        0,
        "UNKNOWN is never resubmitted",
      );
      await chooseBatch("synthetic-partial-batch");
      await imageReady(1);
      if (locale === "zh") {
        assert.match(
          await page(() => document.querySelector(".image-studio-task-feedback").innerText),
          /已保存 1 \/ 2 张图片，可预览和下载/,
        );
      }
      assert.equal(await page(() => document.body.innerText.includes("SYNTHETIC_PROVIDER_REJECTED")), false);
      await click(".image-studio-task-feedback summary");
      assert.equal(await page(() => document.body.innerText.includes("SYNTHETIC_PROVIDER_REJECTED")), true);
      assert.ok(await page(() => document.querySelector(".image-studio-result-actions a[download]")?.href));
      await chooseBatch("synthetic-saved-batch");
      await imageReady(4);
      await click(".image-studio-restore");
      await waitFor(() => document.querySelector("textarea").value.startsWith("清晨海边的小屋"));
      assert.equal((await page(() => window.__imageStudio.evidence())).writes.length, 0, "Reuse does not submit");
      await click(".image-studio-generate");
      await waitFor(
        () =>
          !!document.querySelector(".image-studio-controls .ui-notice--warning button") &&
          document.querySelector("fieldset").disabled,
      );
      await click(".image-studio-controls .ui-notice--warning button");
      await imageReady(4);
      await waitFor(
        () => window.__imageStudio.evidence().writes.length === 2 && !document.querySelector("fieldset").disabled,
      );
      const evidence = await page(() => window.__imageStudio.evidence());
      assert.deepEqual(evidence.writes[0], evidence.writes[1], "Acceptance retry keeps the exact command and key");
      assert.equal(evidence.writes[0].request.parameters.generationCount, 4);
      assert.equal(JSON.stringify(evidence.writes).includes("canvasItemId"), false);
      assert.deepEqual(evidence.unexpected, []);
      if (viewport.width === 1487 && locale === "zh") {
        await click(".image-studio-image-button");
        await waitFor(() => !!document.querySelector(".yarl__root button[aria-label='关闭预览']:focus"));
        const dialogImage = ".yarl__slide_current img";
        await waitFor(selector => document.querySelector(selector)?.naturalWidth > 0, dialogImage);
        const initial = await page(selector => document.querySelector(selector)?.getAttribute("src"), dialogImage);
        await key("ArrowRight", "ArrowRight", 39);
        await waitFor(
          ({ selector, initial }) => {
            const src = document.querySelector(selector)?.getAttribute("src");
            return !!src && src !== initial;
          },
          { selector: dialogImage, initial },
        );
        await waitFor(() => document.querySelector(".yarl__root").innerText.includes("02 / 04"));
        await click('.yarl__root button[aria-label="下一张图片"]');
        await waitFor(() => document.querySelector(".yarl__root").innerText.includes("03 / 04"));
        await key("Escape", "Escape", 27);
        await waitFor(() => !document.querySelector(".yarl__root"));
        await waitFor(() => document.querySelector(".image-studio-photo-index").innerText === "03 / 04");
        assert.equal((await page(() => window.__imageStudio.evidence())).writes.length, 2);
        const path = await page(() =>
          document.querySelector(".image-studio-result-actions a[download]").getAttribute("href"),
        );
        const response = await fetch(new URL(path, base));
        assert.equal(response.status, 200);
        assert.match(response.headers.get("Content-Type"), /^image\//);
        assert.ok((await response.arrayBuffer()).byteLength > 100);
      }
    }
    let desktopResultWidth;
    for (const scenario of ["portrait", "landscape"]) {
      await navigate(scenario);
      await imageReady(1);
      const image = await page(() => {
        const i = document.querySelector(".image-studio-image-button img");
        const rect = selector => document.querySelector(selector).getBoundingClientRect();
        const hero = rect(".image-studio-hero");
        const gallery = rect(".image-studio-gallery");
        const photo = i.getBoundingClientRect();
        const editor = rect(".image-studio-controls");
        return {
          width: i.clientWidth,
          height: i.clientHeight,
          ratio: i.naturalWidth / i.naturalHeight,
          fit: getComputedStyle(i).objectFit,
          overflow: document.documentElement.scrollWidth > innerWidth,
          resultWidth: gallery.width,
          centeredResult:
            Math.abs(hero.left + hero.right - editor.left - editor.right) < 2 &&
            Math.abs(hero.left - gallery.left) < 1 &&
            Math.abs(hero.right - gallery.right) < 1 &&
            Math.abs(photo.left + photo.width / 2 - gallery.left - gallery.width / 2) < 1 &&
            Math.abs(rect(".image-studio-filmstrip").left - gallery.left) < 1 &&
            Math.abs(rect(".image-studio-result-actions").right - gallery.right) < 1,
        };
      });
      assert.equal(image.fit, "contain");
      assert.equal(image.overflow, false);
      assert.ok(image.width > 40 && image.height > 70);
      assert.ok(Math.abs(image.ratio - (scenario === "portrait" ? 9 / 16 : 16 / 9)) < 0.01);
      if (viewport.width > 900) {
        assert.equal(image.centeredResult, true, `${scenario}: photo, filmstrip and editor share the page center`);
        desktopResultWidth ??= image.resultWidth;
        assert.equal(image.resultWidth, desktopResultWidth, "The desktop result column stays the same for both ratios");
        if (scenario === "landscape") {
          await capture("studio-desktop-alignment.png");
          await click(".image-studio-history-toggle");
          await waitFor(() => !document.querySelector(".image-studio-history"));
          const offset = await page(() => {
            const photo = document.querySelector(".image-studio-image-button").getBoundingClientRect();
            const editor = document.querySelector(".image-studio-controls").getBoundingClientRect();
            return (photo.left + photo.right - editor.left - editor.right) / 2;
          });
          assert.ok(Math.abs(offset) < 1, `Closing history must not shift the main axis (offset: ${offset}px)`);
        }
      }
      await page(() => document.querySelector(".image-studio-generate").scrollIntoView({ block: "nearest" }));
      assert.equal(
        await page(() => {
          const r = document.querySelector(".image-studio-generate").getBoundingClientRect();
          return r.top >= 0 && r.bottom <= innerHeight + 1;
        }),
        true,
        "Generate stays reachable by scrolling",
      );
      assert.equal((await page(() => window.__imageStudio.evidence())).writes.length, 0);
    }
    await navigate("feedback", "zh", "ok=1");
    await imageReady(4);
    await click(".image-studio-restore");
    await click(".image-studio-generate");
    await waitFor(
      () => document.querySelector(".image-studio-generation-frame h2")?.textContent === "正在提交生图请求",
    );
    assert.deepEqual(
      await page(() => [...document.querySelectorAll(".image-studio-slot-number")].map(e => e.innerText)),
      ["01", "02", "03", "04"],
    );
    assert.equal(await page(() => !!document.querySelector(".image-studio-image-button")), false);
    const frame = await page(() => {
      const frame = document.querySelector(".image-studio-generation-frame");
      const r = frame.getBoundingClientRect();
      const title = frame.querySelector("h2").getBoundingClientRect();
      return {
        width: r.width,
        height: r.height,
        titleFits: title.left >= r.left && title.right <= r.right && title.top >= r.top && title.bottom <= r.bottom,
        scan: getComputedStyle(frame, "::before").animationName,
        overflow: document.documentElement.scrollWidth > innerWidth,
      };
    });
    assert.ok(frame.width >= 150 && frame.height >= 70 && frame.titleFits && !frame.overflow, JSON.stringify(frame));
    assert.deepEqual(await waitingMotion(), ["image-studio-scan"], "Only the viewfinder moves during submission");
    await cdp("Emulation.setEmulatedMedia", { features: [{ name: "prefers-reduced-motion", value: "reduce" }] });
    await waitFor(
      () =>
        getComputedStyle(document.querySelector(".image-studio-generation-frame"), "::before").animationName === "none",
    );
    assert.deepEqual(await waitingMotion(), [], "Reduced motion also keeps the slots and submit button still");
    await page(() => window.__imageStudio.acknowledge());
    await waitFor(() => document.querySelector(".image-studio-generation-frame h2")?.textContent === "排队中");
    await page(() => window.__imageStudio.startGeneration());
    await waitFor(() => document.querySelector(".image-studio-generation-frame h2")?.textContent === "生成中");
    await observeReveal();
    await page(() => window.__imageStudio.finishNextOutput());
    await imageReady(1);
    await waitFor(() => document.querySelector(".image-studio-photo img")?.dataset.loaded === "true");
    assert.equal(await page(() => document.querySelector(".image-studio-photo-index").innerText), "04 / 04");
    assert.match(
      await page(() => document.querySelector(".image-studio-gallery-summary").textContent),
      /已出图 1 \/ 4/,
    );
    assert.equal(await page(() => document.querySelectorAll(".image-studio-slot").length), 3);
    assert.equal((await page(() => window.__imageStudio.evidence())).writes.length, 1);
    await cdp("Emulation.setEmulatedMedia", { features: [] });
    assert.deepEqual(await page(() => window.__studioReveal.animations), []);
    console.log(
      `Dark Studio viewport passed: ${viewport.width}×${viewport.height} × four languages, aspect ratios, submission motion and reduced motion`,
    );
  }
  for (const viewport of viewports.slice(0, 2)) {
    await cdp("Emulation.setDeviceMetricsOverride", { ...viewport, deviceScaleFactor: 1, mobile: false });
    await navigate("deletion", "en");
    await imageReady(4);
    await click(".image-studio-restore");
    await click("textarea");
    await cdp("Input.insertText", { text: "Keep this working draft." });
    const readDraft = () =>
      page(() => ({
        prompt: document.querySelector("textarea").value,
        parameters: [...document.querySelectorAll(".image-studio-parameter [role=combobox]")].map(e => e.textContent),
        references: [...document.querySelectorAll(".image-studio-reference img")].map(e => e.getAttribute("src")),
      }));
    const draft = await readDraft();
    assert.match(draft.prompt, /Keep this working draft/);
    assert.ok(draft.parameters.includes("4 images"));
    assert.equal(draft.references.length, 1);
    await openHistory();
    const savedId = "synthetic-saved-batch";
    const previousId = "synthetic-previous-batch";
    const row = id => `.image-studio-history-item[data-batch-id="${id}"]`;
    const trash = id => `${row(id)} + .image-studio-history-delete`;
    const savedTrash = trash(savedId);
    const restingDelete = await page(
      selector => ({
        opacity: Number(getComputedStyle(document.querySelector(selector)).opacity),
        finePointer: matchMedia("(hover: hover) and (pointer: fine)").matches,
      }),
      savedTrash,
    );
    if (viewport.width > 900 && restingDelete.finePointer) {
      assert.ok(restingDelete.opacity > 0 && restingDelete.opacity < 1, "Desktop deletion is visible but subdued");
    } else {
      assert.equal(restingDelete.opacity, 1, "Touch deletion is visible without hover");
    }
    const dialog = ".image-studio-delete-dialog";
    const cancel = `${dialog} button[data-variant="outline"]`;
    const confirm = `${dialog} button[data-variant="destructive"]`;
    const checkbox = `${dialog} [role="checkbox"]`;
    for (let step = 0; step < 24 && !(await page(s => document.activeElement.matches(s), savedTrash)); step++)
      await key("Tab", "Tab", 9);
    assert.equal(await page(s => document.activeElement.matches(s), savedTrash), true, "Delete is reachable by Tab");
    assert.equal(await page(s => getComputedStyle(document.querySelector(s)).opacity, savedTrash), "1");
    await key("Enter", "Enter", 13, "\r");
    await waitFor(selector => document.activeElement.matches(selector), cancel);
    assert.equal(await page(s => document.querySelector(s).getAttribute("aria-checked"), checkbox), "false");
    await click(checkbox);
    await key("Escape", "Escape", 27);
    await waitFor(selector => document.activeElement.matches(selector), savedTrash);
    assert.deepEqual((await page(() => window.__imageStudio.evidence())).deletions, []);
    assert.deepEqual(await readDraft(), draft);

    await key("Enter", "Enter", 13, "\r");
    await waitFor(s => document.querySelector(s)?.getAttribute("aria-checked") === "false", checkbox);
    await click(confirm);
    await waitFor(s => !document.querySelector(s), row(savedId));
    await waitFor(s => document.activeElement.matches(s), row(previousId));
    await waitFor(() =>
      document.querySelector(".image-studio-history-list [role=status]")?.textContent.includes("files were kept"),
    );
    assert.deepEqual(await readDraft(), draft);

    await click(trash(previousId));
    await click(checkbox);
    await click(confirm);
    await waitFor(() =>
      document.querySelector('[role="alert"]')?.textContent.includes("An image is still referenced by another batch."),
    );
    assert.equal(await page(s => document.querySelector(s).getAttribute("aria-checked"), checkbox), "true");
    assert.equal(await page(s => document.querySelector(s).getAttribute("aria-pressed"), row(previousId)), "true");
    assert.deepEqual(await readDraft(), draft);
    await click(confirm);
    await waitFor(() =>
      document.querySelector(".image-studio-history-list [role=status]")?.textContent.includes("has been queued"),
    );
    await waitFor(s => !document.querySelector(s), dialog);
    await waitFor(() => !document.querySelector('button[aria-label="Refresh studio"]').disabled);
    await click('button[aria-label="Refresh studio"]');
    await waitFor(() => !document.querySelector('button[aria-label="Refresh studio"]').disabled);
    assert.equal(await page(s => document.querySelectorAll(s).length, `${row(savedId)},${row(previousId)}`), 0);
    assert.deepEqual(await readDraft(), draft);
    const evidence = await page(() => window.__imageStudio.evidence());
    assert.deepEqual(evidence.deletions, [
      { id: savedId, deleteFiles: null, csrf: "synthetic" },
      { id: previousId, deleteFiles: "true", csrf: "synthetic" },
      { id: previousId, deleteFiles: "true", csrf: "synthetic" },
    ]);
    assert.deepEqual(evidence.writes, []);
    assert.deepEqual(evidence.unexpected, []);
  }
  console.log("Studio desktop and mobile: accessible confirmation, record-only deletion, conflict and queued purge");
  for (const [viewport, shape] of [
    [viewports[0], "landscape"],
    [viewports[1], "portrait"],
  ]) {
    await cdp("Emulation.setDeviceMetricsOverride", { ...viewport, deviceScaleFactor: 1, mobile: false });
    await navigate("feedback", "zh", `ok=1&revealShape=${shape}`);
    await imageReady(4);
    await observeReveal();
    await click(".image-studio-restore");
    await click(".image-studio-generate");
    await waitFor(
      () => document.querySelector(".image-studio-generation-frame h2")?.textContent === "正在提交生图请求",
    );
    await page(() => window.__imageStudio.acknowledge());
    await waitFor(() => document.querySelector(".image-studio-generation-frame h2")?.textContent === "排队中");
    assert.deepEqual(await waitingMotion(), ["image-studio-scan"], "Queued batches keep one waiting animation");
    const showThumbnail = holdMedia(`${syntheticMedia}${shape}-103/thumbnail`);
    const showOriginal = holdMedia(`${syntheticMedia}${shape}-103/content`);
    await page(() => window.__imageStudio.finishNextOutput());
    await waitFor(() => document.querySelector(".image-studio-generation-frame h2")?.textContent === "正在加载预览");
    assert.match(
      await page(() => document.querySelector(".image-studio-gallery-summary").textContent),
      /已出图 1 \/ 4/,
    );
    assert.deepEqual(
      await waitingMotion(),
      [],
      "The viewfinder stops scanning as soon as an archived image is loading",
    );
    assert.equal(await page(() => document.querySelector(".image-studio-image-button").matches("[inert]")), true);
    await capture(`studio-${shape}-waiting.png`);
    showThumbnail();
    await waitFor(() => window.__studioReveal.blended && !document.querySelector(".image-studio-generation-frame"));
    const preview = await page(() => window.__studioReveal);
    assert.deepEqual(preview.frame, {
      originalLoaded: false,
      thumbnailShown: true,
      sameCenter: true,
      fit: "contain",
      uncovered: true,
    });
    assert.deepEqual(preview.animations.sort(), ["image-studio-frame-exit", "image-studio-number-reveal:04"]);
    await capture(`studio-${shape}-thumbnail.png`);
    showOriginal();
    await imageReady(1);
    if (viewport.width > 900) {
      await click(".image-studio-image-button");
      await waitFor(() => document.querySelector(".media-preview-position")?.innerText === "04 / 04");
      assert.equal(await page(() => !!document.querySelector(".yarl__navigation_prev")), false);
    }
    await page(() => window.__imageStudio.finishNextOutput());
    await imageReady(2);
    if (viewport.width > 900) {
      await waitFor(() => !!document.querySelector('.yarl__navigation_prev[aria-disabled="false"]'));
      assert.equal(await page(() => document.querySelector(".media-preview-position").innerText), "04 / 04");
      await click(".yarl__navigation_prev");
      await waitFor(() => document.querySelector(".media-preview-position")?.innerText === "03 / 04");
      await click(".yarl__navigation_next");
      await waitFor(() => document.querySelector(".media-preview-position")?.innerText === "04 / 04");
      await key("Escape", "Escape", 27);
      await waitFor(() => !document.querySelector(".yarl__root"));
    }
    await chooseBatch("synthetic-previous-batch");
    await imageReady(1);
    await chooseBatch("5ba708e3-0000-4000-8000-000000000001");
    await imageReady(2);
    assert.equal(await page(() => document.querySelector(".image-studio-photo-index").innerText), "04 / 04");
    assert.equal(await page(() => window.__studioReveal.animations.length), 2);
    assert.equal((await page(() => window.__imageStudio.evidence())).writes.length, 1);
    console.log(`Studio ${shape}: delayed media, layered reveal, original upgrade and history without replay passed`);
  }
  await cdp("Emulation.setDeviceMetricsOverride", { width: 1487, height: 1058, deviceScaleFactor: 1, mobile: false });
  await navigate("portrait", "zh", "ok=1");
  await imageReady(1);
  await observeReveal();
  const warm = ["content", "thumbnail"].map(kind => `${syntheticMedia}portrait-0/${kind}`);
  const readsBefore = warm.map(path => mediaReads.get(path));
  await click(".image-studio-restore");
  await click(".image-studio-generate");
  await waitFor(() => window.__studioReveal.animations.length === 2);
  await imageReady(1);
  assert.deepEqual(
    warm.map(path => mediaReads.get(path)),
    readsBefore,
  );
  assert.equal((await page(() => window.__imageStudio.evidence())).writes.length, 1);
  console.log("Studio cached media: receipt with result reveals once without another media request");
  await cdp("Network.enable");
  await cdp("Network.setCacheDisabled", { cacheDisabled: true });
  const savedPreviewPaths = ["content", "thumbnail"].map(kind => `${syntheticMedia}portrait-0/${kind}`);
  for (const path of savedPreviewPaths) unavailableMedia.add(path);
  await navigate("portrait", "zh", "ok=1");
  await observeReveal();
  await waitFor(() => !!document.querySelector(".image-studio-reload-preview"));
  assert.match(await page(() => document.querySelector(".image-studio-gallery-summary").innerText), /已出图 1 \/ 1/);
  for (const path of savedPreviewPaths) unavailableMedia.delete(path);
  await click(".image-studio-reload-preview");
  await imageReady(1);
  assert.deepEqual(await page(() => window.__studioReveal.animations), []);
  assert.equal((await page(() => window.__imageStudio.evidence())).writes.length, 0);

  const failedFirstPaths = ["content", "thumbnail"].map(kind => `${syntheticMedia}landscape-103/${kind}`);
  for (const path of failedFirstPaths) unavailableMedia.add(path);
  await navigate("feedback", "zh", "ok=1&revealShape=landscape");
  await imageReady(4);
  await observeReveal();
  await click(".image-studio-restore");
  await click(".image-studio-generate");
  await waitFor(() => document.querySelector(".image-studio-generation-frame h2")?.textContent === "正在提交生图请求");
  await page(() => window.__imageStudio.acknowledge());
  await waitFor(() => document.querySelector(".image-studio-generation-frame h2")?.textContent === "排队中");
  await page(() => window.__imageStudio.finishNextOutput());
  await waitFor(() => !!document.querySelector(".image-studio-reload-preview"));
  assert.deepEqual(await page(() => window.__studioReveal.animations), []);
  for (const path of failedFirstPaths) unavailableMedia.delete(path);
  await click(".image-studio-reload-preview");
  await waitFor(() => window.__studioReveal.animations.length === 2);
  await imageReady(1);
  assert.equal(await page(() => document.querySelector(".image-studio-photo-index").innerText), "04 / 04");
  assert.equal((await page(() => window.__imageStudio.evidence())).writes.length, 1);
  await cdp("Network.setCacheDisabled", { cacheDisabled: false });
  console.log("Studio recovery: asset-only reload for historical and first previews without a new command");
  await cdp("Emulation.setDeviceMetricsOverride", { width: 1487, height: 1058, deviceScaleFactor: 1, mobile: false });
  await navigate("polling");
  await imageReady(3);
  await chooseBatch("synthetic-saved-batch");
  await click("textarea");
  await cdp("Input.insertText", { text: "继续优化光线" });
  const editedPrompt = await page(() => document.querySelector("textarea").value);
  assert.match(editedPrompt, /继续优化光线/);
  await click('.image-studio-variant[data-version-id="synthetic-version-1"]');
  await page(() => window.__imageStudio.finishAndLead());
  await imageReady(4);
  await waitFor(() => !!document.querySelector('.image-studio-history-item[data-batch-id="synthetic-leading-batch"]'));
  assert.equal(
    await page(() => document.querySelector(".image-studio-image-button").dataset.versionId),
    "synthetic-version-1",
    "Timed polling and a newer batch preserve the chosen version",
  );
  assert.equal(
    await page(() => document.querySelector(".image-studio-history-item[aria-pressed=true]").dataset.batchId),
    "synthetic-saved-batch",
  );
  assert.equal(await page(() => document.querySelector("textarea").value), editedPrompt);
  assert.equal((await page(() => window.__imageStudio.evidence())).writes.length, 0);
  await cdp("Emulation.setDeviceMetricsOverride", { width: 390, height: 844, deviceScaleFactor: 1, mobile: false });
  await navigate("grid", "zh", "ok=1");
  await imageReady(4);
  await click('.image-studio-variant[data-version-id="synthetic-version-2"]');
  await waitFor(() => document.querySelector(".image-studio-photo-index").innerText === "03 / 04");
  await click(".image-studio-image-button");
  await waitFor(() => document.querySelector(".yarl__root")?.innerText.includes("03 / 04"));
  await click('.yarl__root button[aria-label="上一张图片"]');
  await waitFor(() => document.querySelector(".yarl__root")?.innerText.includes("02 / 04"));
  await waitFor(
    () =>
      document.querySelector('.yarl__slide_current img[src*="synthetic-studio-square-1/content"]')?.naturalWidth > 0,
  );
  await capture("studio-mobile-batch-preview.png");
  await key("Escape", "Escape", 27);
  await waitFor(() => !document.querySelector(".yarl__root"));
  await waitFor(() => document.querySelector(".image-studio-photo-index").innerText === "02 / 04");

  await chooseBatch("synthetic-saved-batch");
  await waitFor(() => document.querySelector(".image-studio-history-prompt [role=status]")?.textContent.includes("#"));
  await chooseBatch("synthetic-pending-batch");
  await waitFor(() => !document.querySelector(".image-studio-image-button"));
  await click(".image-studio-restore-history-prompt");
  await waitFor(() => !document.querySelector(".image-studio-undo-history-prompt"));
  assert.match(await page(() => document.querySelector("textarea").value), /^清晨海边的小屋/);
  assert.equal(
    await page(() => document.querySelector('.image-studio-parameter [role="combobox"][aria-label="数量"]').innerText),
    "4 张",
  );
  assert.equal((await page(() => window.__imageStudio.evidence())).writes.length, 0);
  console.log("Studio mobile: readable parameters, original-source full restore, and enlarged variant navigation");
  await navigate("grid", "zh", "ok=1");
  await imageReady(4);
  await click("textarea");
  await page(() => {
    const t = document.querySelector("textarea");
    const set = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, "value").set;
    t.dispatchEvent(new CompositionEvent("compositionstart", { bubbles: true, data: "" }));
    set.call(t, "中文候选");
    t.dispatchEvent(
      new InputEvent("input", {
        bubbles: true,
        data: "中文候选",
        inputType: "insertCompositionText",
        isComposing: true,
      }),
    );
    t.dispatchEvent(
      new KeyboardEvent("keydown", { bubbles: true, key: "Enter", code: "Enter", keyCode: 229, isComposing: true }),
    );
    t.dispatchEvent(new CompositionEvent("compositionend", { bubbles: true, data: "中文候选" }));
    t.setSelectionRange(t.value.length, t.value.length);
  });
  assert.equal((await page(() => window.__imageStudio.evidence())).writes.length, 0);
  await key("Enter", "Enter", 13, "\r");
  await cdp("Input.insertText", { text: "正常换行。" });
  assert.equal(await page(() => document.querySelector("textarea").value), "中文候选\n正常换行。");
  await cdp("Input.insertText", { text: "真实摄影质感，保留细节与构图。".repeat(320) });
  assert.equal((await page(() => window.__imageStudio.evidence())).writes.length, 0);
  await page(() => {
    Object.defineProperty(window.visualViewport, "height", { configurable: true, value: 390 });
    window.visualViewport.dispatchEvent(new Event("resize"));
  });
  await waitFor(() => document.querySelector(".image-studio").getBoundingClientRect().height <= 391);
  await click(".image-studio-generate");
  await waitFor(
    () => window.__imageStudio.evidence().writes.length === 1 && !document.querySelector("fieldset").disabled,
  );
  assert.equal(
    await page(() => {
      const rect = document.querySelector(".image-studio-generate").getBoundingClientRect();
      return (
        rect.top >= window.visualViewport.offsetTop &&
        rect.bottom <= window.visualViewport.offsetTop + window.visualViewport.height + 1
      );
    }),
    true,
    "Generate is visible inside the simulated keyboard viewport after scrolling",
  );
  assert.match((await page(() => window.__imageStudio.evidence())).writes[0].request.prompt, /中文候选\n正常换行。/);
  assert.deepEqual(browser.exceptions, []);
  console.log(
    "Dark Studio browser passed: 12 locale/viewport cases, 6 single-image aspect cases, timed polling retention, and simulated Chinese composition/Enter + long prompt + reduced VisualViewport + explicit single submit. Keyboard/composition are simulated, not a real phone IME.",
  );
} finally {
  for (const gate of mediaGates.values()) gate.resolve();
  await browser?.close();
  await server.close();
}
