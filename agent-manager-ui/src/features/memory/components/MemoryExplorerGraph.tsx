import React from 'react';

export const MemoryExplorerGraph: React.FC = () => {
    return (
        <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl p-6 flex flex-col items-center justify-center min-h-50 text-center">
            <div className="text-(--theme-muted) text-sm font-medium mb-1">Semantic Knowledge Graph</div>
            <div className="text-xs text-(--theme-muted)/60">
                Relation extraction not yet implemented. Run <strong>Optimize</strong> to consolidate memories first.
            </div>
        </div>
    );
};
