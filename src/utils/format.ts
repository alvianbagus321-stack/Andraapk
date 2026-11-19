export function formatBytes(bytes: number): string {
  if (bytes === 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB'];
  const i = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1);
  const val = bytes / Math.pow(1024, i);
  return `${val >= 10 || i === 0 ? val.toFixed(0) : val.toFixed(1)} ${units[i]}`;
}

export function getLanguageLabel(ext?: string): string {
  switch ((ext || '').toLowerCase()) {
    case '.kt':
      return 'Kotlin';
    case '.kts':
      return 'Kotlin Script';
    case '.java':
      return 'Java';
    case '.xml':
      return 'XML';
    case '.py':
      return 'Python';
    case '.sh':
      return 'Shell';
    case '.md':
      return 'Markdown';
    case '.json':
      return 'JSON';
    case '.toml':
      return 'TOML';
    case '.properties':
      return 'Properties';
    case '.png':
    case '.jpg':
    case '.jpeg':
    case '.webp':
      return 'Image';
    default:
      return ext ? ext.replace('.', '').toUpperCase() : 'Text';
  }
}
