import React from 'react';
import type { KnowledgeDocument } from '../../../shared/types/api';
import type { DocumentPreview, DocumentChunkDetails } from '../api/knowledge-api';
import { RunStatus } from '../../../shared/types/enums';
import { Button } from '../../../shared/components/ui/Button';
import { useEscapeToClose } from '../../../shared/hooks/useEscapeToClose';
import { LuArrowLeft } from 'react-icons/lu';
import { formatBytes } from '../utils/knowledgeFormat';

interface DocumentDetailModalProps {
    doc: KnowledgeDocument;
    preview: DocumentPreview | null;
    previewLoading: boolean;
    previewError: string | null;
    viewMode: 'metadata' | 'chunks';
    chunkDetails: DocumentChunkDetails | null;
    loadingChunks: boolean;
    onViewRawData: () => void;
    onBackToMetadata: () => void;
    onClose: () => void;
}

const formatMetadata = (doc: KnowledgeDocument) =>
    doc.metadata ? JSON.stringify(doc.metadata, null, 2) : 'No metadata available for this document.';

/**
 * Read-only document context modal: metadata view (preview-backed, falling back
 * to the list-row DTO) plus a raw vector-chunk view. Extracted from KnowledgePage
 * to keep the page a thinner assembler. Owns no state — the page drives viewMode
 * and chunk loading via the callbacks below.
 */
export const DocumentDetailModal: React.FC<DocumentDetailModalProps> = ({
    doc,
    preview,
    previewLoading,
    previewError,
    viewMode,
    chunkDetails,
    loadingChunks,
    onViewRawData,
    onBackToMetadata,
    onClose,
}) => {
    useEscapeToClose(onClose);
    return (
        <div className="modal modal-open">
            <div className="modal-box w-11/12 max-w-3xl bg-(--theme-card) border border-(--theme-muted)/10">
                <h3 className="font-bold text-lg border-b border-(--theme-muted)/10 pb-4 mb-4">
                    Document Context: {doc.name}
                </h3>
                <div className="space-y-4">
                    {viewMode === 'metadata' ? (
                        <>
                            {previewError && (
                                <div className="text-xs text-warn-amber bg-warn-amber/10 px-3 py-2 rounded-md">
                                    Preview fetch failed: {previewError}. Falling back to list-row data.
                                </div>
                            )}
                            <div className="grid grid-cols-2 gap-4 text-sm">
                                <div>
                                    <span className="text-(--theme-muted) block text-xs uppercase">Content Type</span>
                                    {preview?.contentType ?? doc.contentType}
                                </div>
                                <div>
                                    <span className="text-(--theme-muted) block text-xs uppercase">File Size</span>
                                    {formatBytes(preview?.size ?? doc.size)}
                                </div>
                                <div>
                                    <span className="text-(--theme-muted) block text-xs uppercase">Vector Chunks</span>
                                    {previewLoading
                                        ? <span className="text-(--theme-muted) text-xs">loading…</span>
                                        : (preview?.chunkCount ?? doc.vectorIds?.length ?? 0) + ' chunks'
                                    }
                                    {preview && doc.vectorIds &&
                                        preview.chunkCount !== doc.vectorIds.length && (
                                        <span className="text-[10px] text-warn-amber block">
                                            list shows {doc.vectorIds.length}; server shows {preview.chunkCount}
                                        </span>
                                    )}
                                </div>
                                <div>
                                    <span className="text-(--theme-muted) block text-xs uppercase">Current Status</span>
                                    {(preview?.status ?? doc.status)}
                                    {(preview?.statusMessage ?? doc.statusMessage)
                                        ? ` (${preview?.statusMessage ?? doc.statusMessage})`
                                        : ''}
                                </div>
                                {(preview?.description ?? doc.description) && (
                                    <div className="col-span-2">
                                        <span className="text-(--theme-muted) block text-xs uppercase">Description</span>
                                        <span className="text-sm">{preview?.description ?? doc.description}</span>
                                    </div>
                                )}
                                {(preview?.uri ?? doc.uri) && (
                                    <div className="col-span-2">
                                        <span className="text-(--theme-muted) block text-xs uppercase">Source URI</span>
                                        <a href={preview?.uri ?? doc.uri ?? '#'} target="_blank" rel="noreferrer"
                                            className="text-xs text-info hover:underline break-all font-mono">
                                            {preview?.uri ?? doc.uri}
                                        </a>
                                    </div>
                                )}
                                <div className="col-span-2">
                                    <span className="text-(--theme-muted) block text-xs uppercase">Content Hash</span>
                                    <span className="font-mono text-xs opacity-70 break-all">{doc.contentHash || 'Not calculated'}</span>
                                </div>
                            </div>
                            <div>
                                <span className="text-(--theme-muted) block text-xs uppercase mb-2">Parsed Extractor Metadata</span>
                                <pre className="bg-obsidian-elevated p-4 rounded-lg overflow-x-auto text-xs font-mono">
                                    {preview?.metadata
                                        ? JSON.stringify(preview.metadata, null, 2)
                                        : formatMetadata(doc)
                                    }
                                </pre>
                            </div>
                        </>
                    ) : (
                        <div className="max-h-[60vh] overflow-y-auto space-y-4 pr-2">
                            {loadingChunks ? (
                                <div className="flex justify-center p-8 text-(--theme-muted)">Loading chunk data...</div>
                            ) : chunkDetails && chunkDetails.chunks.length > 0 ? (
                                <>
                                    <div className="text-xs text-(--theme-muted) px-1">
                                        <span className="font-medium text-(--theme-foreground)">{chunkDetails.documentName}</span>
                                        {' · '}
                                        <span className="font-mono">{chunkDetails.totalChunks}</span> chunks total
                                    </div>
                                    {chunkDetails.chunks.map((chunk, idx) => {
                                        const metaKeys = Object.keys(chunk.metadata ?? {});
                                        return (
                                            <div key={idx} className="bg-obsidian-elevated rounded-lg p-4 text-sm">
                                                <div className="flex justify-between items-start mb-2 text-(--theme-muted) text-xs">
                                                    <span>Chunk #{idx + 1}</span>
                                                    <span className="font-mono truncate max-w-50" title={JSON.stringify(chunk.metadata)}>
                                                        META: {metaKeys.length > 0 ? metaKeys.join(', ') : '—'}
                                                    </span>
                                                </div>
                                                <div className="whitespace-pre-wrap font-mono text-xs overflow-x-auto">
                                                    {chunk.text}
                                                </div>
                                            </div>
                                        );
                                    })}
                                </>
                            ) : (
                                <div className="text-center p-8 text-(--theme-muted)">No vector chunk data found.</div>
                            )}
                        </div>
                    )}
                </div>
                <div className="modal-action flex items-center justify-between">
                    <div>
                        {viewMode === 'metadata' ? (
                            <Button variant="outline" size="sm" onClick={onViewRawData} disabled={doc.status !== RunStatus.COMPLETED}>
                                View Raw Vector Data
                            </Button>
                        ) : (
                            <Button variant="ghost" size="sm" onClick={onBackToMetadata}>
                                <LuArrowLeft className="w-3.5 h-3.5 mr-1" /> Back to Metadata
                            </Button>
                        )}
                    </div>
                    <Button onClick={onClose}>Close</Button>
                </div>
            </div>
            <div className="modal-backdrop" onClick={onClose} />
        </div>
    );
};
