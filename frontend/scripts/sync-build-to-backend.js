const fs = require("fs");
const path = require("path");

const sourceDir = path.resolve(__dirname, "..", "build");
const targetDir = path.resolve(__dirname, "..", "..", "backend", "src", "main", "resources", "public");

if (!fs.existsSync(sourceDir)) {
  throw new Error(`Frontend build output was not found: ${sourceDir}`);
}

fs.rmSync(targetDir, { recursive: true, force: true });
fs.mkdirSync(targetDir, { recursive: true });
fs.cpSync(sourceDir, targetDir, { recursive: true });

console.log(`Copied ${sourceDir} to ${targetDir}`);
