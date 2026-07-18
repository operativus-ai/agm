import React, { useCallback, useEffect, useMemo, useState } from 'react';
import type { UserAdmin, UserCreateRequest, UserUpdateRequest } from '../../../shared/types/api';
import type { ColumnDef } from '@tanstack/react-table';
import { UserAdminApi } from '../api/userAdminApi';
import { UserFormModal } from '../components/UserFormModal';
import { BulkUserImportModal } from '../components/BulkUserImportModal';
import { PageHeader } from '../../../shared/components/ui/PageHeader';
import { Alert } from '../../../shared/components/ui/Alert';
import { Badge } from '../../../shared/components/ui/Badge';
import { Button } from '../../../shared/components/ui/Button';
import { DataTable } from '../../../shared/components/ui/DataTable';
import { PageContainer } from '../../../shared/components/ui/PageContainer';
import { useEscapeToClose } from '../../../shared/hooks/useEscapeToClose';
import { LuFileUp, LuUserPlus, LuPencil, LuTrash2, LuKeyRound } from 'react-icons/lu';
import type { PaginatedResponse } from '../../../shared/types/api';

export const UserManagementPage: React.FC = () => {
    const [usersPage, setUsersPage] = useState<PaginatedResponse<UserAdmin> | null>(null);
    const [, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [actionMessage, setActionMessage] = useState<{ text: string; type: 'success' | 'error' | 'info' | 'warning' } | null>(null);
    const [isModalOpen, setIsModalOpen] = useState(false);
    const [bulkOpen, setBulkOpen] = useState(false);
    const [selectedUser, setSelectedUser] = useState<UserAdmin | null>(null);
    const [resetTarget, setResetTarget] = useState<UserAdmin | null>(null);
    const [resetPassword, setResetPassword] = useState('');
    const [pageIndex, setPageIndex] = useState(0);
    const PAGE_SIZE = 20;

    const closeReset = useCallback(() => { setResetTarget(null); setResetPassword(''); }, []);
    useEscapeToClose(closeReset, !!resetTarget);

    const loadUsers = async () => {
        try {
            setLoading(true);
            const data = await UserAdminApi.listUsers(pageIndex, PAGE_SIZE);
            setUsersPage(data);
            setError(null);
        } catch (err) {
            setError(err instanceof Error ? err.message : 'Error loading users');
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => { loadUsers(); }, [pageIndex]);

    const showMessage = (text: string, type: 'success' | 'error' | 'info' | 'warning' = 'info') => {
        setActionMessage({ text, type });
        setTimeout(() => setActionMessage(null), 5000);
    };

    const handleSave = async (id: string | null, data: UserCreateRequest | UserUpdateRequest) => {
        if (id) {
            await UserAdminApi.updateUser(id, data as UserUpdateRequest);
            showMessage('User updated successfully', 'success');
        } else {
            await UserAdminApi.createUser(data as UserCreateRequest);
            showMessage('User created successfully', 'success');
        }
        await loadUsers();
    };

    const handleDelete = async (user: UserAdmin) => {
        if (!confirm(`Delete user "${user.username}"? This cannot be undone.`)) return;
        try {
            await UserAdminApi.deleteUser(user.id);
            showMessage('User deleted', 'success');
            await loadUsers();
        } catch (err) {
            showMessage(err instanceof Error ? err.message : 'Delete failed', 'error');
        }
    };

    const handleResetPassword = async () => {
        if (!resetTarget || !resetPassword) return;
        try {
            await UserAdminApi.resetPassword(resetTarget.id, resetPassword);
            showMessage(`Password reset for ${resetTarget.username}`, 'success');
            setResetTarget(null);
            setResetPassword('');
        } catch (err) {
            showMessage(err instanceof Error ? err.message : 'Reset failed', 'error');
        }
    };

    const columns = useMemo<ColumnDef<UserAdmin>[]>(() => [
        {
            accessorKey: 'username',
            header: 'Username',
            cell: ({ row }) => (
                <span className="font-mono text-sm text-theme-foreground">{row.original.username}</span>
            ),
        },
        {
            accessorKey: 'email',
            header: 'Email',
            cell: ({ row }) => <span className="text-sm text-theme-muted">{row.original.email}</span>,
        },
        {
            accessorKey: 'roles',
            header: 'Roles',
            cell: ({ row }) => (
                <div className="flex flex-wrap gap-1">
                    {row.original.roles.map(r => (
                        <Badge key={r} variant="info" size="sm">{r.replace('ROLE_', '')}</Badge>
                    ))}
                </div>
            ),
        },
        {
            accessorKey: 'disabled',
            header: 'Status',
            cell: ({ row }) => (
                <Badge variant={row.original.disabled ? 'error' : 'success'} size="sm">
                    {row.original.disabled ? 'Disabled' : 'Active'}
                </Badge>
            ),
        },
        {
            accessorKey: 'lastLoginAt',
            header: 'Last Login',
            cell: ({ row }) => (
                <span className="text-xs text-theme-muted">
                    {row.original.lastLoginAt ? new Date(row.original.lastLoginAt).toLocaleString() : 'Never'}
                </span>
            ),
        },
        {
            id: 'actions',
            header: 'Actions',
            cell: ({ row }) => (
                <div className="flex gap-2">
                    <button className="btn btn-xs btn-ghost text-agent-blue" title="Edit" onClick={() => { setSelectedUser(row.original); setIsModalOpen(true); }}>
                        <LuPencil size={14} />
                    </button>
                    <button className="btn btn-xs btn-ghost text-theme-muted" title="Reset Password" onClick={() => setResetTarget(row.original)}>
                        <LuKeyRound size={14} />
                    </button>
                    <button className="btn btn-xs btn-ghost text-agent-red" title="Delete" onClick={() => handleDelete(row.original)}>
                        <LuTrash2 size={14} />
                    </button>
                </div>
            ),
        },
    ], []);

    return (
        <PageContainer variant="dashboard">
            <PageHeader
                title="User Management"
                subtitle="Manage user accounts, roles, and access."
                actions={
                    <div className="flex items-center gap-2">
                        <Button variant="ghost" size="sm" className="gap-1.5" onClick={() => setBulkOpen(true)}>
                            <LuFileUp size={16} /> Bulk Import
                        </Button>
                        <Button variant="primary" size="sm" onClick={() => { setSelectedUser(null); setIsModalOpen(true); }}>
                            <LuUserPlus size={16} /> Create User
                        </Button>
                    </div>
                }
            />

            {actionMessage && <Alert severity={actionMessage.type} className="mb-4">{actionMessage.text}</Alert>}
            {error && <Alert severity="error" className="mb-4">{error}</Alert>}

            <DataTable
                columns={columns}
                data={usersPage?.content ?? []}
                manualPagination={true}
                pageIndex={pageIndex}
                pageSize={PAGE_SIZE}
                totalElements={usersPage?.page.totalElements ?? 0}
                onPageChange={setPageIndex}
            />

            <UserFormModal
                isOpen={isModalOpen}
                onClose={() => setIsModalOpen(false)}
                onSave={handleSave}
                user={selectedUser}
            />

            <BulkUserImportModal
                isOpen={bulkOpen}
                onClose={() => setBulkOpen(false)}
                onComplete={() => { void loadUsers(); }}
            />

            {resetTarget && (
                <div className="modal modal-open">
                    <div className="modal-box bg-obsidian-raised border border-obsidian-stroke max-w-sm">
                        <h3 className="font-bold text-lg mb-4">Reset Password — {resetTarget.username}</h3>
                        <input
                            type="password"
                            className="input input-bordered w-full bg-obsidian-surface border-obsidian-stroke mb-4"
                            placeholder="New password"
                            value={resetPassword}
                            onChange={e => setResetPassword(e.target.value)}
                        />
                        <div className="flex justify-end gap-2">
                            <button className="btn btn-ghost btn-sm" onClick={closeReset}>Cancel</button>
                            <button className="btn btn-primary btn-sm" onClick={handleResetPassword} disabled={!resetPassword}>Reset</button>
                        </div>
                    </div>
                    <div className="modal-backdrop" onClick={closeReset}></div>
                </div>
            )}
        </PageContainer>
    );
};
