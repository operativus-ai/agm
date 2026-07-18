import React, { forwardRef, useCallback } from 'react';
import { cn } from '../../utils/cn';
import { FormFieldWrapper } from './FormFieldWrapper';
import type { SelectProps, SelectOption } from '../../types/component-props';

const SIZE_STYLES = {
  xs: 'select-xs',
  sm: 'select-sm',
  md: 'select-md',
  lg: 'select-lg',
  xl: 'select-lg text-lg h-12',
} as const;

export const Select = forwardRef<HTMLSelectElement, SelectProps>(({
  size = 'md',
  label,
  description,
  helpText,
  error,
  required,
  disabled,
  loading,
  placeholder,
  options,
  allowEmpty = false,
  onValueChange,
  onChange,
  className,
  'data-testid': testId,
  ...props
}, ref) => {
  const selectId = props.id || `select-${Math.random().toString(36).substr(2, 9)}`;
  const hasError = Boolean(error && typeof error === 'string');

  const handleChange = useCallback((event: React.ChangeEvent<HTMLSelectElement>) => {
    onChange?.(event);
    onValueChange?.(event.target.value);
  }, [ onChange, onValueChange ]);

  const selectClasses = cn(
    'select select-bordered w-full bg-obsidian-base border-obsidian-stroke text-theme-foreground focus:border-agent-blue focus:outline-hidden',
    SIZE_STYLES[size],
    {
      'select-error': hasError,
    },
    className,
  );

  // Group options by group if specified
  const groupedOptions = options.reduce((acc, option) => {
    const group = option.group || 'default';
    if (!acc[group]) acc[group] = [];
    acc[group].push(option);
    return acc;
  }, {} as Record<string, SelectOption[]>);

  const hasGroups = Object.keys(groupedOptions).length > 1 || (Object.keys(groupedOptions)[0] !== 'default');

  const selectElement = (
    <div className="relative">
      <select
        ref={ref}
        id={selectId}
        disabled={disabled || loading}
        required={required}
        className={selectClasses}
        onChange={handleChange}
        data-testid={testId}
        defaultValue=""
        {...props}
      >
        {/* Placeholder option */}
        {(placeholder || allowEmpty) && (
          <option value="" disabled={!allowEmpty}>
            {placeholder || 'Select an option...'}
          </option>
        )}

        {/* Render options */}
        {hasGroups ? (
          Object.entries(groupedOptions).map(([ groupName, groupOptions ]) => (
            <optgroup key={groupName} label={groupName === 'default' ? '' : groupName}>
              {groupOptions.map((option) => (
                <option
                  key={option.value}
                  value={option.value}
                  disabled={option.disabled}
                >
                  {option.label}
                </option>
              ))}
            </optgroup>
          ))
        ) : (
          options.map((option) => (
            <option
              key={option.value}
              value={option.value}
              disabled={option.disabled}
            >
              {option.label}
            </option>
          ))
        )}
      </select>

      {/* Loading Indicator */}
      {loading && (
        <div className="absolute right-8 top-1/2 -translate-y-1/2 pointer-events-none">
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
      htmlFor={selectId}
    >
      {selectElement}
    </FormFieldWrapper>
  );
});

Select.displayName = 'Select';
