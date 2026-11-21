import {
  copyFileSync,
  existsSync,
  lstatSync,
  mkdirSync,
  readdirSync,
  realpathSync,
  rmSync,
  statSync,
} from "node:fs";
import { dirname, isAbsolute, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

// Anchor paths to this script so running it from another directory is safe.
const REPO_DIR = realpathSync(resolve(dirname(fileURLToPath(import.meta.url)), ".."));
const SOURCE_DIR = resolve(REPO_DIR, "web-ui/build/client");
const TARGET_DIR = resolve(REPO_DIR, "web/src/main/resources/static");

function samePath(left: string, right: string): boolean {
  return process.platform === "win32"
    ? left.toLowerCase() === right.toLowerCase()
    : left === right;
}

function assertInsideRepo(path: string) {
  const offset = relative(REPO_DIR, path);
  if (!offset || offset.startsWith("..") || isAbsolute(offset)) {
    throw new Error(`Refusing path outside the repository: ${path}`);
  }
  // Reject junctions and symlinks, including an existing parent of a new target.
  let current = REPO_DIR;
  for (const segment of offset.split(/[\\/]/)) {
    current = join(current, segment);
    if (!existsSync(current)) continue;
    if (lstatSync(current).isSymbolicLink() || !samePath(realpathSync(current), current)) {
      throw new Error(`Refusing redirected path: ${current}`);
    }
  }
}

function assertPlainDirectoryTree(path: string) {
  assertInsideRepo(path);
  if (!statSync(path).isDirectory()) throw new Error(`Not a directory: ${path}`);
  for (const entry of readdirSync(path, { withFileTypes: true })) {
    const child = join(path, entry.name);
    if (entry.isSymbolicLink()) throw new Error(`Refusing linked build entry: ${child}`);
    if (entry.isDirectory()) assertPlainDirectoryTree(child);
    else if (!entry.isFile()) throw new Error(`Unsupported build entry: ${child}`);
  }
}

function copyDirectory(src: string, dest: string) {
  mkdirSync(dest, { recursive: true });
  for (const entry of readdirSync(src, { withFileTypes: true })) {
    const srcPath = join(src, entry.name);
    const destPath = join(dest, entry.name);
    if (entry.isSymbolicLink()) throw new Error(`Refusing linked build entry: ${srcPath}`);
    if (entry.isDirectory()) copyDirectory(srcPath, destPath);
    else if (entry.isFile()) copyFileSync(srcPath, destPath);
    else throw new Error(`Unsupported build entry: ${srcPath}`);
  }
}

try {
  if (!existsSync(join(REPO_DIR, "settings.gradle.kts"))) {
    throw new Error("Repository marker missing; refusing to replace static resources");
  }
  if (!samePath(TARGET_DIR, resolve(REPO_DIR, "web", "src", "main", "resources", "static"))) {
    throw new Error("Unexpected static resource target");
  }
  assertPlainDirectoryTree(SOURCE_DIR);
  assertInsideRepo(TARGET_DIR);
  if (existsSync(TARGET_DIR)) assertPlainDirectoryTree(TARGET_DIR);

  // All source, target, and ancestor checks complete before recursive deletion.
  console.log(`Replacing verified static resources: ${TARGET_DIR}`);
  rmSync(TARGET_DIR, { recursive: true, force: true });
  copyDirectory(SOURCE_DIR, TARGET_DIR);
  console.log("Build output copied successfully.");
} catch (error) {
  console.error("Copy failed:", error);
  process.exit(1);
}
