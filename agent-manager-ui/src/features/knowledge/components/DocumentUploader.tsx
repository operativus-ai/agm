import React, { useCallback, useEffect, useRef, useState } from 'react';
import { LuFile } from 'react-icons/lu';
import { KnowledgeApi } from '../api/knowledge-api';
import { RunStatus } from '../../../shared/types/enums';

interface FileEntry {
  file: File;
  status: 'queued' | 'uploading' | 'processing' | 'completed' | 'failed';
  message?: string;
  documentId?: string;
}

interface DocumentUploaderProps {
  knowledgeBaseId?: string;
  onComplete?: () => void;
}

const UploadIcon = () => (
  <svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="17 8 12 3 7 8"/><line x1="12" x2="12" y1="3" y2="15"/></svg>
);

const statusColor = (status: FileEntry['status']) => {
  if (status === 'completed') return 'text-success';
  if (status === 'failed') return 'text-error';
  if (status === 'processing' || status === 'uploading') return 'text-info';
  return 'text-(--theme-muted)';
};

export const DocumentUploader: React.FC<DocumentUploaderProps> = ({ knowledgeBaseId, onComplete }) => {
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [dragActive, setDragActive] = useState(false);
  const [fileEntries, setFileEntries] = useState<FileEntry[]>([]);
  const [description, setDescription] = useState('');
  const sseRefs = useRef<Map<string, AbortController>>(new Map());

  useEffect(() => {
    return () => {
      sseRefs.current.forEach(ctrl => ctrl.abort());
    };
  }, []);

  const updateEntry = useCallback((file: File, update: Partial<FileEntry>) => {
    setFileEntries(prev => prev.map(e => e.file === file ? { ...e, ...update } : e));
  }, []);

  const subscribeToStatus = useCallback((file: File, docId: string) => {
    const es = KnowledgeApi.subscribeToIngestionStatus(
      docId,
      (status, message) => {
        if (status === RunStatus.COMPLETED) {
          updateEntry(file, { status: 'completed', message });
          sseRefs.current.delete(docId);
          onComplete?.();
        } else if (status === RunStatus.FAILED) {
          updateEntry(file, { status: 'failed', message: message || 'Ingestion failed' });
          sseRefs.current.delete(docId);
        }
      },
      () => {
        updateEntry(file, { status: 'failed', message: 'Connection lost' });
        sseRefs.current.delete(docId);
      }
    );
    sseRefs.current.set(docId, es);
  }, [updateEntry, onComplete]);

  const processFiles = useCallback(async (files: File[]) => {
    const newEntries: FileEntry[] = files.map(f => ({ file: f, status: 'queued' }));
    setFileEntries(prev => [...prev, ...newEntries]);

    newEntries.forEach(e => updateEntry(e.file, { status: 'uploading' }));

    try {
      const response = await KnowledgeApi.uploadBatch(files, knowledgeBaseId, description || undefined);

      const rejectedNames = new Set(
        response.rejected.map(r => { const i = r.indexOf(': '); return i >= 0 ? r.substring(0, i) : r; })
      );

      response.rejected.forEach(r => {
        const i = r.indexOf(': ');
        const name = i >= 0 ? r.substring(0, i) : r;
        const reason = i >= 0 ? r.substring(i + 2) : 'rejected';
        const entry = newEntries.find(e => e.file.name === name);
        if (entry) {
          const msg = reason.includes('already exists') || reason.includes('Duplicate') ? 'Duplicate — already ingested' : reason;
          updateEntry(entry.file, { status: 'failed', message: msg });
        }
      });

      const acceptedEntries = newEntries.filter(e => !rejectedNames.has(e.file.name));
      acceptedEntries.forEach((entry, i) => {
        const docId = response.accepted[i];
        if (!docId) return;
        updateEntry(entry.file, { status: 'processing', documentId: docId });
        subscribeToStatus(entry.file, docId);
      });
    } catch (err: any) {
      const message = err.message || 'Upload failed';
      newEntries.forEach(e => updateEntry(e.file, { status: 'failed', message }));
    }
  }, [knowledgeBaseId, description, updateEntry, subscribeToStatus]);

  const handleDrag = (e: React.DragEvent) => {
    e.preventDefault();
    e.stopPropagation();
    if (e.type === 'dragenter' || e.type === 'dragover') setDragActive(true);
    else if (e.type === 'dragleave') setDragActive(false);
  };

  const handleDrop = (e: React.DragEvent) => {
    e.preventDefault();
    e.stopPropagation();
    setDragActive(false);
    if (e.dataTransfer.files.length) processFiles(Array.from(e.dataTransfer.files));
  };

  const handleChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    e.preventDefault();
    if (e.target.files?.length) {
      processFiles(Array.from(e.target.files));
      if (fileInputRef.current) fileInputRef.current.value = '';
    }
  };

  const allDone = fileEntries.length > 0 && fileEntries.every(e => e.status === 'completed' || e.status === 'failed');

  return (
    <div className="space-y-3">
      <div className="space-y-1">
        <label className="text-xs font-medium text-(--theme-muted) uppercase tracking-wider">Description (optional)</label>
        <textarea
          className="textarea textarea-bordered w-full text-sm resize-none h-16"
          placeholder="Brief description of these documents…"
          value={description}
          onChange={e => setDescription(e.target.value)}
          onClick={e => e.stopPropagation()}
        />
      </div>

      <div
        className={`
          border-2 border-dashed rounded-xl p-8 text-center transition-colors cursor-pointer
          flex flex-col items-center justify-center gap-4
          ${dragActive ? 'border-primary bg-primary/5' : 'border-obsidian-stroke hover:border-primary/50 hover:bg-(--theme-card)'}
        `}
        onDragEnter={handleDrag}
        onDragLeave={handleDrag}
        onDragOver={handleDrag}
        onDrop={handleDrop}
        onClick={() => fileInputRef.current?.click()}
      >
        <input
          ref={fileInputRef}
          type="file"
          className="hidden"
          onChange={handleChange}
          accept=".pdf,.txt,.md,.csv,.json,.docx"
          multiple
        />

        <div className="p-4 bg-obsidian-elevated/50 rounded-full text-primary">
          <UploadIcon />
        </div>

        <div className="space-y-1">
          <p className="font-medium">Click to upload or drag and drop</p>
          <p className="text-sm text-(--theme-muted) opacity-60">PDF, TXT, MD, CSV, JSON, DOCX — multiple files supported</p>
        </div>
      </div>

      {fileEntries.length > 0 && (
        <div className="space-y-1.5">
          {fileEntries.map((entry, idx) => (
            <div key={idx} className="flex items-center gap-3 bg-obsidian-elevated/40 px-3 py-2 rounded-lg text-sm">
              <LuFile className="w-3.5 h-3.5 shrink-0 text-(--theme-muted)" />
              <span className="flex-1 truncate min-w-0">{entry.file.name}</span>
              {(entry.status === 'uploading' || entry.status === 'processing') && (
                <span className="loading loading-spinner loading-xs text-info" />
              )}
              <span className={`text-xs font-medium capitalize shrink-0 ${statusColor(entry.status)}`}>
                {entry.status}
              </span>
              {entry.message && entry.status === 'failed' && (
                <span className="text-xs text-error opacity-70 truncate max-w-32 shrink-0" title={entry.message}>
                  {entry.message}
                </span>
              )}
            </div>
          ))}
          {allDone && (
            <button
              className="btn btn-xs btn-ghost opacity-50 hover:opacity-100"
              onClick={() => setFileEntries([])}
            >
              Clear
            </button>
          )}
        </div>
      )}
    </div>
  );
};
