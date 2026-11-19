import React, { useMemo, useState } from 'react';
import {
  ChevronDown,
  ChevronRight,
  FileCode2,
  FileImage,
  FileJson,
  FileText,
  Folder,
  FolderOpen,
  Search,
  Terminal,
  Trash2,
  X,
} from 'lucide-react';
import { TreeNode, WorkspaceRoot } from '../types/workspace';
import { formatBytes } from '../utils/format';

interface FileTreeProps {
  root: WorkspaceRoot;
  onRootChange: (root: WorkspaceRoot) => void;
  nodes: TreeNode[];
  selectedPath: string | null;
  onSelectFile: (path: string) => void;
  onDeletePath?: (path: string) => void;
  andrapkCount: number;
  uploadsCount: number;
  loading: boolean;
}

function filterNodes(nodes: TreeNode[], query: string): TreeNode[] {
  if (!query.trim()) return nodes;
  const q = query.toLowerCase();
  const result: TreeNode[] = [];

  for (const node of nodes) {
    if (node.type === 'directory') {
      const filteredChildren = filterNodes(node.children || [], query);
      if (node.name.toLowerCase().includes(q) || filteredChildren.length > 0) {
        result.push({ ...node, children: filteredChildren });
      }
    } else if (node.name.toLowerCase().includes(q) || node.path.toLowerCase().includes(q)) {
      result.push(node);
    }
  }
  return result;
}

function FileIcon({ node }: { node: TreeNode }) {
  if (node.isImage) return <FileImage className="w-3.5 h-3.5 text-[#34C77B] shrink-0" />;
  const ext = (node.ext || '').toLowerCase();
  if (ext === '.kt' || ext === '.kts' || ext === '.java') {
    return <FileCode2 className="w-3.5 h-3.5 text-[#5B9CF6] shrink-0" />;
  }
  if (ext === '.py' || ext === '.sh') {
    return <Terminal className="w-3.5 h-3.5 text-[#E5A93B] shrink-0" />;
  }
  if (ext === '.json' || ext === '.toml' || ext === '.xml') {
    return <FileJson className="w-3.5 h-3.5 text-[#94A3B8] shrink-0" />;
  }
  return <FileText className="w-3.5 h-3.5 text-[#8B94A3] shrink-0" />;
}

export const FileTree: React.FC<FileTreeProps> = ({
  root,
  onRootChange,
  nodes,
  selectedPath,
  onSelectFile,
  onDeletePath,
  andrapkCount,
  uploadsCount,
  loading,
}) => {
  const [query, setQuery] = useState('');
  const [expanded, setExpanded] = useState<Record<string, boolean>>({
    'andrapk/app': true,
    'andrapk/app/src': true,
    'andrapk/app/src/main': true,
    'andrapk/app/src/main/java': true,
    'andrapk/app/src/main/java/com': true,
    'andrapk/app/src/main/java/com/example': true,
    'andrapk/termux': true,
    'andrapk/docs': true,
  });

  const filtered = useMemo(() => filterNodes(nodes, query), [nodes, query]);

  const toggleDir = (dirPath: string) => {
    setExpanded((prev) => ({ ...prev, [dirPath]: !prev[dirPath] }));
  };

  const renderNode = (node: TreeNode, depth: number) => {
    const isDir = node.type === 'directory';
    const isOpen = query.trim().length > 0 ? true : !!expanded[node.path];
    const isSelected = selectedPath === node.path;

    return (
      <div key={node.path}>
        <div
          className={`group flex items-center gap-1.5 pr-2 py-1 text-[13px] cursor-pointer select-none transition-colors ${
            isSelected
              ? 'bg-[#242A33] text-[#ECEFF4] font-medium'
              : 'text-[#8B94A3] hover:bg-[#1B1F26] hover:text-[#ECEFF4]'
          }`}
          style={{ paddingLeft: `${depth * 14 + 10}px` }}
          onClick={() => (isDir ? toggleDir(node.path) : onSelectFile(node.path))}
        >
          {isDir ? (
            <>
              {isOpen ? (
                <ChevronDown className="w-3.5 h-3.5 text-[#646E7E] shrink-0" />
              ) : (
                <ChevronRight className="w-3.5 h-3.5 text-[#646E7E] shrink-0" />
              )}
              {isOpen ? (
                <FolderOpen className="w-3.5 h-3.5 text-[#5B9CF6] shrink-0" />
              ) : (
                <Folder className="w-3.5 h-3.5 text-[#5B9CF6]/80 shrink-0" />
              )}
            </>
          ) : (
            <>
              <span className="w-3.5 shrink-0" />
              <FileIcon node={node} />
            </>
          )}

          <span className="truncate flex-1 font-mono text-[12px]">{node.name}</span>

          {!isDir && typeof node.size === 'number' && (
            <span className="text-[10px] font-mono text-[#646E7E] group-hover:hidden">
              {formatBytes(node.size)}
            </span>
          )}

          {root === 'uploads' && onDeletePath && (
            <button
              type="button"
              title="Hapus"
              onClick={(e) => {
                e.stopPropagation();
                onDeletePath(node.path);
              }}
              className="hidden group-hover:flex items-center justify-center p-0.5 text-[#646E7E] hover:text-[#E55B64] rounded"
            >
              <Trash2 className="w-3 h-3" />
            </button>
          )}
        </div>

        {isDir && isOpen && node.children && node.children.length > 0 && (
          <div>{node.children.map((child) => renderNode(child, depth + 1))}</div>
        )}
      </div>
    );
  };

  return (
    <aside className="w-72 lg:w-80 shrink-0 bg-[#13161B] border-r border-[#252A33] flex flex-col h-full">
      {/* Root Switcher */}
      <div className="p-2.5 border-b border-[#252A33] grid grid-cols-2 gap-1.5">
        <button
          type="button"
          onClick={() => onRootChange('andrapk')}
          className={`flex items-center justify-between px-2.5 py-1.5 rounded text-xs font-medium border transition-colors ${
            root === 'andrapk'
              ? 'bg-[#1B1F26] border-[#5B9CF6]/50 text-[#ECEFF4]'
              : 'bg-transparent border-[#252A33] text-[#8B94A3] hover:text-[#ECEFF4]'
          }`}
        >
          <span className="font-mono">/andrapk</span>
          <span className="text-[10px] font-mono text-[#5B9CF6]">{andrapkCount}</span>
        </button>

        <button
          type="button"
          onClick={() => onRootChange('uploads')}
          className={`flex items-center justify-between px-2.5 py-1.5 rounded text-xs font-medium border transition-colors ${
            root === 'uploads'
              ? 'bg-[#1B1F26] border-[#34C77B]/50 text-[#ECEFF4]'
              : 'bg-transparent border-[#252A33] text-[#8B94A3] hover:text-[#ECEFF4]'
          }`}
        >
          <span className="font-mono">/uploads</span>
          <span className="text-[10px] font-mono text-[#34C77B]">{uploadsCount}</span>
        </button>
      </div>

      {/* Search */}
      <div className="p-2.5 border-b border-[#252A33]">
        <div className="relative">
          <Search className="w-3.5 h-3.5 text-[#646E7E] absolute left-2.5 top-1/2 -translate-y-1/2" />
          <input
            type="text"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder={`Cari file di /${root}...`}
            className="w-full bg-[#0B0D10] border border-[#252A33] focus:border-[#5B9CF6] rounded pl-8 pr-7 py-1.5 text-xs text-[#ECEFF4] placeholder-[#646E7E] outline-none"
          />
          {query && (
            <button
              type="button"
              onClick={() => setQuery('')}
              className="absolute right-2 top-1/2 -translate-y-1/2 text-[#646E7E] hover:text-[#ECEFF4]"
            >
              <X className="w-3.5 h-3.5" />
            </button>
          )}
        </div>
      </div>

      {/* Tree */}
      <div className="flex-1 overflow-y-auto py-1.5">
        {loading ? (
          <div className="px-4 py-8 text-xs text-[#646E7E] font-mono">Memuat struktur file...</div>
        ) : filtered.length === 0 ? (
          <div className="px-4 py-8 text-center">
            <p className="text-xs text-[#8B94A3]">
              {query
                ? 'Tidak ada file yang cocok.'
                : root === 'uploads'
                  ? 'Belum ada file di /uploads. Gunakan tab Upload untuk menambahkan file.'
                  : 'Folder kosong.'}
            </p>
          </div>
        ) : (
          filtered.map((node) => renderNode(node, 0))
        )}
      </div>
    </aside>
  );
};
