import React, { forwardRef, useCallback, useState, useEffect } from 'react';
import { cn } from '../../utils/cn';
import { FormFieldWrapper } from './FormFieldWrapper';
import type { TextareaProps } from '../../types/component-props';

const SIZE_STYLES = {
  xs: 'is-xs text-xs px-2 py-1',
  sm: 'text-sm px-3 py-2',
  md: 'text-base px-3 py-2',
  lg: 'text-lg px-4 py-3',
  xl: 'text-xl px-4 py-3',
} as const;

export const Textarea = forwardRef<HTMLTextAreaElement, TextareaProps>(({
  size = 'md',
  label,
  description,
  helpText,
  error,
  required,
  disabled,
  loading,
  placeholder,
  autoResize = false,
  minRows = 3,
  maxRows = 8,
  onValueChange,
  onChange,
  className,
  'data-testid': testId,
  ...props
}, ref) => {
  const textareaId = props.id || `textarea-${Math.random().toString(36).substr(2, 9)}`;
  const hasError = Boolean(error && typeof error === 'string');
  const [ textareaRef, setTextareaRef ] = useState<HTMLTextAreaElement | null>(null);

  // Auto-resize functionality
  const adjustHeight = useCallback(() => {
    if (!autoResize || !textareaRef) return;

    textareaRef.style.height = 'auto';
    const scrollHeight = textareaRef.scrollHeight;
    const lineHeightVal = getComputedStyle(textareaRef).lineHeight;
    const lineHeight = lineHeightVal === 'normal' ? 24 : parseInt(lineHeightVal);
    
    // Fallback if lineHeight parsing fails
    if (isNaN(lineHeight)) return;

    const minHeight = lineHeight * minRows;
    const maxHeight = lineHeight * maxRows;

    const newHeight = Math.max(minHeight, Math.min(maxHeight, scrollHeight));
    textareaRef.style.height = `${newHeight}px`;
  }, [ autoResize, textareaRef, minRows, maxRows ]);

  // Re-adjust on value change from props
  useEffect(() => {
    adjustHeight();
  }, [ adjustHeight, props.value ]);

  const handleChange = useCallback((event: React.ChangeEvent<HTMLTextAreaElement>) => {
    onChange?.(event);
    onValueChange?.(event.target.value);
    if (autoResize) {
      requestAnimationFrame(adjustHeight);
    }
  }, [ onChange, onValueChange, autoResize, adjustHeight ]);

  const handleRef = useCallback((element: HTMLTextAreaElement | null) => {
    setTextareaRef(element);
    if (typeof ref === 'function') {
      ref(element);
    } else if (ref) {
      ref.current = element;
    }
  }, [ ref ]);

  const textareaClasses = cn(
    'textarea textarea-bordered w-full bg-obsidian-base border-obsidian-stroke text-theme-foreground focus:border-agent-blue focus:outline-hidden',
    SIZE_STYLES[size],
    {
      'textarea-error': hasError,
      'resize-none': autoResize,
    },
    className,
  );

  const textareaElement = (
    <div className="relative">
      <textarea
        ref={handleRef}
        id={textareaId}
        placeholder={placeholder}
        disabled={disabled || loading}
        required={required}
        rows={autoResize ? minRows : (props.rows || minRows)}
        className={textareaClasses}
        onChange={handleChange}
        data-testid={testId}
        {...props}
      />

      {/* Loading Indicator */}
      {loading && (
        <div className="absolute right-3 top-3 pointer-events-none">
          <span className="loading loading-spinner loading-sm text-primary"></span>
        </div>
      )}
    </div>
  );

  return (
    <FormFieldWrapper
      label={label}
      description={description}
      helpText={helpText}
      error={error}
      required={required}
      loading={loading}
      size={size}
      htmlFor={textareaId}
    >
      {textareaElement}
    </FormFieldWrapper>
  );
});

Textarea.displayName = 'Textarea';
