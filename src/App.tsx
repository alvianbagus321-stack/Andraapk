import { useCallback, useEffect, useState } from 'react';
import {
  CheckCircle2,
  Download,
  FolderTree,
  GitBranch,
  Images,
  RefreshCw,
  Upload,
} from 'lucide-react';
import { FileTree } from './components/FileTree';
import { FileViewer } from './components/FileViewer';
import { GalleryView } from './components/GalleryView';
import { UploadPanel } from './components/UploadPanel';
import {
  FilePreviewData,
  TreeNode,
  WorkspaceOverview,
  WorkspaceRoot,
} from './types/workspace';
import { formatBytes } from './utils/format';

type ActiveTab = 'explorer' | 'upload' | 'gallery';

export default function App() {
  const [activeTab, setActiveTab] = useState<ActiveTab>('explorer');
  const [root, setRoot] = useState<WorkspaceRoot>('andrapk');
  const [overview, setOverview] = useState<WorkspaceOverview | null>(null);
  const [tree, setTree] = useState<TreeNode[]>([]);
  const [treeLoading, setTreeLoading] = useState(true);

  const [selectedPath, setSelectedPath] = useState<string | null>('andrapk/README.md');
  const [fileData, setFileData] = useState<FilePreviewData | null>(null);
  const [fileLoading, setFileLoading] = useState(false);
  const [fileError, setFileError] = useState<string | null>(null);

  const fetchOverview = useCallback(async () => {
    try {
      const res = await fetch('/api/workspace/overview');
      if (res.ok) {
        const data: WorkspaceOverview = await res.json();
        setOverview(data);
      }
    } catch {
      // handled gracefully
    }
  }, []);

  const fetchTree = useCallback(async (targetRoot: WorkspaceRoot) => {
    setTreeLoading(true);
    try {
      const res = await fetch(`/api/workspace/tree?root=${encodeURIComponent(targetRoot)}`);
      if (res.ok) {
        const data = await res.json();
        setTree(data.tree || []);
      }
    } finally {
      setTreeLoading(false);
    }
  }, []);

  const fetchFile = useCallback(async (filePath: string) => {
    setFileLoading(true);
    setFileError(null);
    try {
      const res = await fetch(`/api/workspace/file?path=${encodeURIComponent(filePath)}`);
      const data = await res.json();
      if (!res.ok) {
        setFileData(null);
        setFileError(data.error || 'Gagal membaca file.');
        return;
      }
      setFileData(data);
    } catch {
      setFileData(null);
      setFileError('Koneksi ke server workspace gagal.');
    } finally {
      setFileLoading(false);
    }
  }, []);

  useEffect(() => {
    void fetchOverview();
  }, [fetchOverview]);

  useEffect(() => {
    void fetchTree(root);
  }, [root, fetchTree]);

  useEffect(() => {
    if (selectedPath) {
      void fetchFile(selectedPath);
    } else {
      setFileData(null);
      setFileError(null);
    }
  }, [selectedPath, fetchFile]);

  const handleRefresh = () => {
    void fetchOverview();
    void fetchTree(root);
    if (selectedPath) {
      void fetchFile(selectedPath);
    }
  };

  const handleWorkspaceChanged = (focusRoot?: WorkspaceRoot, focusFile?: string) => {
    void fetchOverview();
    const nextRoot = focusRoot || root;
    setRoot(nextRoot);
    void fetchTree(nextRoot);
    if (focusFile) {
      setSelectedPath(focusFile);
    }
  };

  const handleDeletePath = async (targetPath: string) => {
    const res = await fetch(`/api/workspace/file?path=${encodeURIComponent(targetPath)}`, {
      method: 'DELETE',
    });
    if (res.ok) {
      if (selectedPath === targetPath) {
        setSelectedPath(null);
      }
      void fetchOverview();
      void fetchTree(root);
    }
  };

  const allImages = [
    ...(overview?.andrapk.images || []),
    ...(overview?.uploads.images || []),
  ];

  return (
    <div className="h-screen w-screen flex flex-col bg-[#0B0D10] text-[#ECEFF4] overflow-hidden">
      {/* Top Bar */}
      <header className="h-13 px-4 border-b border-[#252A33] bg-[#13161B] flex items-center justify-between gap-4 shrink-0">
        <div className="flex items-center gap-3 min-w-0">
          <div className="flex items-center gap-2">
            <span className="w-2 h-2 rounded-full bg-[#34C77B]" />
            <h1 className="text-sm font-semibold tracking-tight text-[#ECEFF4] whitespace-nowrap">
              Andrapk Workspace
            </h1>
          </div>

          <div className="hidden md:flex items-center gap-1.5 px-2 py-0.5 rounded bg-[#1B1F26] border border-[#252A33] text-[11px] font-mono text-[#8B94A3]">
            <GitBranch className="w-3 h-3 text-[#5B9CF6]" />
            <span className="truncate max-w-[200px]">
              {overview?.branch || 'arena/3b32f909-andrapk'}
            </span>
          </div>

          {overview?.andrapkExists && (
            <div className="hidden lg:flex items-center gap-2 text-[11px] font-mono text-[#646E7E]">
              <CheckCircle2 className="w-3.5 h-3.5 text-[#34C77B]" />
              <span>
                {overview.andrapk.fileCount} file · {overview.andrapk.kotlinCount} Kotlin ·{' '}
                {formatBytes(overview.andrapk.totalBytes)}
              </span>
            </div>
          )}
        </div>

        {/* Navigation Tabs */}
        <nav className="flex items-center gap-1">
          <button
            type="button"
            onClick={() => setActiveTab('explorer')}
            className={`inline-flex items-center gap-1.5 px-3 py-1.5 rounded text-xs font-medium transition-colors ${
              activeTab === 'explorer'
                ? 'bg-[#242A33] text-[#ECEFF4] border border-[#323946]'
                : 'text-[#8B94A3] hover:text-[#ECEFF4] hover:bg-[#1B1F26] border border-transparent'
            }`}
          >
            <FolderTree className="w-3.5 h-3.5 text-[#5B9CF6]" />
            <span>Explorer</span>
          </button>

          <button
            type="button"
            onClick={() => setActiveTab('upload')}
            className={`inline-flex items-center gap-1.5 px-3 py-1.5 rounded text-xs font-medium transition-colors ${
              activeTab === 'upload'
                ? 'bg-[#242A33] text-[#ECEFF4] border border-[#323946]'
                : 'text-[#8B94A3] hover:text-[#ECEFF4] hover:bg-[#1B1F26] border border-transparent'
            }`}
          >
            <Upload className="w-3.5 h-3.5 text-[#34C77B]" />
            <span>Upload File</span>
          </button>

          <button
            type="button"
            onClick={() => setActiveTab('gallery')}
            className={`inline-flex items-center gap-1.5 px-3 py-1.5 rounded text-xs font-medium transition-colors ${
              activeTab === 'gallery'
                ? 'bg-[#242A33] text-[#ECEFF4] border border-[#323946]'
                : 'text-[#8B94A3] hover:text-[#ECEFF4] hover:bg-[#1B1F26] border border-transparent'
            }`}
          >
            <Images className="w-3.5 h-3.5 text-[#E5A93B]" />
            <span className="hidden sm:inline">Visual Docs</span>
            {allImages.length > 0 && (
              <span className="text-[10px] font-mono text-[#646E7E]">({allImages.length})</span>
            )}
          </button>

          <a
            href="/api/apk/download"
            download="Andraapk-debug.apk"
            title="Unduh APK debug terbaru"
            className="ml-1 inline-flex items-center gap-1.5 px-2.5 py-1.5 rounded text-xs font-medium bg-[#17452E] text-[#86E6B0] border border-[#276844] hover:bg-[#205735] transition-colors"
          >
            <Download className="w-3.5 h-3.5" />
            <span className="hidden sm:inline">Download APK</span>
          </a>

          <a
            href="/api/workspace/export-zip"
            download="Andrapk-v2.61.zip"
            title="Unduh seluruh proyek andrapk sebagai ZIP"
            className="ml-1 inline-flex items-center gap-1.5 px-2.5 py-1.5 rounded text-xs font-medium bg-[#1D3252] text-[#8CC0FF] border border-[#2F5387] hover:bg-[#25406B] transition-colors"
          >
            <Download className="w-3.5 h-3.5" />
            <span className="hidden sm:inline">Download ZIP</span>
          </a>

          <button
            type="button"
            onClick={handleRefresh}
            title="Muat ulang workspace"
            className="ml-1 p-1.5 rounded text-[#8B94A3] hover:text-[#ECEFF4] hover:bg-[#1B1F26] transition-colors"
          >
            <RefreshCw className="w-3.5 h-3.5" />
          </button>
        </nav>
      </header>

      {/* Main Content */}
      <main className="flex-1 flex min-h-0">
        {activeTab === 'explorer' && (
          <>
            <FileTree
              root={root}
              onRootChange={setRoot}
              nodes={tree}
              selectedPath={selectedPath}
              onSelectFile={(p) => setSelectedPath(p)}
              onDeletePath={handleDeletePath}
              andrapkCount={overview?.andrapk.fileCount ?? 0}
              uploadsCount={overview?.uploads.fileCount ?? 0}
              loading={treeLoading}
            />
            <FileViewer file={fileData} loading={fileLoading} error={fileError} />
          </>
        )}

        {activeTab === 'upload' && (
          <UploadPanel onWorkspaceChanged={handleWorkspaceChanged} />
        )}

        {activeTab === 'gallery' && <GalleryView images={allImages} />}
      </main>
    </div>
  );
}
