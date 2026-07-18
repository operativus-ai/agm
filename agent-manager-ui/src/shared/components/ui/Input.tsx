import React, { forwardRef, useCallback } from 'react';
import { cn } from '../../utils/cn';
import { FormFieldWrapper } from './FormFieldWrapper';
import type { InputProps } from '../../types/component-props';

const SIZE_STYLES = {
  xs: 'input-xs',
  sm: 'input-sm',
  md: 'input-md',
  lg: 'input-lg',
  xl: 'input-lg text-lg h-12',
} as const;

export const Input = forwardRef<HTMLInputElement, InputProps>(({
  type = 'text',
  size = 'md',
  label,
  description,
  helpText,
  error,
  required,
  disabled,
  loading,
  placeholder,
  startIcon: StartIcon,
  endIcon: EndIcon,
  onEndIconClick,
  endIconAriaLabel,
  onValueChange,
  onChange,
  className,
  'data-testid': testId,
  ...props
}, ref) => {
  const inputId = props.id || `input-${Math.random().toString(36).substr(2, 9)}`;
  const hasError = Boolean(error && typeof error === 'string');

  const handleChange = useCallback((event: React.ChangeEvent<HTMLInputElement>) => {
    onChange?.(event);
    onValueChange?.(event.target.value);
  }, [ onChange, onValueChange ]);

  const inputClasses = cn(
    'input input-bordered w-full bg-obsidian-base border-obsidian-stroke text-theme-foreground focus:border-agent-blue focus:outline-hidden',
    SIZE_STYLES[size],
    {
      'input-error': hasError,
      'pl-10': StartIcon,
      'pr-10': EndIcon || loading,
    },
    className,
  );

  const inputElement = (
    <div className="relative">
      {/* Start Icon */}
      {StartIcon && (
        <div className="absolute left-3 top-1/2 -translate-y-1/2 text-base-content/40 pointer-events-none">
          <StartIcon className="h-4 w-4"/>
        </div>
      )}

      {/* Input Element */}
      <input
        ref={ref}
        id={inputId}
        type={type}
        placeholder={placeholder}
        disabled={disabled || loading}
        required={required}
        className={inputClasses}
        onChange={handleChange}
        data-testid={testId}
        {...props}
      />

      {/* End Icon */}
      {EndIcon && !loading && (
        onEndIconClick ? (
          <button
            type="button"
            onClick={onEndIconClick}
            aria-label={endIconAriaLabel}
            className="absolute right-2 top-1/2 -translate-y-1/2 p-1 rounded text-base-content/60 hover:text-theme-foreground hover:bg-obsidian-elevated focus:outline-hidden focus:ring-2 focus:ring-agent-blue/40"
          >
            <EndIcon className="h-4 w-4"/>
          </button>
        ) : (
          <div className="absolute right-3 top-1/2 -translate-y-1/2 text-base-content/40 pointer-events-none">
            <EndIcon className="h-4 w-4"/>
          </div>
        )
      )}

      {/* Loading Indicator */}
      {loading && (
        <div className="absolute right-3 top-1/2 -translate-y-1/2">
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
      htmlFor={inputId}
    >
      {inputElement}
    </FormFieldWrapper>
  );
});

Input.displayName = 'Input';
