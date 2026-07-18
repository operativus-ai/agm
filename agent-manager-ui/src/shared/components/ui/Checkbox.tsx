import React, { forwardRef, useCallback, useRef, useEffect } from 'react';
import { cn } from '../../utils/cn';
import { FormFieldWrapper } from './FormFieldWrapper';
import type { CheckboxProps } from '../../types/component-props';

const SIZE_STYLES = {
  xs: 'checkbox-xs',
  sm: 'checkbox-sm',
  md: 'checkbox-md',
  lg: 'checkbox-lg',
  xl: 'checkbox-lg', // DaisyUI doesn't support xl checkbox natively, map to lg
} as const;

export const Checkbox = forwardRef<HTMLInputElement, CheckboxProps>(({
  size = 'md',
  label,
  description,
  helpText,
  error,
  required,
  disabled,
  loading,
  checked,
  indeterminate = false,
  onCheckedChange,
  onChange,
  className,
  'data-testid': testId,
  ...props
}, ref) => {
  const checkboxId = props.id || `checkbox-${Math.random().toString(36).substr(2, 9)}`;
  const hasError = Boolean(error && typeof error === 'string');
  const checkboxRef = useRef<HTMLInputElement>(null);

  // Handle indeterminate state
  useEffect(() => {
    if (checkboxRef.current) {
      checkboxRef.current.indeterminate = indeterminate;
    }
  }, [ indeterminate ]);

  const handleChange = useCallback((event: React.ChangeEvent<HTMLInputElement>) => {
    onChange?.(event);
    onCheckedChange?.(event.target.checked);
  }, [ onChange, onCheckedChange ]);

  const handleRef = useCallback((element: HTMLInputElement | null) => {
    checkboxRef.current = element;
    if (typeof ref === 'function') {
      ref(element);
    } else if (ref) {
      ref.current = element;
    }
  }, [ ref ]);

  const checkboxClasses = cn(
    'checkbox',
    SIZE_STYLES[size],
    {
      'checkbox-error': hasError,
      'checkbox-primary': !hasError,
    },
    className,
  );

  const checkboxElement = (
    <div className={cn("form-control", { "cursor-not-allowed opacity-50": disabled || loading })}>
      <label className="label cursor-pointer justify-start gap-3 py-0">
        <input
          ref={handleRef}
          id={checkboxId}
          type="checkbox"
          checked={checked}
          disabled={disabled || loading}
          required={required}
          className={checkboxClasses}
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

  // If we have error/description, wrap it to show those message below
  if (description || hasError) {
    return (
      <FormFieldWrapper
        description={description}
        helpText={helpText}
        error={error}
        loading={loading}
        size={size}
      >
        {checkboxElement}
      </FormFieldWrapper>
    );
  }

  return checkboxElement;
});

Checkbox.displayName = 'Checkbox';
