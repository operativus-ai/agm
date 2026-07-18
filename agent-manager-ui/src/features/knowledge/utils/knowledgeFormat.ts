/**
 * Shared formatting helpers for the Knowledge feature. Extracted from
 * KnowledgePage so the page, its column factory, and its document modal can
 * share one implementation.
 */

/** Backend dates arrive either as ISO strings or as [y,m,d,h,m,s] arrays. */
export const parseDate = (dateVal: unknown): Date => {
    if (!dateVal) return new Date(0);
    if (Array.isArray(dateVal) && dateVal.length >= 3) {
        return new Date(dateVal[0], dateVal[1] - 1, dateVal[2], dateVal[3] || 0, dateVal[4] || 0, dateVal[5] || 0);
    }
    return new Date(dateVal as string);
};

export const formatBytes = (bytes: number): string => {
    if (!bytes || bytes === 0) return '—';
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
};

/** A PROCESSING doc older than this is flagged "Stuck" in the table. */
export const PROCESSING_STALE_MS = 90_000;
