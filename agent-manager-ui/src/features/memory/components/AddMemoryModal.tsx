import React, { useState } from 'react';
import { memoryApi } from '../api/memoryApi';
import { MarkdownEditor } from '../../../shared/components/ui/MarkdownEditor';
import { Dialog } from '../../../shared/components/ui/Dialog';
import { useUnsavedChangesGuard } from '../../../shared/hooks/useUnsavedChangesGuard';

interface AddMemoryModalProps {
    isOpen: boolean;
    onClose: () => void;
    onSuccess: () => void;
}

export const AddMemoryModal: React.FC<AddMemoryModalProps> = ({ isOpen, onClose, onSuccess }) => {
    const [content, setContent] = useState('');
    const [isSubmitting, setIsSubmitting] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const { markDirty, resetDirty, guardedClose, showConfirm, confirmLeave, cancelLeave } = useUnsavedChangesGuard(onClose, isOpen);

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        if (!content.trim()) return;

        try {
            setIsSubmitting(true);
            setError(null);
            await memoryApi.addMemory(content);
            setContent('');
            onSuccess();
            resetDirty();
            onClose();
        } catch (err: any) {
            setError(err.message || 'Failed to add memory');
        } finally {
            setIsSubmitting(false);
        }
    };

    if (!isOpen) return null;

    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 backdrop-blur-sm animate-in fade-in duration-200">
            <div className="bg-base-100 w-full max-w-lg rounded-xl shadow-2xl p-6 relative animate-in zoom-in-95 duration-200">
                <button
                    onClick={guardedClose}
                    className="btn btn-sm btn-circle btn-ghost absolute right-4 top-4"
                >
                    ✕
                </button>

                <h3 className="font-bold text-lg mb-6">Inject Agent Memory</h3>

                {error && (
                    <div className="alert alert-error mb-4">
                        <span>{error}</span>
                    </div>
                )}

                <form onSubmit={handleSubmit} className="space-y-4">
                    <MarkdownEditor
                        label="Memory Content"
                        description="This will be vectorized and stored in the pgvector database."
                        value={content}
                        onValueChange={(val) => { setContent(val || ''); markDirty(); }}
                        height="300px"
                    />

                    <div className="modal-action">
                        <button type="button" className="btn btn-ghost" onClick={guardedClose} disabled={isSubmitting}>
                            Cancel
                        </button>
                        <button type="submit" className="btn btn-primary" disabled={isSubmitting || !content.trim()}>
                            {isSubmitting ? <span className="loading loading-spinner loading-sm"></span> : 'Inject Memory'}
                        </button>
                    </div>
                </form>
            </div>

            <Dialog
                isOpen={showConfirm}
                setIsOpen={(open) => { if (!open) cancelLeave(); }}
                title="Unsaved Changes"
                content="You have unsaved changes. Are you sure you want to leave? Changes will be lost."
                severity="warning"
                confirmLabel="Leave"
                cancelLabel="Stay"
                onConfirm={confirmLeave}
                onCancel={cancelLeave}
            />
        </div>
    );
};
