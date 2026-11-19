export type WorkspaceRoot = 'andrapk' | 'uploads';

export interface TreeNode {
  name: string;
  path: string;
  type: 'file' | 'directory';
  size?: number;
  ext?: string;
  isImage?: boolean;
  modifiedAt?: string;
  children?: TreeNode[];
}

export interface RootStats {
  fileCount: number;
  dirCount: number;
  totalBytes: number;
  kotlinCount: number;
  images: Array<{ name: string; path: string; size: number }>;
}

export interface WorkspaceOverview {
  repoUrl: string;
  branch: string;
  andrapkExists: boolean;
  andrapk: RootStats;
  uploads: RootStats;
}

export interface FilePreviewData {
  path: string;
  name: string;
  size: number;
  ext: string;
  kind: 'text' | 'image' | 'binary';
  content?: string;
  rawUrl: string;
}

export interface UploadRecord {
  id: string;
  fileName: string;
  targetPath: string;
  size: number;
  status: 'uploading' | 'done' | 'error';
  error?: string;
}
