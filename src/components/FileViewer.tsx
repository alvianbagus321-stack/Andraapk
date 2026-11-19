import React, { useState } from 'react';
import { Check, Copy, Download, FileCode2, FileQuestion, Image as ImageIcon } from 'lucide-react';
import { FilePreviewData } from '../types/workspace';
import { formatBytes, getLanguageLabel } from '../utils/format';

interface FileViewerProps {
  file: FilePreviewData | null;
  loading: boolean;
  error: string | null;
}

export const FileViewer: React.FC<FileViewerProps> = ({ file, loading, error }) => {
  const [copiedPath, setCopiedPath] = useState(false);
  const [copiedContent, setCopiedContent] = useState(false);

  const handleCopyPath = async () => {
    if (!file) return;
    await navigator.clipboard.writeText(`/${file.path}`);
    setCopiedPath(true);
    setTimeout(() => setCopiedPath(false), 1800);
  };

  const handleCopyContent = async () => {
    if (!file?.content) return;
    await navigator.clipboard.writeText(file.content);
    setCopiedContent(true);
    setTimeout(() => setCopiedContent(false), 1800);
  };

  if (loading) {
    return (
      <div className="flex-1 flex items-center justify-center bg-[#0B0D10] text-xs font-mono text-[#646E7E]">
        Membaca file...
      </div>
    );
  }

  if (error) {
    return (
      <div className="flex-1 flex items-center justify-center bg-[#0B0D10] p-6">
        <div className="border border-[#E55B64]/40 bg-[#13161B] rounded-lg px-4 py-3 text-xs text-[#E55B64] font-mono">
          {error}
        </div>
      </div>
    );
  }

  if (!file) {
    return (
      <div className="flex-1 flex flex-col items-center justify-center bg-[#0B0D10] p-8 text-center">
        <div className="w-11 h-11 rounded-lg bg-[#13161B] border border-[#252A33] flex items-center justify-center mb-3">
          <FileCode2 className="w-5 h-5 text-[#5B9CF6]" />
        </div>
        <p className="text-sm font-medium text-[#ECEFF4]">Pilih file dari panel kiri</p>
        <p className="text-xs text-[#8B94A3] mt-1 max-w-sm">
          Semua source code Kotlin, Gradle, Termux scripts, dan file upload langsung tersimpan di disk workspace dan siap diakses.
        </p>
      </div>
    );
  }

  const lines = file.kind === 'text' && file.content !== undefined ? file.content.split('\n') : [];

  return (
    <div className="flex-1 flex flex-col min-w-0 bg-[#0B0D10] h-full">
      {/* File Header */}
      <div className="h-11 px-4 border-b border-[#252A33] bg-[#13161B] flex items-center justify-between gap-3 shrink-0">
        <div className="flex items-center gap-2 min-w-0">
          {file.kind === 'image' ? (
            <ImageIcon className="w-4 h-4 text-[#34C77B] shrink-0" />
          ) : (
            <FileCode2 className="w-4 h-4 text-[#5B9CF6] shrink-0" />
          )}
          <span className="font-mono text-xs text-[#ECEFF4] truncate">/{file.path}</span>
          <span className="text-[11px] font-mono text-[#646E7E] shrink-0 hidden sm:inline">
            · {getLanguageLabel(file.ext)} · {formatBytes(file.size)}
            {file.kind === 'text' ? ` · ${lines.length} baris` : ''}
          </span>
        </div>

        <div className="flex items-center gap-1.5 shrink-0">
          <button
            type="button"
            onClick={handleCopyPath}
            className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded text-xs bg-[#1B1F26] hover:bg-[#242A33] border border-[#252A33] text-[#ECEFF4] transition-colors"
          >
            {copiedPath ? <Check className="w-3.5 h-3.5 text-[#34C77B]" /> : <Copy className="w-3.5 h-3.5 text-[#8B94A3]" />}
            <span>{copiedPath ? 'Path disalin' : 'Salin Path'}</span>
          </button>

          {file.kind === 'text' && (
            <button
              type="button"
              onClick={handleCopyContent}
              className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded text-xs bg-[#1B1F26] hover:bg-[#242A33] border border-[#252A33] text-[#ECEFF4] transition-colors"
            >
              {copiedContent ? <Check className="w-3.5 h-3.5 text-[#34C77B]" /> : <Copy className="w-3.5 h-3.5 text-[#8B94A3]" />}
              <span className="hidden md:inline">{copiedContent ? 'Isi disalin' : 'Salin Isi'}</span>
            </button>
          )}

          <a
            href={`${file.rawUrl}&download=1`}
            className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded text-xs bg-[#1B1F26] hover:bg-[#242A33] border border-[#252A33] text-[#ECEFF4] transition-colors"
          >
            <Download className="w-3.5 h-3.5 text-[#8B94A3]" />
            <span className="hidden md:inline">Unduh</span>
          </a>
        </div>
      </div>

      {/* Body */}
      <div className="flex-1 overflow-auto">
        {file.kind === 'image' ? (
          <div className="min-h-full flex items-center justify-center p-6 bg-[#0B0D10]">
            <img
              src={file.rawUrl}
              alt={file.name}
              className="max-w-full max-h-[78vh] object-contain rounded border border-[#252A33] bg-[#13161B]"
            />
          </div>
        ) : file.kind === 'binary' ? (
          <div className="h-full flex flex-col items-center justify-center p-8 text-center">
            <FileQuestion className="w-8 h-8 text-[#8B94A3] mb-3" />
            <p className="text-sm font-medium text-[#ECEFF4]">File biner ({formatBytes(file.size)})</p>
            <p className="text-xs text-[#8B94A3] mt-1">
              Preview teks tidak tersedia, namun file tersimpan utuh di <code className="text-[#5B9CF6]">/{file.path}</code>.
            </p>
          </div>
        ) : (
          <div className="font-mono text-[12.5px] leading-[1.6] py-3">
            <table className="w-full border-collapse">
              <tbody>
                {lines.map((line, idx) => (
                  <tr key={idx} className="hover:bg-[#13161B]">
                    <td className="w-12 select-none text-right pr-4 pl-3 text-[#646E7E] align-top border-r border-[#1B1F26]">
                      {idx + 1}
                    </td>
                    <td className="pl-4 pr-6 whitespace-pre text-[#ECEFF4]">{line || ' '}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
};
