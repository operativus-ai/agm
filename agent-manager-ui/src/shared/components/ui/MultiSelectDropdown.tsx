import React, { useState, useRef, useEffect, useCallback } from 'react';
import { cn } from '../../utils/cn';
import { FormFieldWrapper } from './FormFieldWrapper';
import { Badge } from './Badge';

export interface MultiSelectOption {
  value: string;
  label: string;
  disabled?: boolean;
}

export interface MultiSelectDropdownProps {
  value: string[];
  onChange: (value: string[]) => void;
  options: MultiSelectOption[];
  placeholder?: string;
  emptyMessage?: string;
  label?: string;
  description?: string;
  error?: string | boolean;
  required?: boolean;
  disabled?: boolean;
  loading?: boolean;
  fetchError?: string | null;
  className?: string;
  id?: string;
  /** Render the display label for a selected value. Falls back to matching option label or raw value. */
  renderSelectedLabel?: (value: string) => string;
}

export const MultiSelectDropdown: React.FC<MultiSelectDropdownProps> = ({
  value = [],
  onChange,
  options,
  placeholder = 'Select items...',
  emptyMessage = 'No options available',
  label,
  description,
  error,
  required,
  disabled,
  loading,
  fetchError,
  className,
  id,
  renderSelectedLabel,
}) => {
  const [isOpen, setIsOpen] = useState(false);
  const [search, setSearch] = useState('');
  const containerRef = useRef<HTMLDivElement>(null);
  const searchRef = useRef<HTMLInputElement>(null);

  const inputId = id || `multi-select-${Math.random().toString(36).substr(2, 9)}`;
  const hasError = Boolean(error || fetchError);
  const displayError = fetchError || (typeof error === 'string' ? error : undefined);

  // Close dropdown on outside click
  useEffect(() => {
    const handleClickOutside = (e: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
        setIsOpen(false);
        setSearch('');
      }
    };
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

  // Focus search input when dropdown opens
  useEffect(() => {
    if (isOpen && searchRef.current) {
      searchRef.current.focus();
    }
  }, [isOpen]);

  const filteredOptions = options.filter(opt =>
    opt.label.toLowerCase().includes(search.toLowerCase()) ||
    opt.value.toLowerCase().includes(search.toLowerCase())
  );

  const handleToggle = useCallback((optionValue: string) => {
    if (value.includes(optionValue)) {
      onChange(value.filter(v => v !== optionValue));
    } else {
      onChange([...value, optionValue]);
    }
  }, [value, onChange]);

  const handleRemove = useCallback((optionValue: string) => {
    if (disabled) return;
    onChange(value.filter(v => v !== optionValue));
  }, [value, onChange, disabled]);

  const getDisplayLabel = useCallback((val: string): string => {
    if (renderSelectedLabel) return renderSelectedLabel(val);
    const opt = options.find(o => o.value === val);
    return opt ? opt.label : val;
  }, [options, renderSelectedLabel]);

  const isDisabled = disabled || !!fetchError;

  return (
    <FormFieldWrapper
      label={label}
      description={description}
      error={displayError}
      required={required}
      htmlFor={inputId}
    >
      <div ref={containerRef} className={cn('relative', className)}>
        {/* Selected tags and trigger */}
        <div
          role="combobox"
          aria-expanded={isOpen}
          aria-haspopup="listbox"
          tabIndex={isDisabled ? -1 : 0}
          onClick={() => !isDisabled && setIsOpen(!isOpen)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' || e.key === ' ') {
              e.preventDefault();
              if (!isDisabled) setIsOpen(!isOpen);
            }
          }}
          className={cn(
            'w-full bg-obsidian-surface border rounded-lg px-3 min-h-[48px] flex items-center flex-wrap gap-2 py-2 cursor-pointer transition-colors',
            {
              'border-error': hasError,
              'border-obsidian-stroke hover:border-agent-blue/50': !hasError && !isOpen,
              'border-agent-blue': isOpen && !hasError,
              'opacity-50 cursor-not-allowed bg-base-200': isDisabled,
            }
          )}
        >
          {value.length > 0 ? (
            value.map(v => (
              <Badge
                key={v}
                variant="default"
                className="flex items-center gap-1 bg-obsidian-layer border-obsidian-stroke"
              >
                <span className="truncate max-w-45">{getDisplayLabel(v)}</span>
                {!isDisabled && (
                  <button
                    type="button"
                    className="hover:text-error text-theme-muted ml-1 font-bold"
                    onClick={(e) => { e.stopPropagation(); handleRemove(v); }}
                    aria-label={`Remove ${getDisplayLabel(v)}`}
                  >
                    ✕
                  </button>
                )}
              </Badge>
            ))
          ) : (
            <span className="text-theme-muted text-sm">{placeholder}</span>
          )}

          {/* Dropdown arrow / loading indicator */}
          <span className="ml-auto pl-2 shrink-0 flex items-center">
            {loading ? (
              <span className="loading loading-spinner loading-xs text-agent-blue"></span>
            ) : (
              <svg className={cn('w-4 h-4 text-theme-muted transition-transform', isOpen && 'rotate-180')} fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
              </svg>
            )}
          </span>
        </div>

        {/* Dropdown panel */}
        {isOpen && (
          <div className="absolute z-50 mt-1 w-full bg-obsidian-elevated border border-obsidian-stroke rounded-lg shadow-xl overflow-hidden animate-in fade-in slide-in-from-top-1 duration-150">
            {/* Search input */}
            <div className="p-2 border-b border-obsidian-stroke">
              <input
                ref={searchRef}
                type="text"
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                placeholder="Search..."
                className="input input-sm w-full bg-obsidian-surface border-obsidian-stroke focus:border-agent-blue text-theme-foreground text-sm"
                onClick={(e) => e.stopPropagation()}
              />
            </div>

            {/* Options list */}
            <ul
              role="listbox"
              aria-multiselectable="true"
              className="max-h-52 overflow-y-auto custom-scrollbar py-1"
            >
              {loading ? (
                <li className="px-4 py-3 text-center text-theme-muted text-sm">
                  <span className="loading loading-spinner loading-sm text-agent-blue mr-2"></span>
                  Loading...
                </li>
              ) : filteredOptions.length === 0 ? (
                <li className="px-4 py-3 text-center text-theme-muted text-sm italic">
                  {search ? 'No matches found' : emptyMessage}
                </li>
              ) : (
                filteredOptions.map(opt => {
                  const isSelected = value.includes(opt.value);
                  return (
                    <li
                      key={opt.value}
                      role="option"
                      aria-selected={isSelected}
                      onClick={(e) => {
                        e.stopPropagation();
                        if (!opt.disabled) handleToggle(opt.value);
                      }}
                      className={cn(
                        'flex items-center gap-3 px-4 py-2 cursor-pointer transition-colors text-sm',
                        {
                          'bg-agent-blue/10 text-agent-blue': isSelected,
                          'text-theme-foreground hover:bg-obsidian-surface': !isSelected && !opt.disabled,
                          'opacity-40 cursor-not-allowed': opt.disabled,
                        }
                      )}
                    >
                      <span className={cn(
                        'w-4 h-4 rounded border flex items-center justify-center shrink-0 transition-colors',
                        isSelected
                          ? 'bg-agent-blue border-agent-blue text-white'
                          : 'border-obsidian-stroke bg-obsidian-surface'
                      )}>
                        {isSelected && (
                          <svg className="w-3 h-3" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={3}>
                            <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
                          </svg>
                        )}
                      </span>
                      <span className="truncate">{opt.label}</span>
                    </li>
                  );
                })
              )}
            </ul>

            {/* Selection count footer */}
            {value.length > 0 && (
              <div className="px-4 py-2 border-t border-obsidian-stroke text-[10px] text-theme-muted uppercase tracking-wider flex justify-between items-center">
                <span>{value.length} selected</span>
                <button
                  type="button"
                  onClick={(e) => { e.stopPropagation(); onChange([]); }}
                  className="hover:text-error transition-colors"
                >
                  Clear all
                </button>
              </div>
            )}
          </div>
        )}
      </div>
    </FormFieldWrapper>
  );
};
