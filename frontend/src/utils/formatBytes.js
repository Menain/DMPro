// Byte → human-readable formatter for the space-usage page (design 09-19-table-space-usage §5).
// 1024-based up to TB with one decimal, values below 1024 shown as-is in bytes — matching the
// backend worker's convertUnit and PG's pg_size_pretty conventions. The three pre-existing inline
// formatters (sqlFileUpload / ticket / ticketDetail) cap at MB and are intentionally left untouched.

const UNITS = ['B', 'KB', 'MB', 'GB', 'TB'];

export function formatBytes(bytes) {
  if (bytes === null || bytes === undefined) {
    return null;
  }
  const value = Number(bytes);
  if (!Number.isFinite(value)) {
    return null;
  }
  if (value < 1024) {
    return `${value} B`;
  }
  let scaled = value;
  let unit = 0;
  while (scaled >= 1024 && unit < UNITS.length - 1) {
    scaled /= 1024;
    unit += 1;
  }
  return `${scaled.toFixed(1)} ${UNITS[unit]}`;
}

export default formatBytes;
