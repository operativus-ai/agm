import React, { useState, useRef, useEffect, useCallback } from 'react';
import { cn } from '../../utils/cn';
import { FormFieldWrapper } from './FormFieldWrapper';

export interface SearchableSelectOption {
  value: string;
  label: string;
  sublabel?: string;
  disabled?: boolean;
  disabledReason?: string;
}

export interface SearchableSelectProps {
  value: string;
  onChange: (value: string) => void;
  options: SearchableSelectOption[];
  placeholder?: string;
  emptyMessage?: string;
  label?: string;
  description?: string;
  helpText?: string;
  error?: string | boolean;
  required?: boolean;
  disabled?: boolean;
  loading?: boolean;
  className?: string;
  id?: string;
}

export const SearchableSelect: React.FC<SearchableSelectProps> = ({
  value,
  onChange,
  options,
  placeholder = 'Select...',
  emptyMessage = 'No options available',
  label,
  description,
  helpText,
  error,
  required,
  disabled,
  loading,
  className,
  id,
}) => {
  const [isOpen, setIsOpen] = useState(false);
  const [search, setSearch] = useState('');
  const containerRef = useRef<HTMLDivElement>(null);
  const searchRef = useRef<HTMLInputElement>(null);

  const inputId = id || `searchable-select-${Math.random().toString(36).substr(2, 9)}`;
  const hasError = Boolean(error);
  const displayError = typeof error === 'string' ? error : undefined;

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
    opt.value.toLowerCase().includes(search.toLowerCase()) ||
    (opt.sublabel && opt.sublabel.toLowerCase().includes(search.toLowerCase()))
  );

  const selectedOption = options.find(o => o.value === value);

  const handleSelect = useCallback((optionValue: string) => {
    onChange(optionValue);
    setIsOpen(false);
    setSearch('');
  }, [onChange]);

  const handleClear = useCallback((e: React.MouseEvent) => {
    e.stopPropagation();
    onChange('');
    setSearch('');
  }, [onChange]);

  const isDisabled = disabled || loading;

  return (
    <FormFieldWrapper
      label={label}
      description={description}
      helpText={helpText}
      error={displayError}
      required={required}
      htmlFor={inputId}
    >
      <div ref={containerRef} className={cn('relative', className)}>
        {/* Trigger */}
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
            'w-full bg-(--theme-card) border rounded-lg px-3 h-12 flex items-center gap-2 cursor-pointer transition-colors',
            {
              'border-error': hasError,
              'border-(--theme-muted)/10 hover:border-primary/50': !hasError && !isOpen,
              'border-primary': isOpen && !hasError,
              'opacity-50 cursor-not-allowed': isDisabled,
            }
          )}
        >
          {selectedOption ? (
            <div className="flex-1 min-w-0">
              <span className="text-sm text-(--theme-foreground) truncate block">{selectedOption.label}</span>
              {selectedOption.sublabel && (
                <span className="text-[10px] text-(--theme-muted) font-mono truncate block">{selectedOption.sublabel}</span>
              )}
            </div>
          ) : (
            <span className="text-(--theme-muted) text-sm flex-1">{placeholder}</span>
          )}

          <span className="ml-auto pl-2 shrink-0 flex items-center gap-1">
            {value && !isDisabled && (
              <button
                type="button"
                className="hover:text-error text-(--theme-muted) transition-colors"
                onClick={handleClear}
                aria-label="Clear selection"
              >
                <svg className="w-3.5 h-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                  <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
                </svg>
              </button>
            )}
            {loading ? (
              <span className="loading loading-spinner loading-xs text-primary"></span>
            ) : (
              <svg className={cn('w-4 h-4 text-(--theme-muted) transition-transform', isOpen && 'rotate-180')} fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
              </svg>
            )}
          </span>
        </div>

        {/* Dropdown panel */}
        {isOpen && (
          <div className="absolute z-50 mt-1 w-full bg-(--theme-card) border border-(--theme-muted)/10 rounded-lg shadow-xl overflow-hidden animate-in fade-in slide-in-from-top-1 duration-150">
            {/* Search input */}
            <div className="p-2 border-b border-(--theme-muted)/10">
              <input
                ref={searchRef}
                type="text"
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                placeholder="Search..."
                className="input input-sm w-full bg-(--theme-background) border-(--theme-muted)/10 focus:border-primary text-(--theme-foreground) text-sm"
                onClick={(e) => e.stopPropagation()}
              />
            </div>

            {/* Options list */}
            <ul
              role="listbox"
              className="max-h-52 overflow-y-auto py-1"
            >
              {loading ? (
                <li className="px-4 py-3 text-center text-(--theme-muted) text-sm">
                  <span className="loading loading-spinner loading-sm text-primary mr-2"></span>
                  Loading...
                </li>
              ) : filteredOptions.length === 0 ? (
                <li className="px-4 py-3 text-center text-(--theme-muted) text-sm italic">
                  {search ? 'No matches found' : emptyMessage}
                </li>
              ) : (
                filteredOptions.map(opt => {
                  const isSelected = opt.value === value;
                  return (
                    <li
                      key={opt.value}
                      role="option"
                      aria-selected={isSelected}
                      onClick={(e) => {
                        e.stopPropagation();
                        if (!opt.disabled) handleSelect(opt.value);
                      }}
                      className={cn(
                        'flex items-center gap-3 px-4 py-2 cursor-pointer transition-colors text-sm',
                        {
                          'bg-primary/10 text-primary': isSelected,
                          'text-(--theme-foreground) hover:bg-(--theme-muted)/5': !isSelected && !opt.disabled,
                          'opacity-40 cursor-not-allowed': opt.disabled,
                        }
                      )}
                      title={opt.disabled ? opt.disabledReason : undefined}
                    >
                      <div className="flex-1 min-w-0">
                        <span className="block truncate">{opt.label}</span>
                        {opt.sublabel && (
                          <span className="block text-[10px] text-(--theme-muted) font-mono truncate">{opt.sublabel}</span>
                        )}
                      </div>
                      {isSelected && (
                        <svg className="w-4 h-4 text-primary shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={3}>
                          <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
                        </svg>
                      )}
                    </li>
                  );
                })
              )}
            </ul>
          </div>
        )}
      </div>
    </FormFieldWrapper>
  );
};
