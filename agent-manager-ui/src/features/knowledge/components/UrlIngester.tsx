import React, { useState } from 'react';
import { KnowledgeApi } from '../api/knowledge-api';

interface UrlIngesterProps {
  knowledgeBaseId?: string;
  onIngested?: () => void;
}

export const UrlIngester: React.FC<UrlIngesterProps> = ({ knowledgeBaseId, onIngested }) => {
  const [url, setUrl] = useState('');
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [duplicate, setDuplicate] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!url.trim()) return;
    setIsLoading(true);
    setError(null);
    setDuplicate(false);
    try {
      await KnowledgeApi.ingestUrl(url.trim(), knowledgeBaseId);
      setUrl('');
      onIngested?.();
    } catch (err: any) {
      if (err?.status === 409 || err?.message?.includes('409') || err?.message?.toLowerCase().includes('already exists')) {
        setDuplicate(true);
      } else {
        setError(err?.message ?? 'Failed to start URL ingestion');
      }
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <div className="border-2 border-dashed rounded-xl p-8 flex flex-col gap-4 border-obsidian-stroke">
      <div className="flex flex-col gap-1 text-center">
        <p className="font-medium">Ingest from URL</p>
        <p className="text-sm text-muted opacity-60">Paste a public web page URL to scrape and vectorize its content</p>
      </div>
      <form onSubmit={handleSubmit} className="flex gap-2">
        <input
          type="url"
          value={url}
          onChange={e => { setUrl(e.target.value); setError(null); setDuplicate(false); }}
          placeholder="https://example.com/article"
          disabled={isLoading}
          className="flex-1 input input-bordered input-sm bg-(--theme-card) border-obsidian-stroke"
          required
        />
        <button
          type="submit"
          disabled={isLoading || !url.trim()}
          className="btn btn-primary btn-sm"
        >
          {isLoading ? <span className="loading loading-spinner loading-xs" /> : 'Ingest'}
        </button>
      </form>
      {duplicate && (
        <p className="text-xs text-warning text-center">This URL has already been ingested.</p>
      )}
      {error && (
        <p className="text-xs text-error text-center">{error}</p>
      )}
      {isLoading && (
        <p className="text-xs text-muted opacity-60 animate-pulse text-center">
          Starting ingestion — scraping and vectorizing in the background.
        </p>
      )}
    </div>
  );
};
