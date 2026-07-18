import React, { useState } from 'react';
import { DocumentUploader } from './DocumentUploader';
import { UrlIngester } from './UrlIngester';

type Tab = 'file' | 'url';

interface ContentIngesterProps {
  knowledgeBaseId?: string;
  onIngested?: () => void;
}

export const ContentIngester: React.FC<ContentIngesterProps> = ({
  knowledgeBaseId,
  onIngested,
}) => {
  const [tab, setTab] = useState<Tab>('file');

  return (
    <div className="flex flex-col gap-3">
      <div className="flex gap-2">
        <button
          className={`btn btn-xs ${tab === 'file' ? 'btn-primary' : 'btn-ghost opacity-60'}`}
          onClick={() => setTab('file')}
        >
          File
        </button>
        <button
          className={`btn btn-xs ${tab === 'url' ? 'btn-primary' : 'btn-ghost opacity-60'}`}
          onClick={() => setTab('url')}
        >
          URL
        </button>
      </div>
      {tab === 'file' ? (
        <DocumentUploader
          knowledgeBaseId={knowledgeBaseId}
          onComplete={onIngested}
        />
      ) : (
        <UrlIngester
          knowledgeBaseId={knowledgeBaseId}
          onIngested={onIngested}
        />
      )}
    </div>
  );
};
