import React, { useRef, useState } from 'react';
import {
  AlertCircle,
  CheckCircle2,
  FolderUp,
  GitBranch,
  Loader2,
  Upload,
} from 'lucide-react';
import { UploadRecord, WorkspaceRoot } from '../types/workspace';
import { formatBytes } from '../utils/format';

interface UploadPanelProps {
  onWorkspaceChanged: (focusRoot?: WorkspaceRoot, focusFile?: string) => void;
}

export const UploadPanel: React.FC<UploadPanelProps> = ({ onWorkspaceChanged }) => {
  const [targetRoot, setTargetRoot] = useState<WorkspaceRoot>('uploads');
  const [subPath, setSubPath] = useState('');
  const [isDragging, setIsDragging] = useState(false);
  const [records, setRecords] = useState<UploadRecord[]>([]);

  const [repoUrl, setRepoUrl] = useState(
    'https://github.com/alvianbagus321-stack/Andrapk/tree/arena/3b32f909-andrapk'
  );
  const [cloneTarget, setCloneTarget] = useState('andrapk');
  const [cloning, setCloning] = useState(false);
  const [cloneMessage, setCloneMessage] = useState<{ ok: boolean; text: string } | null>(null);

  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const folderInputRef = useRef<HTMLInputElement | null>(null);

  const uploadFiles = async (fileList: FileList | File[]) => {
    const files = Array.from(fileList);
    if (files.length === 0) return;

    const cleanPrefix = subPath.trim().replace(/^\/+|\/+$/g, '');
    let lastUploadedRel: string | undefined;

    for (const file of files) {
      const webkitRel = (file as File & { webkitRelativePath?: string }).webkitRelativePath;
      const relPart = webkitRel && webkitRel.length > 0 ? webkitRel : file.name;
      const relativePath = cleanPrefix ? `${cleanPrefix}/${relPart}` : relPart;
      const id = `${Date.now()}-${Math.random().toString(36).slice(2, 7)}`;

      setRecords((prev) => [
        {
          id,
          fileName: relPart,
          targetPath: `/${targetRoot}/${relativePath}`,
          size: file.size,
          status: 'uploading',
        },
        ...prev,
      ]);

      try {
        const params = new URLSearchParams({
          targetRoot,
          relativePath,
        });
        const response = await fetch(`/api/workspace/upload?${params.toString()}`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/octet-stream' },
          body: file,
        });

        const data = await response.json();
        if (!response.ok) {
          throw new Error(data.error || 'Upload gagal');
        }

        lastUploadedRel = data.relPath;
        setRecords((prev) =>
          prev.map((rec) =>
            rec.id === id ? { ...rec, status: 'done', targetPath: data.path } : rec
          )
        );
      } catch (err) {
        const message = err instanceof Error ? err.message : 'Gagal mengunggah file';
        setRecords((prev) =>
          prev.map((rec) => (rec.id === id ? { ...rec, status: 'error', error: message } : rec))
        );
      }
    }

    onWorkspaceChanged(targetRoot, lastUploadedRel);
  };

  const handleDrop = (e: React.DragEvent) => {
    e.preventDefault();
    setIsDragging(false);
    if (e.dataTransfer.files && e.dataTransfer.files.length > 0) {
      void uploadFiles(e.dataTransfer.files);
    }
  };

  const handleClone = async (e: React.FormEvent) => {
    e.preventDefault();
    setCloning(true);
    setCloneMessage(null);
    try {
      const res = await fetch('/api/workspace/clone', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          url: repoUrl,
          targetFolder: cloneTarget,
        }),
      });
      const data = await res.json();
      if (!res.ok) {
        throw new Error(data.error || 'Clone gagal');
      }
      setCloneMessage({
        ok: true,
        text: `Berhasil di-clone ke ${data.target} (branch: ${data.branch})`,
      });
      onWorkspaceChanged(cloneTarget === 'andrapk' ? 'andrapk' : 'uploads');
    } catch (err) {
      setCloneMessage({
        ok: false,
        text: err instanceof Error ? err.message : 'Gagal melakukan clone.',
      });
    } finally {
      setCloning(false);
    }
  };

  return (
    <div className="flex-1 overflow-y-auto bg-[#0B0D10] p-6">
      <div className="max-w-4xl mx-auto space-y-6">
        {/* Direct Workspace File Uploader */}
        <section className="bg-[#13161B] border border-[#252A33] rounded-lg p-5 space-y-4">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div>
              <h2 className="text-sm font-semibold text-[#ECEFF4]">
                Upload File atau Folder ke Workspace
              </h2>
              <p className="text-xs text-[#8B94A3] mt-0.5">
                File yang diunggah langsung ditulis ke disk workspace sehingga bisa langsung dibaca & diproses.
              </p>
            </div>
            <div className="flex items-center gap-2">
              <label className="text-xs text-[#8B94A3]">Tujuan:</label>
              <select
                value={targetRoot}
                onChange={(e) => setTargetRoot(e.target.value as WorkspaceRoot)}
                className="bg-[#0B0D10] border border-[#252A33] rounded px-2.5 py-1 text-xs font-mono text-[#ECEFF4] outline-none focus:border-[#5B9CF6]"
              >
                <option value="uploads">/uploads</option>
                <option value="andrapk">/andrapk</option>
              </select>
              <input
                type="text"
                value={subPath}
                onChange={(e) => setSubPath(e.target.value)}
                placeholder="subfolder (opsional)"
                className="bg-[#0B0D10] border border-[#252A33] rounded px-2.5 py-1 text-xs font-mono text-[#ECEFF4] placeholder-[#646E7E] w-40 outline-none focus:border-[#5B9CF6]"
              />
            </div>
          </div>

          <div
            onDragOver={(e) => {
              e.preventDefault();
              setIsDragging(true);
            }}
            onDragLeave={() => setIsDragging(false)}
            onDrop={handleDrop}
            className={`border border-dashed rounded-lg p-8 text-center transition-colors ${
              isDragging
                ? 'border-[#5B9CF6] bg-[#5B9CF6]/10'
                : 'border-[#323946] bg-[#0B0D10] hover:border-[#5B9CF6]/60'
            }`}
          >
            <Upload className="w-7 h-7 text-[#5B9CF6] mx-auto mb-3" />
            <p className="text-sm font-medium text-[#ECEFF4]">
              Tarik & lepas file ke sini, atau pilih dari perangkat
            </p>
            <p className="text-xs text-[#8B94A3] mt-1 font-mono">
              Mendukung semua format (.kt, .xml, .gradle, .zip, .apk, gambar) hingga 50 MB / file
            </p>

            <div className="flex flex-wrap items-center justify-center gap-2.5 mt-5">
              <button
                type="button"
                onClick={() => fileInputRef.current?.click()}
                className="inline-flex items-center gap-2 px-3.5 py-2 rounded text-xs font-medium bg-[#5B9CF6] hover:bg-[#3B82F6] text-[#071324] transition-colors"
              >
                <Upload className="w-3.5 h-3.5" />
                <span>Pilih File</span>
              </button>

              <button
                type="button"
                onClick={() => folderInputRef.current?.click()}
                className="inline-flex items-center gap-2 px-3.5 py-2 rounded text-xs font-medium bg-[#1B1F26] hover:bg-[#242A33] border border-[#323946] text-[#ECEFF4] transition-colors"
              >
                <FolderUp className="w-3.5 h-3.5 text-[#5B9CF6]" />
                <span>Upload Satu Folder</span>
              </button>
            </div>

            <input
              ref={fileInputRef}
              type="file"
              multiple
              className="hidden"
              onChange={(e) => {
                if (e.target.files) void uploadFiles(e.target.files);
                e.target.value = '';
              }}
            />

            <input
              ref={folderInputRef}
              type="file"
              multiple
              {...({ webkitdirectory: '', directory: '' } as React.InputHTMLAttributes<HTMLInputElement>)}
              className="hidden"
              onChange={(e) => {
                if (e.target.files) void uploadFiles(e.target.files);
                e.target.value = '';
              }}
            />
          </div>

          {records.length > 0 && (
            <div className="border border-[#252A33] rounded-lg divide-y divide-[#252A33] bg-[#0B0D10] max-h-60 overflow-y-auto">
              {records.map((rec) => (
                <div key={rec.id} className="px-3.5 py-2 flex items-center justify-between gap-3 text-xs">
                  <div className="flex items-center gap-2 min-w-0">
                    {rec.status === 'uploading' && (
                      <Loader2 className="w-3.5 h-3.5 text-[#5B9CF6] animate-spin shrink-0" />
                    )}
                    {rec.status === 'done' && (
                      <CheckCircle2 className="w-3.5 h-3.5 text-[#34C77B] shrink-0" />
                    )}
                    {rec.status === 'error' && (
                      <AlertCircle className="w-3.5 h-3.5 text-[#E55B64] shrink-0" />
                    )}
                    <span className="font-mono text-[#ECEFF4] truncate">{rec.targetPath}</span>
                  </div>
                  <span className="font-mono text-[11px] text-[#646E7E] shrink-0">
                    {rec.error ? rec.error : formatBytes(rec.size)}
                  </span>
                </div>
              ))}
            </div>
          )}
        </section>

        {/* GitHub Clone / Refresh */}
        <section className="bg-[#13161B] border border-[#252A33] rounded-lg p-5 space-y-4">
          <div className="flex items-center gap-2">
            <GitBranch className="w-4 h-4 text-[#5B9CF6]" />
            <div>
              <h2 className="text-sm font-semibold text-[#ECEFF4]">
                Sync / Clone Repository GitHub
              </h2>
              <p className="text-xs text-[#8B94A3]">
                Repo Andrapk sudah ter-clone di <code className="text-[#5B9CF6]">/andrapk</code>. Gunakan form ini bila ingin pull ulang atau clone branch lain.
              </p>
            </div>
          </div>

          <form onSubmit={handleClone} className="grid grid-cols-1 md:grid-cols-12 gap-2.5">
            <input
              type="url"
              required
              value={repoUrl}
              onChange={(e) => setRepoUrl(e.target.value)}
              placeholder="https://github.com/owner/repo/tree/branch"
              className="md:col-span-7 bg-[#0B0D10] border border-[#252A33] focus:border-[#5B9CF6] rounded px-3 py-2 text-xs font-mono text-[#ECEFF4] outline-none"
            />
            <input
              type="text"
              value={cloneTarget}
              onChange={(e) => setCloneTarget(e.target.value)}
              placeholder="andrapk atau nama folder"
              className="md:col-span-3 bg-[#0B0D10] border border-[#252A33] focus:border-[#5B9CF6] rounded px-3 py-2 text-xs font-mono text-[#ECEFF4] outline-none"
            />
            <button
              type="submit"
              disabled={cloning}
              className="md:col-span-2 inline-flex items-center justify-center gap-1.5 px-3 py-2 rounded text-xs font-medium bg-[#1B1F26] hover:bg-[#242A33] border border-[#323946] text-[#ECEFF4] disabled:opacity-50 transition-colors"
            >
              {cloning ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <GitBranch className="w-3.5 h-3.5 text-[#5B9CF6]" />}
              <span>{cloning ? 'Cloning...' : 'Clone'}</span>
            </button>
          </form>

          {cloneMessage && (
            <div
              className={`text-xs font-mono px-3 py-2 rounded border ${
                cloneMessage.ok
                  ? 'bg-[#34C77B]/10 border-[#34C77B]/40 text-[#34C77B]'
                  : 'bg-[#E55B64]/10 border-[#E55B64]/40 text-[#E55B64]'
              }`}
            >
              {cloneMessage.text}
            </div>
          )}
        </section>
      </div>
    </div>
  );
};
