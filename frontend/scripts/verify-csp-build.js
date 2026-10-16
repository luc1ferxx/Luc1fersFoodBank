import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

// The backend sends `script-src 'self'` without nonces or hashes, so any inline
// <script> in index.html is blocked by the browser and the app renders blank.
/**
 * @param {string} html
 * @returns {string[]} the first 80 characters of every inline script body
 */
export const findInlineScripts = (html) => {
  /** @type {string[]} */
  const inlineScripts = [];
  const scriptPattern = /<script\b([^>]*)>([\s\S]*?)<\/script\s*>/gi;
  let match;

  while ((match = scriptPattern.exec(html)) !== null) {
    const attributes = match[1] ?? "";
    const body = match[2] ?? "";
    if (!/\bsrc\s*=/i.test(attributes) && body.trim() !== "") {
      inlineScripts.push(body.trim().slice(0, 80));
    }
  }

  return inlineScripts;
};

/** @param {string} buildDir */
export const verifyBuild = (buildDir) => {
  const indexPath = path.join(buildDir, "index.html");
  if (!fs.existsSync(indexPath)) {
    throw new Error(`Frontend build output was not found: ${indexPath}`);
  }

  const inlineScripts = findInlineScripts(fs.readFileSync(indexPath, "utf8"));
  if (inlineScripts.length > 0) {
    throw new Error(
      `${indexPath} contains ${inlineScripts.length} inline script(s) that the backend CSP blocks: ` +
        inlineScripts.map((snippet) => JSON.stringify(snippet)).join(", ") +
        ". Load scripts as external modules from index.html instead of inlining them.",
    );
  }
};

const scriptPath = fileURLToPath(import.meta.url);

if (process.argv[1] && path.resolve(process.argv[1]) === scriptPath) {
  verifyBuild(path.resolve(path.dirname(scriptPath), "..", "build"));
  console.log("Verified build/index.html has no inline scripts.");
}
