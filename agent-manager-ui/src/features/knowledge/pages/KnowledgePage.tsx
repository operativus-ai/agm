import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { KnowledgeDocument, KnowledgeBase } from '../../../shared/types/api';
import { KnowledgeApi } from '../api/knowledge-api';
import type { DocumentPreview, DocumentChunkDetails } from '../api/knowledge-api';
import { KnowledgeBasesApi } from '../api/knowledge-bases-api';
import { AssignedAgentsPanel } from '../components/AssignedAgentsPanel';
import { ContentIngester } from '../components/ContentIngester';
import { RagSandbox } from '../components/RagSandbox';
import { createDocumentColumns } from '../components/documentColumns';
import { DocumentDetailModal } from '../components/DocumentDetailModal';
import { EditKbModal, MoveDocumentModal, DeleteKbDialog } from '../components/knowledgeModals';
import { RunStatus } from '../../../shared/types/enums';

import { PageHeader } from '../../../shared/components/ui/PageHeader';
import { Typography } from '../../../shared/components/ui/Typography';
import { Alert } from '../../../shared/components/ui/Alert';
import { Badge } from '../../../shared/components/ui/Badge';
import { Button } from '../../../shared/components/ui/Button';
import { DataTable } from '../../../shared/components/ui/DataTable';
import {
    LuTrash2, LuFolderOpen, LuFolderClosed,
    LuPlus, LuSparkles, LuDatabase, LuPencil,
    LuUpload, LuBot, LuFlaskConical, LuChevronRight as LuChevronRightIcon,
} from 'react-icons/lu';
import { PageContainer } from '../../../shared/components/ui/PageContainer';
import { cn } from '../../../shared/utils/cn';

type ActiveTab = 'documents' | 'upload' | 'agents' | 'rag';

const TABS: { id: ActiveTab; label: string; icon: React.ElementType; requiresKb?: boolean }[] = [
    { id: 'documents', label: 'Documents', icon: LuDatabase },
    { id: 'upload',    label: 'Add Content', icon: LuUpload },
    { id: 'agents',    label: 'Agents',      icon: LuBot, requiresKb: true },
    { id: 'rag',       label: 'RAG Sandbox', icon: LuFlaskConical },
];

export const KnowledgePage: React.FC = () => {
    const [documents, setDocuments] = useState<KnowledgeDocument[]>([]);
    const [knowledgeBases, setKnowledgeBases] = useState<KnowledgeBase[]>([]);
    const [selectedBaseId, setSelectedBaseId] = useState<string | undefined>(undefined);
    const [activeTab, setActiveTab] = useState<ActiveTab>('documents');

    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const PAGE_SIZE = 20;
    const [pageIndex, setPageIndex] = useState(0);
    const [totalElements, setTotalElements] = useState(0);

    // Create KB form state
    const [showCreateForm, setShowCreateForm] = useState(false);
    const [newKbName, setNewKbName] = useState('');
    const [newKbDescription, setNewKbDescription] = useState('');
    const [creatingKb, setCreatingKb] = useState(false);

    // Edit KB modal state
    const [editingKb, setEditingKb] = useState<KnowledgeBase | null>(null);
    const [editNameDraft, setEditNameDraft] = useState('');
    const [editDescDraft, setEditDescDraft] = useState('');
    const [savingKbEdit, setSavingKbEdit] = useState(false);

    // KB delete confirm dialog state
    const [deletingKb, setDeletingKb] = useState<KnowledgeBase | null>(null);
    const [deleteKbAgents, setDeleteKbAgents] = useState<string[]>([]);
    const [deleteKbLoading, setDeleteKbLoading] = useState(false);

    // Document detail modal state
    const [selectedDoc, setSelectedDoc] = useState<KnowledgeDocument | null>(null);
    const [chunkDetails, setChunkDetails] = useState<DocumentChunkDetails | null>(null);
    const [loadingChunks, setLoadingChunks] = useState(false);
    const [viewMode, setViewMode] = useState<'metadata' | 'chunks'>('metadata');
    const [preview, setPreview] = useState<DocumentPreview | null>(null);
    const [previewLoading, setPreviewLoading] = useState(false);
    const [previewError, setPreviewError] = useState<string | null>(null);

    // Move document modal state
    const [movingDoc, setMovingDoc] = useState<KnowledgeDocument | null>(null);
    const [moveTargetKbId, setMoveTargetKbId] = useState<string>('');
    const [movingInProgress, setMovingInProgress] = useState(false);

    const sseRefs = useRef<Map<string, AbortController>>(new Map());

    const loadData = useCallback(async () => {
        try {
            setLoading(true);
            setError(null);
            const [docsData, kbsData] = await Promise.all([
                KnowledgeApi.getDocuments({ page: pageIndex, size: PAGE_SIZE, knowledgeBaseId: selectedBaseId }),
                KnowledgeBasesApi.getAll(),
            ]);
            if (docsData?.content) {
                setDocuments(docsData.content);
                setTotalElements(docsData.page.totalElements);
            }
            if (Array.isArray(kbsData)) setKnowledgeBases(kbsData);
        } catch (err: any) {
            setError(err.message || 'Failed to load data');
        } finally {
            setLoading(false);
        }
    }, [pageIndex, selectedBaseId]);

    useEffect(() => { loadData(); }, [loadData]);

    const handleSelectBase = (id: string | undefined) => {
        setSelectedBaseId(id);
        setPageIndex(0);
        setActiveTab('documents');
    };

    // SSE: subscribe to PROCESSING docs
    useEffect(() => {
        for (const doc of documents) {
            if (doc.status !== RunStatus.PROCESSING) continue;
            if (sseRefs.current.has(doc.id)) continue;
            const es = KnowledgeApi.subscribeToIngestionStatus(
                doc.id,
                (status) => {
                    if (status === RunStatus.COMPLETED || status === RunStatus.FAILED) {
                        KnowledgeApi.getDocumentById(doc.id).then(updated => {
                            setDocuments(prev => prev.map(d => d.id === updated.id ? updated : d));
                            KnowledgeBasesApi.getAll().then(kbs => { if (Array.isArray(kbs)) setKnowledgeBases(kbs); });
                        }).catch(() => {});
                        sseRefs.current.delete(doc.id);
                    }
                },
                () => sseRefs.current.delete(doc.id)
            );
            sseRefs.current.set(doc.id, es);
        }
    }, [documents]);

    useEffect(() => {
        return () => {
            sseRefs.current.forEach(ctrl => ctrl.abort());
            sseRefs.current.clear();
        };
    }, []);

    const handleIngested = () => loadData();

    const handleDelete = async (id: string) => {
        if (!confirm('Delete this document and remove its vector chunks?')) return;
        try {
            await KnowledgeApi.deleteDocument(id);
            setDocuments(prev => prev.filter(d => d.id !== id));
        } catch {
            setError('Failed to delete document.');
        }
    };

    const handleRetry = async (id: string) => {
        try {
            await KnowledgeApi.retryIngestion(id);
            // Optimistic state flip — backend will emit SSE updates as the retry runs.
            setDocuments(prev => prev.map(d => d.id === id ? { ...d, status: RunStatus.PROCESSING, statusMessage: undefined } : d));
        } catch (e: any) {
            // 422 = file upload (raw bytes not persisted; user must re-upload). Surface as info.
            const msg = e?.message || '';
            if (msg.includes('retry_not_supported_for_file_uploads') || msg.includes('422')) {
                setError('Retry is only supported for URL-sourced documents. Please re-upload the file.');
            } else {
                setError('Failed to retry ingestion: ' + msg);
            }
        }
    };

    const handleOpenMove = (doc: KnowledgeDocument) => {
        setMovingDoc(doc);
        setMoveTargetKbId('');
    };

    const handleConfirmMove = async () => {
        if (!movingDoc || !moveTargetKbId) return;
        setMovingInProgress(true);
        try {
            await KnowledgeApi.moveDocument(movingDoc.id, moveTargetKbId);
            setMovingDoc(null);
            loadData();
        } catch (err: any) {
            setError(err.message || 'Failed to move document');
        } finally {
            setMovingInProgress(false);
        }
    };

    const handleCreateKb = async (e: React.FormEvent) => {
        e.preventDefault();
        if (!newKbName.trim()) return;
        try {
            setCreatingKb(true);
            const newKb = await KnowledgeBasesApi.create({ name: newKbName, description: newKbDescription || undefined });
            setKnowledgeBases(prev => [...prev, newKb]);
            setNewKbName('');
            setNewKbDescription('');
            setShowCreateForm(false);
            handleSelectBase(newKb.id);
        } catch (err: any) {
            setError(err.message || 'Failed to create knowledge base');
        } finally {
            setCreatingKb(false);
        }
    };

    const handleStartEdit = (kb: KnowledgeBase) => {
        setEditingKb(kb);
        setEditNameDraft(kb.name);
        setEditDescDraft(kb.description ?? '');
    };

    const handleCommitEdit = async () => {
        if (!editingKb || !editNameDraft.trim()) return;
        setSavingKbEdit(true);
        try {
            const updated = await KnowledgeBasesApi.update(editingKb.id, {
                name: editNameDraft.trim(),
                description: editDescDraft.trim() || undefined,
            });
            setKnowledgeBases(prev => prev.map(k => k.id === editingKb.id ? { ...k, name: updated.name, description: updated.description } : k));
        } catch (err: any) {
            setError(err.message || 'Failed to update collection');
        } finally {
            setSavingKbEdit(false);
            setEditingKb(null);
        }
    };

    const handleInitiateDeleteKb = async (kb: KnowledgeBase) => {
        setDeleteKbLoading(true);
        setDeletingKb(kb);
        try {
            const agents = await KnowledgeBasesApi.getAssignedAgents(kb.id);
            setDeleteKbAgents(agents.map(a => a.name));
        } catch {
            setDeleteKbAgents([]);
        } finally {
            setDeleteKbLoading(false);
        }
    };

    const handleConfirmDeleteKb = async () => {
        if (!deletingKb) return;
        try {
            await KnowledgeBasesApi.delete(deletingKb.id);
            setKnowledgeBases(prev => prev.filter(k => k.id !== deletingKb.id));
            if (selectedBaseId === deletingKb.id) handleSelectBase(undefined);
        } catch (err: any) {
            setError(err.message || 'Failed to delete knowledge base');
        } finally {
            setDeletingKb(null);
            setDeleteKbAgents([]);
        }
    };

    const handleOpenModal = (doc: KnowledgeDocument) => {
        setSelectedDoc(doc);
        setChunkDetails(null);
        setViewMode('metadata');
        setPreview(null);
        setPreviewError(null);
        // Fetch typed preview alongside the local-row data. The list-row DTO can
        // drift from the server's view (e.g. chunkCount is computed server-side);
        // the preview endpoint is the canonical source.
        setPreviewLoading(true);
        KnowledgeApi.getDocumentPreview(doc.id)
            .then(setPreview)
            .catch((err: unknown) => {
                setPreviewError(err instanceof Error ? err.message : 'Failed to load preview.');
            })
            .finally(() => setPreviewLoading(false));
    };

    const handleCloseModal = () => {
        setSelectedDoc(null);
        setPreview(null);
        setPreviewError(null);
        setChunkDetails(null);
    };

    const handleViewRawData = async () => {
        if (!selectedDoc) return;
        setViewMode('chunks');
        if (chunkDetails !== null) return;
        setLoadingChunks(true);
        try {
            const data = await KnowledgeApi.getChunkDetails(selectedDoc.id);
            setChunkDetails(data);
        } catch {
            // ignore — leave chunkDetails null; render falls back to empty state
        } finally {
            setLoadingChunks(false);
        }
    };

    const selectedKb = knowledgeBases.find(k => k.id === selectedBaseId);

    const columns = useMemo(() => createDocumentColumns({
        knowledgeBases,
        selectedBaseId,
        onOpenModal: handleOpenModal,
        onRetry: handleRetry,
        onOpenMove: handleOpenMove,
        onDelete: handleDelete,
    }), [knowledgeBases, selectedBaseId]);

    return (
        <PageContainer variant="dashboard">
            <PageHeader
                icon={LuDatabase}
                title="Knowledge Base"
                subtitle="Manage collections of documents indexed for RAG (Retrieval Augmented Generation)."
            />

            {error && <Alert severity="error" title="Error" className="mb-4">{error}</Alert>}

            {/* ── Two-panel layout ── */}
            <div className="flex gap-4 items-start">

                {/* ── Left sidebar: Collections ── */}
                <aside className="w-64 shrink-0 sticky top-0 self-start flex flex-col bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl overflow-hidden max-h-[calc(100vh-160px)]">
                    {/* Sidebar header */}
                    <div className="px-4 py-3 border-b border-(--theme-muted)/10 flex items-center justify-between shrink-0">
                        <span className="text-xs font-bold uppercase tracking-wider text-(--theme-muted)">Collections</span>
                        <button
                            className="btn btn-ghost btn-xs btn-square"
                            title="New collection"
                            onClick={() => setShowCreateForm(v => !v)}
                        >
                            <LuPlus className="w-3.5 h-3.5" />
                        </button>
                    </div>

                    {/* Collection list */}
                    <div className="flex-1 overflow-y-auto py-1.5 px-1.5 space-y-0.5 scrollbar-thin scrollbar-thumb-obsidian-stroke scrollbar-track-transparent">
                        {/* All Documents */}
                        <button
                            onClick={() => handleSelectBase(undefined)}
                            className={cn(
                                'w-full flex items-center gap-2.5 px-2.5 py-2 rounded-lg text-sm transition-colors text-left group',
                                !selectedBaseId
                                    ? 'bg-primary/10 text-primary border border-primary/20'
                                    : 'text-(--theme-muted) hover:bg-(--theme-muted)/5 hover:text-(--theme-foreground) border border-transparent'
                            )}
                        >
                            <LuSparkles className="w-4 h-4 shrink-0" />
                            <span className="flex-1 font-medium truncate">All Documents</span>
                            <Badge variant="neutral" className="text-[10px] shrink-0">{totalElements}</Badge>
                        </button>

                        {/* Per-KB items */}
                        {knowledgeBases.map(kb => (
                            <div key={kb.id} className="group relative">
                                <button
                                    onClick={() => handleSelectBase(kb.id)}
                                    title={kb.description || undefined}
                                    className={cn(
                                        'w-full flex items-center gap-2.5 px-2.5 py-2 rounded-lg text-sm transition-colors text-left pr-14',
                                        selectedBaseId === kb.id
                                            ? 'bg-primary/10 text-primary border border-primary/20'
                                            : 'text-(--theme-muted) hover:bg-(--theme-muted)/5 hover:text-(--theme-foreground) border border-transparent'
                                    )}
                                >
                                    {selectedBaseId === kb.id
                                        ? <LuFolderOpen className="w-4 h-4 shrink-0" />
                                        : <LuFolderClosed className="w-4 h-4 shrink-0" />
                                    }
                                    <div className="flex-1 min-w-0">
                                        <span className="font-medium truncate block">{kb.name}</span>
                                        {kb.description && (
                                            <span className="text-[10px] opacity-60 truncate block">{kb.description}</span>
                                        )}
                                    </div>
                                    <Badge variant="neutral" className="text-[10px] shrink-0 mr-1">{kb.documentCount ?? 0}</Badge>
                                </button>
                                {/* Edit / Delete — appear on row hover */}
                                <div className="absolute right-1 top-1/2 -translate-y-1/2 flex items-center gap-0.5 opacity-0 group-hover:opacity-100 transition-opacity">
                                    <button
                                        className="btn btn-ghost btn-xs btn-square"
                                        title="Edit collection"
                                        onClick={(e) => { e.stopPropagation(); handleStartEdit(kb); }}
                                    >
                                        <LuPencil className="w-3 h-3" />
                                    </button>
                                    <button
                                        className="btn btn-ghost btn-xs btn-square hover:text-error"
                                        title="Delete collection"
                                        onClick={(e) => { e.stopPropagation(); handleInitiateDeleteKb(kb); }}
                                    >
                                        <LuTrash2 className="w-3 h-3" />
                                    </button>
                                </div>
                            </div>
                        ))}

                        {knowledgeBases.length === 0 && (
                            <p className="text-center text-xs text-(--theme-muted) opacity-50 py-6 px-2 italic">
                                No collections yet
                            </p>
                        )}
                    </div>

                    {/* Create collection form */}
                    {showCreateForm && (
                        <div className="border-t border-(--theme-muted)/10 p-3 shrink-0 space-y-2">
                            <p className="text-xs font-semibold text-(--theme-muted) uppercase tracking-wider">New Collection</p>
                            <form onSubmit={handleCreateKb} className="space-y-2">
                                <input
                                    type="text"
                                    placeholder="Collection name"
                                    className="input input-xs input-bordered w-full"
                                    value={newKbName}
                                    onChange={e => setNewKbName(e.target.value)}
                                    autoFocus
                                />
                                <textarea
                                    placeholder="Description (optional)"
                                    className="textarea textarea-bordered textarea-xs w-full resize-none h-12 text-xs"
                                    value={newKbDescription}
                                    onChange={e => setNewKbDescription(e.target.value)}
                                />
                                <div className="flex gap-1.5">
                                    <Button type="submit" size="sm" variant="primary" className="flex-1 btn-xs" disabled={creatingKb || !newKbName.trim()}>
                                        {creatingKb ? <span className="loading loading-spinner loading-xs" /> : 'Create'}
                                    </Button>
                                    <Button type="button" size="sm" variant="ghost" className="btn-xs" onClick={() => { setShowCreateForm(false); setNewKbName(''); setNewKbDescription(''); }}>
                                        Cancel
                                    </Button>
                                </div>
                            </form>
                        </div>
                    )}
                </aside>

                {/* ── Right panel ── */}
                <div className="flex-1 min-w-0 flex flex-col gap-4">

                    {/* Context header for selected KB */}
                    <div className="flex items-center gap-3 min-h-7">
                        {selectedKb ? (
                            <>
                                <LuFolderOpen className="w-5 h-5 text-primary shrink-0" />
                                <div className="min-w-0">
                                    <Typography.Heading level={3} className="text-base leading-tight">{selectedKb.name}</Typography.Heading>
                                    {selectedKb.description && (
                                        <p className="text-xs text-(--theme-muted) truncate max-w-xl">{selectedKb.description}</p>
                                    )}
                                </div>
                            </>
                        ) : (
                            <>
                                <LuSparkles className="w-5 h-5 text-primary shrink-0" />
                                <Typography.Heading level={3} className="text-base">All Documents</Typography.Heading>
                            </>
                        )}
                    </div>

                    {/* Tab bar */}
                    <div className="flex items-center gap-1 border-b border-(--theme-muted)/10 pb-0">
                        {TABS.map(tab => {
                            const Icon = tab.icon;
                            const disabled = tab.requiresKb && !selectedBaseId;
                            const isActive = activeTab === tab.id;
                            return (
                                <button
                                    key={tab.id}
                                    onClick={() => !disabled && setActiveTab(tab.id)}
                                    disabled={disabled}
                                    className={cn(
                                        'flex items-center gap-1.5 px-4 py-2.5 text-sm font-medium border-b-2 -mb-px transition-colors',
                                        isActive
                                            ? 'border-primary text-primary'
                                            : disabled
                                                ? 'border-transparent text-(--theme-muted) opacity-30 cursor-not-allowed'
                                                : 'border-transparent text-(--theme-muted) hover:text-(--theme-foreground) hover:border-(--theme-muted)/40'
                                    )}
                                >
                                    <Icon className="w-3.5 h-3.5" />
                                    {tab.label}
                                </button>
                            );
                        })}
                    </div>

                    {/* Tab content */}
                    <div className="min-h-100">

                        {/* Documents tab */}
                        {activeTab === 'documents' && (
                            loading ? (
                                <div className="space-y-2">
                                    {[1, 2, 3, 4, 5].map(i => <div key={i} className="h-12 bg-obsidian-elevated/50 rounded-md animate-pulse" />)}
                                </div>
                            ) : (
                                <DataTable
                                    columns={columns}
                                    data={documents}
                                    manualPagination
                                    pageIndex={pageIndex}
                                    pageSize={PAGE_SIZE}
                                    totalElements={totalElements}
                                    onPageChange={setPageIndex}
                                    emptyMessage="No documents found. Switch to Add Content to upload files."
                                />
                            )
                        )}

                        {/* Add Content tab */}
                        {activeTab === 'upload' && (
                            <div className="bg-(--theme-card) rounded-xl border border-(--theme-muted)/10 p-6">
                                <div className="flex items-center gap-2 mb-4">
                                    <LuUpload className="w-4 h-4 text-primary" />
                                    <Typography.Heading level={3} className="text-base">
                                        {selectedKb ? `Add Content to "${selectedKb.name}"` : 'Add Global Content'}
                                    </Typography.Heading>
                                </div>
                                {!selectedBaseId && (
                                    <div className="bg-warning/10 border border-warning/20 rounded-lg px-4 py-3 mb-4 text-sm text-warning flex items-center gap-2">
                                        <LuChevronRightIcon className="w-4 h-4 shrink-0" />
                                        Tip: select a collection on the left to organise content into a named group.
                                    </div>
                                )}
                                <ContentIngester
                                    knowledgeBaseId={selectedBaseId}
                                    onIngested={handleIngested}
                                />
                            </div>
                        )}

                        {/* Agents tab */}
                        {activeTab === 'agents' && selectedBaseId && selectedKb && (
                            <AssignedAgentsPanel
                                kbId={selectedBaseId}
                                kbName={selectedKb.name}
                            />
                        )}

                        {/* RAG Sandbox tab */}
                        {activeTab === 'rag' && <RagSandbox />}

                    </div>
                </div>
            </div>

            {/* ── Edit KB Modal ── */}
            {editingKb && (
                <EditKbModal
                    nameDraft={editNameDraft}
                    descDraft={editDescDraft}
                    saving={savingKbEdit}
                    onNameChange={setEditNameDraft}
                    onDescChange={setEditDescDraft}
                    onCancel={() => setEditingKb(null)}
                    onCommit={handleCommitEdit}
                />
            )}

            {/* ── Move Document Modal ── */}
            {movingDoc && (
                <MoveDocumentModal
                    doc={movingDoc}
                    knowledgeBases={knowledgeBases}
                    targetKbId={moveTargetKbId}
                    inProgress={movingInProgress}
                    onTargetChange={setMoveTargetKbId}
                    onCancel={() => setMovingDoc(null)}
                    onConfirm={handleConfirmMove}
                />
            )}

            {/* ── KB Delete Confirm Dialog ── */}
            {deletingKb && (
                <DeleteKbDialog
                    kb={deletingKb}
                    agents={deleteKbAgents}
                    loading={deleteKbLoading}
                    onCancel={() => { setDeletingKb(null); setDeleteKbAgents([]); }}
                    onConfirm={handleConfirmDeleteKb}
                />
            )}

            {/* ── Document Detail Modal ── */}
            {selectedDoc && (
                <DocumentDetailModal
                    doc={selectedDoc}
                    preview={preview}
                    previewLoading={previewLoading}
                    previewError={previewError}
                    viewMode={viewMode}
                    chunkDetails={chunkDetails}
                    loadingChunks={loadingChunks}
                    onViewRawData={handleViewRawData}
                    onBackToMetadata={() => setViewMode('metadata')}
                    onClose={handleCloseModal}
                />
            )}
        </PageContainer>
    );
};
