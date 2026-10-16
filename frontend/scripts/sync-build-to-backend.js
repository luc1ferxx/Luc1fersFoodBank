import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const sourceDir = path.resolve(scriptDir, "..", "build");
const targetDir = path.resolve(
  scriptDir,
  "..",
  "..",
  "backend",
  "src",
  "main",
  "resources",
  "public",
);

if (!fs.existsSync(sourceDir)) {
  throw new Error(`Frontend build output was not found: ${sourceDir}`);
}

fs.rmSync(targetDir, { recursive: true, force: true });
fs.mkdirSync(targetDir, { recursive: true });
fs.cpSync(sourceDir, targetDir, { recursive: true });

console.log(`Copied ${sourceDir} to ${targetDir}`);
