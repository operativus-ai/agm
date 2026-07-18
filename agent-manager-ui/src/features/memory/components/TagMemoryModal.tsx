import React, { useState } from 'react';
import { Button } from '../../../shared/components/ui/Button';
import { TagInput } from '../../../shared/components/ui/TagInput';
import { LuX } from 'react-icons/lu';
import { memoryApi } from '../api/memoryApi';
import type { MemoryEntry } from '../api/memoryApi';

interface TagMemoryModalProps {
    memory: MemoryEntry;
    onClose: () => void;
    onSaved: () => void;
}

/**
 * Edit-tags modal for a single memory entry. Extracted from MemoryManagerPage
 * to keep that page a thin assembler. Self-contained: owns its tag-edit state
 * and persists via memoryApi.tagMemory.
 */
export const TagMemoryModal: React.FC<TagMemoryModalProps> = ({ memory, onClose, onSaved }) => {
    const [tags, setTags] = useState<string[]>(memory.topics ?? []);
    const [saving, setSaving] = useState(false);
    const [error, setError] = useState<string | null>(null);

    const handleSave = async () => {
        setSaving(true);
        setError(null);
        try {
            await memoryApi.tagMemory(memory.id, tags);
            onSaved();
            onClose();
        } catch (err: any) {
            setError(err.message || 'Failed to save tags');
        } finally {
            setSaving(false);
        }
    };

    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 backdrop-blur-sm">
            <div className="bg-base-100 w-full max-w-md rounded-xl shadow-2xl p-6 relative">
                <button onClick={onClose} className="btn btn-sm btn-circle btn-ghost absolute right-4 top-4">
                    <LuX className="w-4 h-4" />
                </button>
                <h3 className="font-bold text-lg mb-1">Edit Memory Tags</h3>
                <p className="text-xs text-(--theme-muted) mb-4 line-clamp-2">{memory.memory}</p>

                {error && <div className="alert alert-error text-sm mb-4"><span>{error}</span></div>}

                <TagInput
                    label="Topics"
                    description="Press Enter or comma to add a tag."
                    value={tags}
                    onChange={setTags}
                />

                <div className="flex justify-end gap-2 mt-4">
                    <Button variant="ghost" onClick={onClose} disabled={saving}>Cancel</Button>
                    <Button onClick={handleSave} disabled={saving}>
                        {saving ? <span className="loading loading-spinner loading-sm" /> : 'Save Tags'}
                    </Button>
                </div>
            </div>
        </div>
    );
};
