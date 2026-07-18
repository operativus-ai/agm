import React, { useEffect, useState } from 'react';
import { PageHeader } from '../../../shared/components/ui/PageHeader';
import { Button } from '../../../shared/components/ui/Button';
import { Alert } from '../../../shared/components/ui/Alert';
import { registryApi } from '../api/registryApi';
import { logger } from '../../../utils/logger';
import type { CodeRegistryResponse } from '../api/registryApi';
import { LuDatabase } from 'react-icons/lu';
import { PageContainer } from '../../../shared/components/ui/PageContainer';

export const RegistryPage: React.FC = () => {
    const [codeRegistry, setCodeRegistry] = useState<CodeRegistryResponse | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        loadData();
    }, []);

    const loadData = async () => {
        try {
            setLoading(true);
            const [regData] = await Promise.all([
                registryApi.getCodeRegistry().catch(() => null)
            ]);

            setCodeRegistry(regData);

            setError(null);
        } catch (err: any) {
            logger.error("Failed to load registries", err);
            setError(err.message || 'Failed to load system registries');
        } finally {
            setLoading(false);
        }
    };

    const renderJsonTree = (data: any) => {
        if (!data) return <div className="text-(--theme-muted) italic">None registered</div>;
        return (
            <pre className="text-xs font-mono bg-obsidian-elevated p-4 rounded-lg overflow-x-auto whitespace-pre-wrap break-all">
                {JSON.stringify(data, null, 2)}
            </pre>
        );
    };

    return (
        <PageContainer variant="dashboard">
            {/* Header */}
            <PageHeader
                icon={LuDatabase}
                title="System Registries"
                subtitle="Inspect the hardcoded (Code) agents, teams, and tools registered at startup."
                actions={
                    <Button variant="outline" size="sm" onClick={loadData} disabled={loading}>
                        {loading ? <span className="loading loading-spinner loading-sm"></span> : 'Refresh'}
                    </Button>
                }
            />

            {error && (
                <Alert severity="error" title="Error">{error}</Alert>
            )}

            {loading ? (
                <div className="flex justify-center items-center h-64">
                    <span className="loading loading-spinner text-primary loading-lg"></span>
                </div>
            ) : (
                <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
                    <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl overflow-hidden">
                        <div className="px-5 py-4 border-b border-(--theme-muted)/10">
                            <h2 className="font-semibold text-primary">Registered Agents</h2>
                        </div>
                        <div className="p-5 text-sm max-h-96 overflow-y-auto">
                            {renderJsonTree(codeRegistry?.agents)}
                        </div>
                    </div>

                    <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl overflow-hidden">
                        <div className="px-5 py-4 border-b border-(--theme-muted)/10">
                            <h2 className="font-semibold text-secondary">Registered Teams</h2>
                        </div>
                        <div className="p-5 text-sm max-h-96 overflow-y-auto">
                            {renderJsonTree(codeRegistry?.teams)}
                        </div>
                    </div>

                    <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl overflow-hidden md:col-span-2">
                        <div className="px-5 py-4 border-b border-(--theme-muted)/10">
                            <h2 className="font-semibold text-accent">Registered Tools</h2>
                        </div>
                        <div className="p-5 text-sm max-h-96 overflow-y-auto">
                            {renderJsonTree(codeRegistry?.tools)}
                        </div>
                    </div>
                </div>
            )}
        </PageContainer>
    );
};
