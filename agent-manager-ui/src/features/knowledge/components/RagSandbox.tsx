import React, { useState } from 'react';
import { KnowledgeApi } from '../api/knowledge-api';
import { logger } from '../../../utils/logger';
import { Button } from '../../../shared/components/ui/Button';

function highlightTerms(text: string, query: string): React.ReactNode[] {
  const terms = query.trim().split(/\s+/).filter(Boolean);
  if (!terms.length || !text) return [text];
  const pattern = new RegExp(`(${terms.map(t => t.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|')})`, 'gi');
  return text.split(pattern).map((part, i) =>
    pattern.test(part)
      ? <mark key={i} className="bg-warning/30 text-(--theme-foreground) rounded px-0.5">{part}</mark>
      : part
  );
}

export const RagSandbox: React.FC = () => {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<any[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [showNotFound, setShowNotFound] = useState(false);

  const handleSearch = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!query.trim()) return;

    setLoading(true);
    setError(null);
    try {
      const data = await KnowledgeApi.search(query);
      setResults(data);
      if (data.length === 0) {
        setShowNotFound(true);
        setTimeout(() => setShowNotFound(false), 3000); // Auto-hide after 3s
      }
    } catch (err: any) {
      logger.error('Failed to search knowledge base', err);
      setError('Failed to execute semantic search.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-lg p-6 space-y-4">
      <div className="flex items-center justify-between">
        <h2 className="text-lg font-bold">Semantic Search Sandbox</h2>
        <span className="text-xs font-mono bg-info/10 text-info px-2 py-1 rounded">RAG Diagnostic</span>
      </div>
      
      <p className="text-sm opacity-70">
        Test queries directly against the PGVector database. This bypassed the Agent LLM layer to show exactly which chunks are retrieved based on vector similarity.
      </p>

      <div className="relative">
        {/* Postioned right above the input field */}
        {showNotFound && (
          <div className="absolute bottom-full left-1/2 -translate-x-1/2 mb-2 z-50 transition-opacity duration-300">
            <div className="alert alert-warning shadow-lg text-sm px-4 py-2 whitespace-nowrap">
              <span>No matching vectors found for your query.</span>
            </div>
          </div>
        )}
        <form onSubmit={handleSearch} className="flex gap-2">
          <input 
            type="text" 
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Ask a question or enter a concept to search..." 
            className="input input-bordered w-full bg-(--theme-bg)"
            disabled={loading}
          />
          <Button type="submit" disabled={loading || !query.trim()} variant="primary">
            {loading ? 'Searching...' : 'Search Vectors'}
          </Button>
        </form>
      </div>

      {error && (
        <div className="alert alert-error text-sm">
          <span>{error}</span>
        </div>
      )}

      {results.length > 0 && (
        <div className="mt-6 space-y-4">
          <h3 className="font-medium text-sm border-b border-(--theme-muted)/10 pb-2">Retrieved Chunks ({results.length})</h3>
          <div className="grid gap-3 max-h-96 overflow-y-auto pr-2">
            {results.map((doc, idx) => (
              <div key={doc.id || idx} className="bg-(--theme-bg) p-4 rounded border border-(--theme-muted)/10 text-sm">
                <div className="flex justify-between items-start mb-2 opacity-50 text-xs font-mono">
                  <span>Chunk ID: {doc.id}</span>
                  {doc.metadata?.distance && <span>Distance: {Number(doc.metadata.distance).toFixed(4)}</span>}
                </div>
                <p className="whitespace-pre-wrap leading-relaxed">
                  {highlightTerms(doc.text ?? '', query)}
                </p>
                <div className="mt-3 pt-2 border-t border-(--theme-muted)/5 overflow-x-auto">
                    <pre className="text-[10px] font-mono opacity-50">
                        {JSON.stringify(doc.metadata, null, 2)}
                    </pre>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

    </div>
  );
};
