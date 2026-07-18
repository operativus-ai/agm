import React, { useState, useRef, useEffect } from 'react';
import { Button } from '../../../shared/components/ui/Button';
import { Textarea } from '../../../shared/components/ui/Textarea';
import type { MediaInput } from '../types';
import { logger } from '../../../utils/logger';
import { STORAGE_KEYS } from '../../../shared/constants/storage-keys';

interface ChatInputProps {
  onSend: (message: string, media?: MediaInput[], useSync?: boolean, useBackground?: boolean) => void;
  isLoading?: boolean;
  disabled?: boolean;
  onStop?: () => void;
}

const SendIcon = () => (
   <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="m22 2-7 20-4-9-9-4Z"/><path d="M22 2 11 13"/></svg>
);

const StopIcon = () => (
    <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><rect width="18" height="18" x="3" y="3" rx="2"/></svg>
);

const PaperclipIcon = () => (
    <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="m21.44 11.05-9.19 9.19a6 6 0 0 1-8.49-8.49l9.19-9.19a4 4 0 0 1 5.66 5.66l-9.2 9.19a2 2 0 0 1-2.83-2.83l8.49-8.48"/></svg>
);

const XIcon = () => (
    <svg xmlns="http://www.w3.org/2000/svg" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M18 6 6 18"/><path d="m6 6 12 12"/></svg>
);

const FileIcon = () => (
    <svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z"/><path d="M14 2v4a2 2 0 0 0 2 2h4"/></svg>
);

const ACCEPTED_FILE_TYPES = 'image/*,.pdf,.txt,.md,.csv,.json,.docx,.doc,.xml,.yaml,.yml';

export const ChatInput: React.FC<ChatInputProps> = ({ onSend, isLoading, disabled, onStop }) => {
  const [value, setValue] = useState('');
  const [media, setMedia] = useState<MediaInput[]>([]);
  const [useSync, setUseSync] = useState(false);
  const [useBackground, setUseBackground] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);
  
  const [history, setHistory] = useState<string[]>([]);
  const [historyIndex, setHistoryIndex] = useState<number>(-1);
  const [draft, setDraft] = useState('');

  useEffect(() => {
    try {
      const saved = localStorage.getItem(STORAGE_KEYS.CHAT_PROMPT_HISTORY);
      if (saved) {
        setHistory(JSON.parse(saved));
      }
    } catch (e) {
      logger.error('Failed to parse chat history', e);
    }
  }, []);
  
  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    } else if (e.key === 'ArrowUp') {
      const target = e.target as HTMLTextAreaElement;
      if (target.selectionStart !== null && !target.value.substring(0, target.selectionStart).includes('\n')) {
        if (historyIndex < history.length - 1) {
          e.preventDefault();
          if (historyIndex === -1) setDraft(value);
          const nextIndex = historyIndex + 1;
          setHistoryIndex(nextIndex);
          setValue(history[nextIndex]);
        }
      }
    } else if (e.key === 'ArrowDown') {
      const target = e.target as HTMLTextAreaElement;
      if (target.selectionEnd !== null && !target.value.substring(target.selectionEnd).includes('\n')) {
        if (historyIndex !== -1) {
          e.preventDefault();
          const nextIndex = historyIndex - 1;
          setHistoryIndex(nextIndex);
          setValue(nextIndex === -1 ? draft : history[nextIndex]);
        }
      }
    }
  };

  const handleSend = () => {
    if ((!value.trim() && media.length === 0) || isLoading || disabled) return;
    
    if (value.trim()) {
      const newHistory = [value, ...history.filter(h => h !== value)].slice(0, 50);
      setHistory(newHistory);
      localStorage.setItem(STORAGE_KEYS.CHAT_PROMPT_HISTORY, JSON.stringify(newHistory));
    }
    
    onSend(value, media.length > 0 ? media : undefined, useSync, useBackground);
    
    // Check if we're replacing the current draft in history
    setValue('');
    setMedia([]);
    setHistoryIndex(-1);
    setDraft('');
  };

  const handleFileSelect = async (e: React.ChangeEvent<HTMLInputElement>) => {
      if (e.target.files && e.target.files[0]) {
          const file = e.target.files[0];
          // Max file size: 10MB
          if (file.size > 10 * 1024 * 1024) {
              alert('File size must not exceed 10MB');
              return;
          }
          
          const reader = new FileReader();
          reader.onload = (ev) => {
              if (ev.target?.result) {
                  const base64 = ev.target.result as string; 
                  setMedia(prev => [...prev, { type: file.type || 'application/octet-stream', data: base64 }]);
              }
          };
          reader.readAsDataURL(file);
      }
      // Reset input so same file can be selected again if needed
      if (fileInputRef.current) fileInputRef.current.value = '';
  };

  const removeMedia = (index: number) => {
      setMedia(prev => prev.filter((_, i) => i !== index));
  };

  return (
    <div className="w-full relative">
      {/* Attachment Preview */}
      {media.length > 0 && (
      <div className="flex gap-2 mb-2 overflow-x-auto p-1">
              {media.map((m, i) => (
                  <div key={i} className="relative group">
                      {m.type.startsWith('image/') ? (
                          <img src={m.data} alt="attachment" className="h-16 w-16 object-cover rounded-lg border border-obsidian-stroke" />
                      ) : (
                          <div className="h-16 w-16 flex flex-col items-center justify-center rounded-lg border border-obsidian-stroke bg-obsidian-elevated text-theme-muted">
                              <FileIcon />
                              <span className="text-[8px] mt-0.5 truncate max-w-14">{m.type.split('/').pop()}</span>
                          </div>
                      )}
                      <button 
                          onClick={() => removeMedia(i)}
                          className="absolute -top-1 -right-1 bg-error text-white rounded-full p-0.5 shadow-sm opacity-0 group-hover:opacity-100 transition-opacity"
                      >
                          <XIcon />
                      </button>
                  </div>
              ))}
          </div>
      )}

      <div className="relative">
        <Textarea
            value={value}
            onValueChange={setValue}
            onKeyDown={handleKeyDown}
            placeholder="Type your message..."
            aria-label="Chat message input"
            minRows={1}
            maxRows={6}
            autoResize
            className="pl-10 pr-12 py-3 bg-obsidian-elevated shadow-sm border-obsidian-stroke focus:border-agent-blue focus:ring-agent-blue text-theme-foreground placeholder:text-theme-muted"
            disabled={disabled || (isLoading && !onStop)}
        />
        
        {/* Attachment Button */}
        <div className="absolute left-2 bottom-2.5">
            <input 
                type="file" 
                ref={fileInputRef} 
                onChange={handleFileSelect} 
                className="hidden" 
                accept={ACCEPTED_FILE_TYPES}
            />
            <Button
                size="xs"
                variant="ghost"
                onClick={() => fileInputRef.current?.click()}
                className="h-8 w-8 p-0 text-theme-muted hover:text-agent-blue"
                title="Attach File"
                aria-label="Attach file to message"
                disabled={isLoading}
            >
                <PaperclipIcon />
            </Button>
        </div>
        
        <div className="absolute right-2 bottom-2.5">
            {isLoading ? (
                <Button 
                    size="xs" 
                    variant="danger" 
                    onClick={onStop}
                    title="Stop generating"
                    aria-label="Stop generating response"
                    className="h-8 w-8 p-0 flex items-center justify-center rounded-lg"
                >
                    <StopIcon />
                </Button>
            ) : (
                <Button 
                    size="xs" 
                    variant="primary" 
                    onClick={handleSend}
                    disabled={!value.trim() && media.length === 0}
                    aria-label="Send message"
                    className="h-8 w-8 p-0 flex items-center justify-center rounded-lg shadow-md transition-all hover:scale-105 active:scale-95"
                >
                    <SendIcon />
                </Button>
            )}
        </div>
      </div>
      <div className="flex justify-between items-center mt-2 px-2 text-[10px] text-theme-muted">
        <div className="flex items-center gap-4">
            <label className="flex items-center gap-1.5 cursor-pointer hover:text-theme-foreground transition-colors">
                <input
                    type="checkbox"
                    className="toggle toggle-xs toggle-primary"
                    checked={useSync}
                    onChange={() => { setUseSync(!useSync); if (!useSync) setUseBackground(false); }}
                    disabled={isLoading}
                />
                <span>Sync Mode</span>
            </label>
            <label className="flex items-center gap-1.5 cursor-pointer hover:text-theme-foreground transition-colors">
                <input
                    type="checkbox"
                    className="toggle toggle-xs toggle-neutral"
                    checked={useBackground}
                    onChange={() => { setUseBackground(!useBackground); if (!useBackground) setUseSync(false); }}
                    disabled={isLoading}
                />
                <span>Background Mode</span>
            </label>
        </div>
        <div>Agent calls may generate costs. AI can make mistakes.</div>
      </div>
    </div>
  );
};
