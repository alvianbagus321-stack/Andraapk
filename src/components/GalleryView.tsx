import React, { useState } from 'react';
import { Image as ImageIcon, Maximize2, X } from 'lucide-react';
import { formatBytes } from '../utils/format';

interface GalleryImage {
  name: string;
  path: string;
  size: number;
}

interface GalleryViewProps {
  images: GalleryImage[];
}

export const GalleryView: React.FC<GalleryViewProps> = ({ images }) => {
  const [active, setActive] = useState<GalleryImage | null>(null);

  const docsImages = images.filter((img) => img.path.includes('/docs/'));
  const screenshots = images.filter((img) => !img.path.includes('/docs/'));

  const renderCard = (img: GalleryImage, wide?: boolean) => {
    const src = `/api/workspace/raw?path=${encodeURIComponent(img.path)}`;
    return (
      <button
        key={img.path}
        type="button"
        onClick={() => setActive(img)}
        className="group text-left bg-[#13161B] border border-[#252A33] hover:border-[#5B9CF6]/60 rounded-lg overflow-hidden transition-colors flex flex-col"
      >
        <div
          className={`w-full bg-[#0B0D10] flex items-center justify-center overflow-hidden relative ${
            wide ? 'aspect-video' : 'aspect-[9/16]'
          }`}
        >
          <img src={src} alt={img.name} className="w-full h-full object-contain" loading="lazy" />
          <div className="absolute top-2 right-2 p-1.5 rounded bg-[#0B0D10]/85 border border-[#252A33] opacity-0 group-hover:opacity-100 transition-opacity">
            <Maximize2 className="w-3.5 h-3.5 text-[#ECEFF4]" />
          </div>
        </div>
        <div className="px-3 py-2 border-t border-[#252A33]">
          <p className="font-mono text-[11px] text-[#ECEFF4] truncate">{img.name}</p>
          <p className="font-mono text-[10px] text-[#646E7E] mt-0.5">{formatBytes(img.size)}</p>
        </div>
      </button>
    );
  };

  if (images.length === 0) {
    return (
      <div className="flex-1 flex flex-col items-center justify-center bg-[#0B0D10] p-8 text-center">
        <ImageIcon className="w-8 h-8 text-[#646E7E] mb-2" />
        <p className="text-sm text-[#8B94A3]">Tidak ada aset gambar ditemukan.</p>
      </div>
    );
  }

  return (
    <div className="flex-1 overflow-y-auto bg-[#0B0D10] p-6">
      <div className="max-w-6xl mx-auto space-y-8">
        {docsImages.length > 0 && (
          <section className="space-y-3">
            <h2 className="text-xs font-semibold uppercase tracking-wider text-[#8B94A3]">
              Diagram & Dokumentasi (/andrapk/docs)
            </h2>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              {docsImages.map((img) => renderCard(img, true))}
            </div>
          </section>
        )}

        {screenshots.length > 0 && (
          <section className="space-y-3">
            <h2 className="text-xs font-semibold uppercase tracking-wider text-[#8B94A3]">
              Screenshot Aplikasi ({screenshots.length})
            </h2>
            <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-4">
              {screenshots.map((img) => renderCard(img, false))}
            </div>
          </section>
        )}
      </div>

      {active && (
        <div
          className="fixed inset-0 z-50 bg-[#0B0D10]/90 flex flex-col p-4"
          onClick={() => setActive(null)}
        >
          <div className="flex items-center justify-between max-w-6xl w-full mx-auto pb-3">
            <span className="font-mono text-xs text-[#ECEFF4]">/{active.path}</span>
            <button
              type="button"
              onClick={() => setActive(null)}
              className="p-1.5 rounded bg-[#13161B] border border-[#252A33] text-[#ECEFF4] hover:border-[#5B9CF6]"
            >
              <X className="w-4 h-4" />
            </button>
          </div>
          <div
            className="flex-1 flex items-center justify-center min-h-0"
            onClick={(e) => e.stopPropagation()}
          >
            <img
              src={`/api/workspace/raw?path=${encodeURIComponent(active.path)}`}
              alt={active.name}
              className="max-w-full max-h-full object-contain rounded border border-[#252A33]"
            />
          </div>
        </div>
      )}
    </div>
  );
};
