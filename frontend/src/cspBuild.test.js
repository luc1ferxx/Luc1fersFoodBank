// @vitest-environment node
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { build } from "vite";
import { afterAll, describe, expect, it } from "vitest";
import { findInlineScripts, verifyBuild } from "../scripts/verify-csp-build.js";

const frontendRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

describe("CSP build check", () => {
  it("flags an inline script that the backend CSP blocks", () => {
    const html =
      '<html><head><script type="module" src="/static/index.js"></script></head>' +
      '<body><div id="root"></div><script>!function(e){function r(r){}}([])</script></body></html>';

    expect(findInlineScripts(html)).toEqual(["!function(e){function r(r){}}([])"]);
  });

  it("accepts a build that only loads external scripts", () => {
    const html =
      '<html><head><script type="module" crossorigin src="/static/index-abc.js"></script></head>' +
      '<body><div id="root"></div><script src="/static/vendor.js"></script></body></html>';

    expect(findInlineScripts(html)).toEqual([]);
  });

  describe("the Vite production build", () => {
    const outDir = fs.mkdtempSync(path.join(os.tmpdir(), "foodbank-csp-build-"));

    afterAll(() => {
      fs.rmSync(outDir, { recursive: true, force: true });
    });

    it("emits index.html without inline scripts and assets under /static", async () => {
      await build({
        root: frontendRoot,
        configFile: path.join(frontendRoot, "vite.config.js"),
        logLevel: "silent",
        build: { outDir, emptyOutDir: true },
      });

      expect(() => verifyBuild(outDir)).not.toThrow();
      const html = fs.readFileSync(path.join(outDir, "index.html"), "utf8");
      expect(html).toMatch(
        /<script type="module" crossorigin src="\/static\/index-[\w-]+\.js"><\/script>/,
      );
      expect(html).toMatch(
        /<link rel="stylesheet" crossorigin href="\/static\/index-[\w-]+\.css">/,
      );
    }, 60000);
  });
});
