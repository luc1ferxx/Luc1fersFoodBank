const fs = require("fs");
const path = require("path");

// The backend sends `script-src 'self'` without nonces or hashes, so any inline
// <script> in index.html is blocked by the browser and the app renders blank.
const findInlineScripts = (html) => {
  const inlineScripts = [];
  const scriptPattern = /<script\b([^>]*)>([\s\S]*?)<\/script\s*>/gi;
  let match;

  while ((match = scriptPattern.exec(html)) !== null) {
    const attributes = match[1];
    const body = match[2];
    if (!/\bsrc\s*=/i.test(attributes) && body.trim() !== "") {
      inlineScripts.push(body.trim().slice(0, 80));
    }
  }

  return inlineScripts;
};

const verifyBuild = (buildDir) => {
  const indexPath = path.join(buildDir, "index.html");
  if (!fs.existsSync(indexPath)) {
    throw new Error(`Frontend build output was not found: ${indexPath}`);
  }

  const inlineScripts = findInlineScripts(fs.readFileSync(indexPath, "utf8"));
  if (inlineScripts.length > 0) {
    throw new Error(
      `${indexPath} contains ${inlineScripts.length} inline script(s) that the backend CSP blocks: ` +
        inlineScripts.map((snippet) => JSON.stringify(snippet)).join(", ") +
        ". Keep INLINE_RUNTIME_CHUNK=false in .env.production."
    );
  }
};

if (require.main === module) {
  verifyBuild(path.resolve(__dirname, "..", "build"));
  console.log("Verified build/index.html has no inline scripts.");
}

module.exports = { findInlineScripts, verifyBuild };
