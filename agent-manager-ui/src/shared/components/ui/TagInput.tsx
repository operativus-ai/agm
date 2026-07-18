import React, { useState, forwardRef, useCallback } from 'react';
import type { KeyboardEvent } from 'react';
import { cn } from '../../utils/cn';
import { FormFieldWrapper } from './FormFieldWrapper';
import { Badge } from './Badge';

export interface TagInputProps {
  value: string[];
  onChange: (value: string[]) => void;
  placeholder?: string;
  label?: string;
  description?: string;
  error?: string | boolean;
  required?: boolean;
  disabled?: boolean;
  className?: string;
  id?: string;
  validateTag?: (tag: string) => boolean | string; // Returns true if valid, or a string error message.
}

export const TagInput = forwardRef<HTMLInputElement, TagInputProps>(({
  value = [],
  onChange,
  placeholder = "Type and press Enter or comma...",
  label,
  description,
  error,
  required,
  disabled,
  className,
  id,
  validateTag,
  ...props
}, ref) => {
  const [inputValue, setInputValue] = useState('');
  const [internalError, setInternalError] = useState<string | null>(null);

  const inputId = id || `tag-input-${Math.random().toString(36).substr(2, 9)}`;
  const hasError = Boolean(error || internalError);
  const displayError = internalError || (typeof error === 'string' ? error : undefined);

  const handleAddTag = useCallback(() => {
    const trimmed = inputValue.trim();
    if (!trimmed) {
      setInputValue('');
      return;
    }

    if (value.includes(trimmed)) {
      setInputValue('');
      return;
    }

    if (validateTag) {
        const validationResult = validateTag(trimmed);
        if (validationResult !== true) {
            setInternalError(typeof validationResult === 'string' ? validationResult : "Invalid tag");
            return;
        }
    }

    onChange([...value, trimmed]);
    setInputValue('');
    setInternalError(null);
  }, [inputValue, value, onChange, validateTag]);

  const handleKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      handleAddTag();
    } else if (e.key === ',' || e.key === 'Tab') {
      e.preventDefault();
      handleAddTag();
    } else if (e.key === 'Backspace' && !inputValue && value.length > 0) {
      e.preventDefault();
      onChange(value.slice(0, -1));
    }
  };

  const handleChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    setInputValue(e.target.value);
    setInternalError(null);
  };

  const handleRemove = (tagToRemove: string) => {
    if (disabled) return;
    onChange(value.filter(tag => tag !== tagToRemove));
  };

  const containerClasses = cn(
    'w-full bg-obsidian-surface border rounded-lg focus-within:border-agent-blue transition-colors px-3 min-h-[48px] flex items-center flex-wrap gap-2 py-2',
    {
      'border-error': hasError,
      'border-obsidian-stroke': !hasError,
      'opacity-50 cursor-not-allowed bg-base-200': disabled
    },
    className
  );

  return (
    <FormFieldWrapper
      label={label}
      description={description}
      error={displayError}
      required={required}
      htmlFor={inputId}
    >
      <div className={containerClasses}>
        {value.map(tag => (
          <Badge 
            key={tag} 
            variant="default" 
            className="flex items-center gap-1 bg-obsidian-layer border-obsidian-stroke"
          >
            {tag}
            {!disabled && (
              <button
                type="button"
                className="hover:text-error text-theme-muted ml-1 font-bold"
                onClick={() => handleRemove(tag)}
                aria-label={`Remove ${tag}`}
              >
                ✕
              </button>
            )}
          </Badge>
        ))}
        <input
          ref={ref}
          id={inputId}
          type="text"
          value={inputValue}
          onChange={handleChange}
          onKeyDown={handleKeyDown}
          onBlur={handleAddTag}
          disabled={disabled}
          placeholder={value.length === 0 ? placeholder : "..."}
          className="flex-1 min-w-30 bg-transparent outline-hidden text-theme-foreground text-sm"
          {...props}
        />
      </div>
    </FormFieldWrapper>
  );
});

TagInput.displayName = 'TagInput';
