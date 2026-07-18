import React, { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { memoryApi } from '../api/memoryApi';
import { UserAdminApi } from '../../users/api/userAdminApi';
import { SearchableSelect } from '../../../shared/components/ui/SearchableSelect';

/**
 * Right-to-be-Forgotten (RTBF) compliance panel.
 * Provides a destructive-action interface for purging all semantic memory
 * and vector embeddings associated with a specific User ID.
 * EU AI Act compliance requirement.
 */
export const RtbfWipePanel: React.FC = () => {
    const [userId, setUserId] = useState('');
    const [confirming, setConfirming] = useState(false);
    const [wiping, setWiping] = useState(false);
    const [result, setResult] = useState<{ success: boolean; message: string } | null>(null);

    const { data: usersPage, isLoading: loadingUsers } = useQuery({
        queryKey: ['users', 'list'],
        queryFn: () => UserAdminApi.listUsers(0, 200),
        staleTime: 120_000,
    });
    const users = usersPage?.content ?? [];
    const userOptions = users.map(u => ({ value: u.id, label: u.username, sublabel: u.email }));
    const selectedUser = users.find(u => u.id === userId);

    const handleInitiateWipe = () => {
        if (!userId.trim()) return;
        setConfirming(true);
        setResult(null);
    };

    const handleConfirmWipe = async () => {
        setWiping(true);
        setResult(null);
        try {
            await memoryApi.rtbfWipe(userId.trim());
            setResult({
                success: true,
                message: `All memory records and vector embeddings for user "${selectedUser?.username ?? userId}" have been permanently deleted.`
            });
            setUserId('');
            setConfirming(false);
        } catch (err: any) {
            setResult({
                success: false,
                message: err.message || 'RTBF wipe operation failed. Please check server logs.'
            });
        } finally {
            setWiping(false);
        }
    };

    const handleCancel = () => {
        setConfirming(false);
    };

    return (
        <div className="card bg-obsidian-surface shadow-sm border border-error/30">
            <div className="card-body">
                <div className="flex items-center gap-2 mb-2">
                    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20" fill="currentColor" className="w-5 h-5 text-error">
                        <path fillRule="evenodd" d="M8.75 1A2.75 2.75 0 006 3.75v.443c-.795.077-1.584.176-2.365.298a.75.75 0 10.23 1.482l.149-.022.841 10.518A2.75 2.75 0 007.596 19h4.807a2.75 2.75 0 002.742-2.53l.841-10.519.149.023a.75.75 0 00.23-1.482A41.03 41.03 0 0014 4.193V3.75A2.75 2.75 0 0011.25 1h-2.5zM10 4c.84 0 1.673.025 2.5.075V3.75c0-.69-.56-1.25-1.25-1.25h-2.5c-.69 0-1.25.56-1.25 1.25v.325C8.327 4.025 9.16 4 10 4zM8.58 7.72a.75.75 0 00-1.5.06l.3 7.5a.75.75 0 101.5-.06l-.3-7.5zm4.34.06a.75.75 0 10-1.5-.06l-.3 7.5a.75.75 0 101.5.06l.3-7.5z" clipRule="evenodd" />
                    </svg>
                    <h2 className="card-title text-error text-lg">Right to be Forgotten (RTBF)</h2>
                </div>
                <p className="text-sm text-theme-muted mb-4">
                    Permanently erase all semantic memory records, vector embeddings, and extracted knowledge tuples for a specific user.
                    This operation is <strong className="text-error">irreversible</strong> and is required for EU AI Act / GDPR compliance.
                </p>

                {!confirming ? (
                    <div className="flex flex-col gap-3">
                        <SearchableSelect
                            label="User to Erase"
                            value={userId}
                            onChange={setUserId}
                            options={userOptions}
                            loading={loadingUsers}
                            placeholder="Search by username or email…"
                            emptyMessage="No users found"
                        />
                        <div className="flex justify-end">
                            <button
                                className="btn btn-sm btn-error"
                                disabled={!userId}
                                onClick={handleInitiateWipe}
                            >
                                Erase All Data
                            </button>
                        </div>
                    </div>
                ) : (
                    <div className="bg-error/10 border border-error/30 rounded-lg p-4 space-y-3">
                        <p className="text-sm font-bold text-error">⚠️ Confirm Permanent Deletion</p>
                        <p className="text-xs text-base-content/70">
                            This will permanently delete <strong>ALL</strong> memory records and vector embeddings for
                            <code className="bg-base-300 px-1.5 py-0.5 rounded mx-1 text-error font-bold">
                                {selectedUser ? `${selectedUser.username} (${selectedUser.email})` : userId}
                            </code>.
                            This action <strong>cannot be undone</strong>.
                        </p>
                        <div className="flex gap-2 justify-end">
                            <button className="btn btn-sm btn-ghost" onClick={handleCancel} disabled={wiping}>
                                Cancel
                            </button>
                            <button className="btn btn-sm btn-error" onClick={handleConfirmWipe} disabled={wiping}>
                                {wiping ? (
                                    <span className="loading loading-spinner loading-xs"></span>
                                ) : (
                                    'Yes, Permanently Delete'
                                )}
                            </button>
                        </div>
                    </div>
                )}

                {/* Result notification */}
                {result && (
                    <div className={`alert ${result.success ? 'alert-success' : 'alert-error'} mt-4 text-sm`}>
                        <span>{result.message}</span>
                    </div>
                )}
            </div>
        </div>
    );
};
