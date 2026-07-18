import React, { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { PageHeader } from '../../../shared/components/ui/PageHeader';
import { SecurityAuditLog } from '../components/SecurityAuditLog';
import { AgentSandboxViewer } from '../components/AgentSandboxViewer';
import { PiiPolicyManager } from '../components/PiiPolicyManager';
import { PiiAuditLogViewer } from '../components/PiiAuditLogViewer';
import { ErasureRequestsAuditPanel } from '../components/ErasureRequestsAuditPanel';
import { Tabs } from '../../../shared/components/ui/Tabs';
import { Button } from '../../../shared/components/ui/Button';
import { Alert } from '../../../shared/components/ui/Alert';
import { SearchableSelect } from '../../../shared/components/ui/SearchableSelect';
import { LuShield, LuDownload, LuTrash2 } from 'react-icons/lu';
import { PageContainer } from '../../../shared/components/ui/PageContainer';
import { complianceApi } from '../api/complianceApi';
import { UserAdminApi } from '../../users/api/userAdminApi';

const GdprPanel: React.FC = () => {
    const [userId, setUserId] = useState('');
    const [status, setStatus] = useState<{ type: 'success' | 'error'; message: string } | null>(null);
    const [loading, setLoading] = useState<'export' | 'erase' | null>(null);
    const [syncErase, setSyncErase] = useState(false);

    const { data: usersPage, isLoading: loadingUsers } = useQuery({
        queryKey: ['users', 'list'],
        queryFn: () => UserAdminApi.listUsers(0, 200),
        staleTime: 120_000,
    });
    const users = usersPage?.content ?? [];
    const userOptions = users.map(u => ({ value: u.id, label: u.username, sublabel: u.email }));
    const selectedUser = users.find(u => u.id === userId);
    const userLabel = selectedUser ? `${selectedUser.username} (${selectedUser.email})` : userId;

    const handleExport = async () => {
        if (!userId) return;
        setLoading('export');
        setStatus(null);
        try {
            await complianceApi.exportUserData(userId);
            setStatus({ type: 'success', message: `Data export initiated for ${userLabel}.` });
        } catch (err: any) {
            setStatus({ type: 'error', message: err.message || 'Export failed.' });
        } finally {
            setLoading(null);
        }
    };

    const handleErase = async () => {
        if (!userId) return;
        const promptSuffix = syncErase
            ? ' This bypasses the queue and blocks until completion.'
            : '';
        if (!confirm(`Permanently erase all data for "${userLabel}"? This cannot be undone.${promptSuffix}`)) return;
        setLoading('erase');
        setStatus(null);
        try {
            if (syncErase) {
                await complianceApi.eraseUserData(userId);
                setStatus({ type: 'success', message: `Sync erase completed for ${userLabel}.` });
            } else {
                const { jobId } = await complianceApi.submitErasureRequest(userId);
                setStatus({ type: 'success', message: `Erasure request queued for ${userLabel} (job: ${jobId}). Processing in background.` });
            }
            setUserId('');
        } catch (err: any) {
            setStatus({ type: 'error', message: err.message || 'Erasure failed.' });
        } finally {
            setLoading(null);
        }
    };

    return (
        <div className="max-w-xl space-y-6 py-2">
            <p className="text-sm text-(--theme-muted)">
                Process GDPR data subject requests — export or permanently erase all personal data tied to a user.
            </p>

            {status && (
                <Alert severity={status.type === 'success' ? 'success' : 'error'}>
                    {status.message}
                </Alert>
            )}

            <SearchableSelect
                label="User"
                value={userId}
                onChange={setUserId}
                options={userOptions}
                loading={loadingUsers}
                placeholder="Search by username or email…"
                emptyMessage="No users found"
            />

            <div className="flex gap-3">
                <Button
                    variant="outline"
                    size="sm"
                    className="gap-1.5"
                    disabled={!userId || loading !== null}
                    onClick={handleExport}
                >
                    {loading === 'export' ? <span className="loading loading-spinner loading-xs" /> : <LuDownload className="w-4 h-4" />}
                    Export Data
                </Button>
                <Button
                    variant="ghost"
                    size="sm"
                    className="gap-1.5 text-error hover:bg-error/10"
                    disabled={!userId || loading !== null}
                    onClick={handleErase}
                >
                    {loading === 'erase' ? <span className="loading loading-spinner loading-xs" /> : <LuTrash2 className="w-4 h-4" />}
                    {syncErase ? 'Erase Now (Sync)' : 'Erase Data (Right to be Forgotten)'}
                </Button>
            </div>

            <label className="flex items-center gap-2 text-xs text-(--theme-muted) cursor-pointer select-none">
                <input
                    type="checkbox"
                    className="checkbox checkbox-xs"
                    checked={syncErase}
                    onChange={(e) => setSyncErase(e.target.checked)}
                />
                <span>
                    Force sync erase (admin) &mdash; bypasses the queue and blocks until completion. Slower; use only when you need confirmed completion before continuing.
                </span>
            </label>
        </div>
    );
};

export const SecurityPage: React.FC = () => {
    return (
        <PageContainer variant="dashboard">
            <PageHeader
                icon={LuShield}
                title="Security & Compliance"
                subtitle="NIST CSF 2.0 Compliant Dashboards. Monitor prompt injections, PII governance, and virtual thread boundaries."
            />

            <Tabs defaultValue="threats">
                <Tabs.List>
                    <Tabs.Trigger value="threats">Threat Detection</Tabs.Trigger>
                    <Tabs.Trigger value="privacy">Data Privacy (PII)</Tabs.Trigger>
                    <Tabs.Trigger value="gdpr">GDPR Compliance</Tabs.Trigger>
                </Tabs.List>

                <Tabs.Content value="threats">
                    <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
                        <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl overflow-y-auto max-h-150">
                            <SecurityAuditLog />
                        </div>
                        <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl overflow-y-auto max-h-150">
                            <AgentSandboxViewer />
                        </div>
                    </div>
                </Tabs.Content>

                <Tabs.Content value="privacy">
                    <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
                        <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl overflow-y-auto max-h-150">
                            <PiiPolicyManager />
                        </div>
                        <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl overflow-y-auto max-h-150">
                            <PiiAuditLogViewer />
                        </div>
                    </div>
                </Tabs.Content>

                <Tabs.Content value="gdpr">
                    <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
                        <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl p-6">
                            <GdprPanel />
                        </div>
                        <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl p-6">
                            <ErasureRequestsAuditPanel />
                        </div>
                    </div>
                </Tabs.Content>
            </Tabs>
        </PageContainer>
    );
};
