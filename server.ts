import express from 'express';
import fs from 'fs';
import path from 'path';
import { execFile } from 'child_process';
import { promisify } from 'util';
import { fileURLToPath } from 'url';

const execFileAsync = promisify(execFile);
const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const WORKSPACE_ROOT = __dirname;
const ALLOWED_ROOTS = ['andrapk', 'uploads'] as const;
type AllowedRoot = (typeof ALLOWED_ROOTS)[number];

const IMAGE_EXTENSIONS = new Set(['.png', '.jpg', '.jpeg', '.webp', '.gif', '.svg']);
const TEXT_EXTENSIONS = new Set([
  '.kt',
  '.kts',
  '.java',
  '.xml',
  '.json',
  '.md',
  '.txt',
  '.py',
  '.sh',
  '.properties',
  '.pro',
  '.toml',
  '.gradle',
  '.yml',
  '.yaml',
  '.gitignore',
  '.env',
  '.example',
  '.ts',
  '.tsx',
  '.js',
  '.jsx',
  '.css',
  '.html',
  '.csv',
  '.sql',
  '.cfg',
  '.ini',
  '.conf',
]);

interface TreeNode {
  name: string;
  path: string;
  type: 'file' | 'directory';
  size?: number;
  ext?: string;
  isImage?: boolean;
  modifiedAt?: string;
  children?: TreeNode[];
}

function ensureDir(dirPath: string): void {
  if (!fs.existsSync(dirPath)) {
    fs.mkdirSync(dirPath, { recursive: true });
  }
}

function resolveSafeWorkspacePath(relativePath: string): {
  absPath: string;
  normRel: string;
  rootName: AllowedRoot;
} | null {
  const cleaned = relativePath.replace(/\\/g, '/').replace(/^\/+/, '');
  if (!cleaned || cleaned.includes('\0')) return null;

  const segments = cleaned.split('/').filter(Boolean);
  if (segments.length === 0) return null;
  if (segments.some((s) => s === '..' || s === '.')) return null;

  const rootName = segments[0] as AllowedRoot;
  if (!ALLOWED_ROOTS.includes(rootName)) return null;

  const rootAbs = path.resolve(WORKSPACE_ROOT, rootName);
  const absPath = path.resolve(WORKSPACE_ROOT, ...segments);
  if (absPath !== rootAbs && !absPath.startsWith(rootAbs + path.sep)) {
    return null;
  }

  return {
    absPath,
    normRel: segments.join('/'),
    rootName,
  };
}

function buildTree(absDir: string, relDir: string): TreeNode[] {
  if (!fs.existsSync(absDir)) return [];
  const entries = fs.readdirSync(absDir, { withFileTypes: true });
  const nodes: TreeNode[] = [];

  for (const entry of entries) {
    if (
      entry.name === '.git' ||
      entry.name === 'node_modules' ||
      entry.name === '.DS_Store' ||
      entry.name === '.gradle' ||
      entry.name === 'intermediates' ||
      entry.name === 'generated' ||
      entry.name === 'tmp' ||
      entry.name === 'kotlin' ||
      entry.name === 'kspCaches' ||
      entry.name === 'reports'
    ) {
      continue;
    }
    const entryAbs = path.join(absDir, entry.name);
    const entryRel = `${relDir}/${entry.name}`;

    if (entry.isDirectory()) {
      nodes.push({
        name: entry.name,
        path: entryRel,
        type: 'directory',
        children: buildTree(entryAbs, entryRel),
      });
    } else if (entry.isFile()) {
      const stat = fs.statSync(entryAbs);
      const ext = path.extname(entry.name).toLowerCase();
      nodes.push({
        name: entry.name,
        path: entryRel,
        type: 'file',
        size: stat.size,
        ext,
        isImage: IMAGE_EXTENSIONS.has(ext),
        modifiedAt: stat.mtime.toISOString(),
      });
    }
  }

  nodes.sort((a, b) => {
    if (a.type !== b.type) return a.type === 'directory' ? -1 : 1;
    return a.name.localeCompare(b.name);
  });

  return nodes;
}

function collectStats(nodes: TreeNode[]): {
  fileCount: number;
  dirCount: number;
  totalBytes: number;
  kotlinCount: number;
  images: Array<{ name: string; path: string; size: number }>;
} {
  let fileCount = 0;
  let dirCount = 0;
  let totalBytes = 0;
  let kotlinCount = 0;
  const images: Array<{ name: string; path: string; size: number }> = [];

  const walk = (list: TreeNode[]) => {
    for (const n of list) {
      if (n.type === 'directory') {
        dirCount += 1;
        if (n.children) walk(n.children);
      } else {
        fileCount += 1;
        totalBytes += n.size ?? 0;
        if (n.ext === '.kt' || n.ext === '.kts') kotlinCount += 1;
        if (n.isImage) {
          images.push({ name: n.name, path: n.path, size: n.size ?? 0 });
        }
      }
    }
  };

  walk(nodes);
  return { fileCount, dirCount, totalBytes, kotlinCount, images };
}

function isLikelyText(fileName: string, buffer: Buffer): boolean {
  const lower = fileName.toLowerCase();
  const ext = path.extname(lower);
  if (TEXT_EXTENSIONS.has(ext) || lower === 'dockerfile' || lower === 'makefile' || lower.startsWith('.env')) {
    return true;
  }
  const sampleLen = Math.min(buffer.length, 4096);
  for (let i = 0; i < sampleLen; i++) {
    if (buffer[i] === 0) return false;
  }
  return true;
}

function parseGitHubUrl(input: string): { cloneUrl: string; branch?: string } | null {
  const trimmed = input.trim();
  const match = trimmed.match(
    /^https:\/\/github\.com\/([A-Za-z0-9_.-]+)\/([A-Za-z0-9_.-]+?)(?:\.git)?(?:\/tree\/(.+))?\/?$/
  );
  if (!match) return null;
  const [, owner, repo, branch] = match;
  return {
    cloneUrl: `https://github.com/${owner}/${repo}.git`,
    branch: branch ? branch.replace(/\/+$/, '') : undefined,
  };
}

async function startServer() {
  const app = express();
  const PORT = 3000;

  ensureDir(path.join(WORKSPACE_ROOT, 'uploads'));

  app.use(express.json({ limit: '5mb' }));

  app.get('/api/workspace/overview', (_req, res) => {
    const andrapkTree = buildTree(path.join(WORKSPACE_ROOT, 'andrapk'), 'andrapk');
    const uploadsTree = buildTree(path.join(WORKSPACE_ROOT, 'uploads'), 'uploads');
    const andrapkStats = collectStats(andrapkTree);
    const uploadsStats = collectStats(uploadsTree);

    res.json({
      repoUrl: 'https://github.com/alvianbagus321-stack/Andrapk/tree/arena/3b32f909-andrapk',
      branch: 'arena/3b32f909-andrapk',
      andrapkExists: fs.existsSync(path.join(WORKSPACE_ROOT, 'andrapk')),
      andrapk: andrapkStats,
      uploads: uploadsStats,
    });
  });

  app.get('/api/workspace/tree', (req, res) => {
    const rootParam = String(req.query.root || 'andrapk') as AllowedRoot;
    if (!ALLOWED_ROOTS.includes(rootParam)) {
      res.status(400).json({ error: 'Root tidak valid. Gunakan andrapk atau uploads.' });
      return;
    }
    const targetDir = path.join(WORKSPACE_ROOT, rootParam);
    ensureDir(targetDir);
    const tree = buildTree(targetDir, rootParam);
    res.json({ root: rootParam, tree });
  });

  app.get('/api/workspace/file', (req, res) => {
    const relPath = String(req.query.path || '');
    const resolved = resolveSafeWorkspacePath(relPath);
    if (!resolved || !fs.existsSync(resolved.absPath)) {
      res.status(404).json({ error: 'File tidak ditemukan.' });
      return;
    }

    const stat = fs.statSync(resolved.absPath);
    if (!stat.isFile()) {
      res.status(400).json({ error: 'Path adalah direktori.' });
      return;
    }

    const ext = path.extname(resolved.absPath).toLowerCase();
    const isImage = IMAGE_EXTENSIONS.has(ext);
    if (isImage) {
      res.json({
        path: resolved.normRel,
        name: path.basename(resolved.absPath),
        size: stat.size,
        ext,
        kind: 'image',
        rawUrl: `/api/workspace/raw?path=${encodeURIComponent(resolved.normRel)}`,
      });
      return;
    }

    const MAX_TEXT_BYTES = 1024 * 1024 * 2; // 2 MB preview cap
    if (stat.size > MAX_TEXT_BYTES) {
      res.json({
        path: resolved.normRel,
        name: path.basename(resolved.absPath),
        size: stat.size,
        ext,
        kind: 'binary',
        rawUrl: `/api/workspace/raw?path=${encodeURIComponent(resolved.normRel)}`,
      });
      return;
    }

    const buffer = fs.readFileSync(resolved.absPath);
    if (!isLikelyText(path.basename(resolved.absPath), buffer)) {
      res.json({
        path: resolved.normRel,
        name: path.basename(resolved.absPath),
        size: stat.size,
        ext,
        kind: 'binary',
        rawUrl: `/api/workspace/raw?path=${encodeURIComponent(resolved.normRel)}`,
      });
      return;
    }

    res.json({
      path: resolved.normRel,
      name: path.basename(resolved.absPath),
      size: stat.size,
      ext,
      kind: 'text',
      content: buffer.toString('utf-8'),
      rawUrl: `/api/workspace/raw?path=${encodeURIComponent(resolved.normRel)}`,
    });
  });

  app.get('/api/workspace/raw', (req, res) => {
    const relPath = String(req.query.path || '');
    const download = req.query.download === '1';
    const resolved = resolveSafeWorkspacePath(relPath);
    if (!resolved || !fs.existsSync(resolved.absPath)) {
      res.status(404).json({ error: 'File tidak ditemukan.' });
      return;
    }
    if (download) {
      res.download(resolved.absPath, path.basename(resolved.absPath));
      return;
    }
    res.sendFile(resolved.absPath);
  });

  app.get('/api/workspace/export-zip', async (_req, res) => {
    const andrapkDir = path.join(WORKSPACE_ROOT, 'andrapk');
    if (!fs.existsSync(andrapkDir)) {
      res.status(404).json({ error: 'Folder andrapk tidak ditemukan.' });
      return;
    }
    const tmpZip = `/tmp/andrapk-export-${Date.now()}.zip`;
    const pyScript = `
import os, sys, zipfile
src, dst = sys.argv[1], sys.argv[2]
skip_dirs = {'.git', '.gradle', 'build', 'node_modules'}
with zipfile.ZipFile(dst, 'w', zipfile.ZIP_DEFLATED) as zf:
    for root, dirs, files in os.walk(src):
        dirs[:] = [d for d in dirs if d not in skip_dirs]
        for f in files:
            if f == '.DS_Store':
                continue
            full = os.path.join(root, f)
            rel = os.path.relpath(full, src)
            zf.write(full, rel)
`;
    try {
      await execFileAsync('python3', ['-c', pyScript, andrapkDir, tmpZip], {
        timeout: 30000,
      });
      res.download(tmpZip, 'Andrapk-v2.61.zip', () => {
        fs.rmSync(tmpZip, { force: true });
      });
    } catch (err) {
      fs.rmSync(tmpZip, { force: true });
      const msg = err instanceof Error ? err.message : 'Gagal membuat file ZIP.';
      res.status(500).json({ error: msg });
    }
  });

  app.post(
    '/api/workspace/upload',
    express.raw({ limit: '50mb', type: () => true }),
    (req, res) => {
      const targetRoot = String(req.query.targetRoot || 'uploads') as AllowedRoot;
      const relativePath = String(req.query.relativePath || '');

      if (!ALLOWED_ROOTS.includes(targetRoot)) {
        res.status(400).json({ error: 'Target folder tidak valid.' });
        return;
      }

      const combined = `${targetRoot}/${relativePath.replace(/^\/+/, '')}`;
      const resolved = resolveSafeWorkspacePath(combined);
      if (!resolved) {
        res.status(400).json({ error: 'Path file tidak valid atau tidak aman.' });
        return;
      }

      const bodyBuffer = Buffer.isBuffer(req.body) ? req.body : Buffer.from([]);
      ensureDir(path.dirname(resolved.absPath));
      fs.writeFileSync(resolved.absPath, bodyBuffer);

      res.json({
        ok: true,
        path: `/${resolved.normRel}`,
        relPath: resolved.normRel,
        size: bodyBuffer.length,
      });
    }
  );

  app.delete('/api/workspace/file', (req, res) => {
    const relPath = String(req.query.path || '');
    const resolved = resolveSafeWorkspacePath(relPath);
    if (!resolved || !fs.existsSync(resolved.absPath)) {
      res.status(404).json({ error: 'File tidak ditemukan.' });
      return;
    }
    if (resolved.normRel === 'andrapk' || resolved.normRel === 'uploads') {
      res.status(400).json({ error: 'Root directory tidak boleh dihapus.' });
      return;
    }
    fs.rmSync(resolved.absPath, { recursive: true, force: true });
    res.json({ ok: true, deleted: resolved.normRel });
  });

  app.post('/api/workspace/clone', async (req, res) => {
    const { url, targetFolder } = req.body as { url?: string; targetFolder?: string };
    if (!url || typeof url !== 'string') {
      res.status(400).json({ error: 'URL GitHub wajib diisi.' });
      return;
    }

    const parsed = parseGitHubUrl(url);
    if (!parsed) {
      res.status(400).json({
        error: 'Format URL harus https://github.com/owner/repo atau .../tree/branch',
      });
      return;
    }

    const safeTarget =
      targetFolder === 'andrapk'
        ? 'andrapk'
        : `uploads/${(targetFolder || 'cloned-repo').replace(/[^a-zA-Z0-9._-]/g, '-')}`;

    const resolved = resolveSafeWorkspacePath(safeTarget);
    if (!resolved) {
      res.status(400).json({ error: 'Folder tujuan tidak valid.' });
      return;
    }

    const tmpDir = `/tmp/git-clone-${Date.now()}`;
    try {
      const args = ['clone', '--depth', '1'];
      if (parsed.branch) {
        args.push('--branch', parsed.branch);
      }
      args.push(parsed.cloneUrl, tmpDir);

      await execFileAsync('git', args, {
        env: { ...process.env, GIT_TERMINAL_PROMPT: '0' },
        timeout: 60000,
      });

      fs.rmSync(path.join(tmpDir, '.git'), { recursive: true, force: true });
      fs.rmSync(resolved.absPath, { recursive: true, force: true });
      ensureDir(path.dirname(resolved.absPath));
      fs.cpSync(tmpDir, resolved.absPath, { recursive: true });
      fs.rmSync(tmpDir, { recursive: true, force: true });

      res.json({
        ok: true,
        target: `/${resolved.normRel}`,
        branch: parsed.branch || 'default',
      });
    } catch (err) {
      fs.rmSync(tmpDir, { recursive: true, force: true });
      const msg = err instanceof Error ? err.message : 'Gagal melakukan git clone.';
      res.status(500).json({ error: msg });
    }
  });

  if (process.env.NODE_ENV !== 'production') {
    const { createServer: createViteServer } = await import('vite');
    const vite = await createViteServer({
      server: { middlewareMode: true },
      appType: 'spa',
    });
    app.use(vite.middlewares);
  } else {
    const distDir = path.resolve(WORKSPACE_ROOT, 'dist');
    app.use(express.static(distDir));
    app.get('*', (_req, res) => {
      res.sendFile(path.join(distDir, 'index.html'));
    });
  }

  app.listen(PORT, '0.0.0.0', () => {
    console.log(`Andrapk Workspace server listening on http://0.0.0.0:${PORT}`);
  });
}

startServer().catch((err) => {
  console.error('Failed to start server:', err);
  process.exit(1);
});
