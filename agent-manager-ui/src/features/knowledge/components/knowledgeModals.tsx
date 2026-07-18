import React from 'react';
import type { KnowledgeDocument, KnowledgeBase } from '../../../shared/types/api';
import { Button } from '../../../shared/components/ui/Button';
import { useEscapeToClose } from '../../../shared/hooks/useEscapeToClose';

/**
 * The three small knowledge-base management dialogs, extracted from KnowledgePage.
 * All controlled — draft/loading state stays in the page and is passed down — so
 * extraction is byte-identical in behavior. The page still gates each on its
 * trigger state (editingKb / movingDoc / deletingKb).
 */

interface EditKbModalProps {
    nameDraft: string;
    descDraft: string;
    saving: boolean;
    onNameChange: (v: string) => void;
    onDescChange: (v: string) => void;
    onCancel: () => void;
    onCommit: () => void;
}

export const EditKbModal: React.FC<EditKbModalProps> = ({
    nameDraft, descDraft, saving, onNameChange, onDescChange, onCancel, onCommit,
}) => {
    useEscapeToClose(onCancel);
    return (
    <div className="modal modal-open">
        <div className="modal-box bg-(--theme-card) border border-(--theme-muted)/10">
            <h3 className="font-bold text-lg mb-4">Edit Collection</h3>
            <div className="space-y-3">
                <div className="space-y-1">
                    <label className="text-xs font-medium text-(--theme-muted) uppercase tracking-wider">Name</label>
                    <input
                        className="input input-bordered w-full"
                        value={nameDraft}
                        onChange={e => onNameChange(e.target.value)}
                        onKeyDown={e => e.key === 'Enter' && onCommit()}
                        autoFocus
                    />
                </div>
                <div className="space-y-1">
                    <label className="text-xs font-medium text-(--theme-muted) uppercase tracking-wider">Description</label>
                    <textarea
                        className="textarea textarea-bordered w-full resize-none h-20"
                        placeholder="Describe what documents belong in this collection…"
                        value={descDraft}
                        onChange={e => onDescChange(e.target.value)}
                    />
                </div>
            </div>
            <div className="modal-action">
                <Button variant="ghost" onClick={onCancel} disabled={saving}>Cancel</Button>
                <Button variant="primary" onClick={onCommit} disabled={!nameDraft.trim() || saving}>
                    {saving ? <span className="loading loading-spinner loading-xs" /> : 'Save'}
                </Button>
            </div>
        </div>
        <div className="modal-backdrop" onClick={onCancel} />
    </div>
    );
};

interface MoveDocumentModalProps {
    doc: KnowledgeDocument;
    knowledgeBases: KnowledgeBase[];
    targetKbId: string;
    inProgress: boolean;
    onTargetChange: (v: string) => void;
    onCancel: () => void;
    onConfirm: () => void;
}

export const MoveDocumentModal: React.FC<MoveDocumentModalProps> = ({
    doc, knowledgeBases, targetKbId, inProgress, onTargetChange, onCancel, onConfirm,
}) => {
    useEscapeToClose(onCancel);
    return (
    <div className="modal modal-open">
        <div className="modal-box bg-(--theme-card) border border-(--theme-muted)/10">
            <h3 className="font-bold text-lg mb-2">Move &quot;{doc.name}&quot;</h3>
            <p className="text-sm text-(--theme-muted) mb-4">
                Select a knowledge base to move this document to. Vector chunks will be re-tagged — no re-embedding required.
            </p>
            <select
                className="select select-bordered w-full"
                value={targetKbId}
                onChange={e => onTargetChange(e.target.value)}
            >
                <option value="">— Select a collection —</option>
                {knowledgeBases
                    .filter(kb => kb.id !== doc.knowledgeBaseId)
                    .map(kb => <option key={kb.id} value={kb.id}>{kb.name}</option>)
                }
            </select>
            <div className="modal-action">
                <Button variant="ghost" onClick={onCancel} disabled={inProgress}>Cancel</Button>
                <Button variant="primary" onClick={onConfirm} disabled={!targetKbId || inProgress}>
                    {inProgress ? <span className="loading loading-spinner loading-xs" /> : 'Move'}
                </Button>
            </div>
        </div>
        <div className="modal-backdrop" onClick={onCancel} />
    </div>
    );
};

interface DeleteKbDialogProps {
    kb: KnowledgeBase;
    agents: string[];
    loading: boolean;
    onCancel: () => void;
    onConfirm: () => void;
}

export const DeleteKbDialog: React.FC<DeleteKbDialogProps> = ({
    kb, agents, loading, onCancel, onConfirm,
}) => {
    useEscapeToClose(onCancel);
    return (
    <div className="modal modal-open">
        <div className="modal-box bg-(--theme-card) border border-(--theme-muted)/10">
            <h3 className="font-bold text-lg mb-2">Delete &quot;{kb.name}&quot;?</h3>
            <p className="text-sm text-(--theme-muted) mb-4">
                This will permanently delete the collection and all its documents and vector chunks.
            </p>
            {loading ? (
                <div className="flex justify-center py-4"><span className="loading loading-spinner" /></div>
            ) : agents.length > 0 && (
                <div className="bg-warning/10 border border-warning/30 rounded-lg p-3 mb-4 text-sm">
                    <p className="font-medium text-warning mb-1">Used by {agents.length} agent{agents.length > 1 ? 's' : ''}:</p>
                    <ul className="list-disc list-inside opacity-80 space-y-0.5">
                        {agents.map(name => <li key={name}>{name}</li>)}
                    </ul>
                </div>
            )}
            <div className="modal-action">
                <Button variant="ghost" onClick={onCancel}>Cancel</Button>
                <Button variant="primary" className="bg-error border-error hover:bg-error/80" onClick={onConfirm}>
                    Delete
                </Button>
            </div>
        </div>
        <div className="modal-backdrop" onClick={onCancel} />
    </div>
    );
};
