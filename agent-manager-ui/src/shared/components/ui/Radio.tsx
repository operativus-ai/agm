import React, { forwardRef, useCallback } from 'react';
import { cn } from '../../utils/cn';
import { FormFieldWrapper } from './FormFieldWrapper';
import type { RadioProps } from '../../types/component-props';

const SIZE_STYLES = {
  xs: 'radio-xs',
  sm: 'radio-sm',
  md: 'radio-md',
  lg: 'radio-lg',
  xl: 'radio-lg',
} as const;

export const Radio = forwardRef<HTMLInputElement, RadioProps>(({
  size = 'md',
  label,
  description,
  error,
  required,
  disabled,
  loading,
  value,
  checked,
  onValueChange,
  onChange,
  className,
  'data-testid': testId,
  ...props
}, ref) => {
  const radioId = props.id || `radio-${Math.random().toString(36).substr(2, 9)}`;
  const hasError = Boolean(error && typeof error === 'string');

  const handleChange = useCallback((event: React.ChangeEvent<HTMLInputElement>) => {
    // Only fire change if checked (native radio behavior)
    if (event.target.checked) {
      onChange?.(event);
      onValueChange?.(value);
    }
  }, [ onChange, onValueChange, value ]);

  const radioClasses = cn(
    'radio',
    SIZE_STYLES[size],
    {
      'radio-error': hasError,
      'radio-primary': !hasError,
    },
    className,
  );

  const radioElement = (
    <div className={cn("form-control", { "cursor-not-allowed opacity-50": disabled || loading })}>
      <label className="label cursor-pointer justify-start gap-3 py-0">
        <input
          ref={ref}
          id={radioId}
          type="radio"
          value={value}
          checked={checked}
          disabled={disabled || loading}
          required={required}
          className={radioClasses}
          onChange={handleChange}
          data-testid={testId}
          {...props}
        />
        {label && (
          <span className="label-text">
            {label}
            {required && <span className="text-error ml-1">*</span>}
          </span>
        )}
      </label>
    </div>
  );

  if (description || hasError) {
    return (
      <FormFieldWrapper
        description={description}
        error={error}
        loading={loading}
        size={size}
      >
        {radioElement}
      </FormFieldWrapper>
    );
  }

  return radioElement;
});

Radio.displayName = 'Radio';
