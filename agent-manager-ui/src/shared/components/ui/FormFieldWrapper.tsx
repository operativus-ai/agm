import React, { forwardRef, useState } from 'react';
import { cn } from '../../utils/cn';
import type { BaseFormFieldProps } from '../../types/component-props';
import { LuInfo } from 'react-icons/lu';

interface FormFieldWrapperProps extends BaseFormFieldProps {
  children: React.ReactNode;
  htmlFor?: string;
}

export const FormFieldWrapper = forwardRef<HTMLDivElement, FormFieldWrapperProps>(({
  label,
  description,
  helpText,
  error,
  required,
  loading,
  size = 'md',
  className,
  children,
  htmlFor,
  'data-testid': testId,
  ...props
}, ref) => {
  const hasError = Boolean(error && typeof error === 'string');
  const errorMessage = hasError ? error as string : '';
  const [showTooltip, setShowTooltip] = useState(false);

  const wrapperClasses = cn(
    'form-control w-full',
    {
      'opacity-50 pointer-events-none': loading,
    },
    className,
  );

  return (
    <div
      ref={ref}
      className={wrapperClasses}
      data-testid={testId}
      {...props}
    >
      {/* Label with optional help tooltip */}
      {label && (
        <div className="label pb-0.5">
          <label
            htmlFor={htmlFor}
            className={cn(
              'label-text font-medium flex items-center gap-1.5',
              {
                'text-xs': size === 'xs',
                'text-sm': size === 'sm',
                'text-base': size === 'md' || size === 'lg' || size === 'xl',
                'after:content-["*"] after:ml-0.5 after:text-error': required,
              },
            )}
          >
            {label}
            {helpText && (
              <span
                className="relative inline-flex"
                onMouseEnter={() => setShowTooltip(true)}
                onMouseLeave={() => setShowTooltip(false)}
              >
                <LuInfo className="w-3.5 h-3.5 text-theme-muted/60 hover:text-agent-blue cursor-help transition-colors" />
                {showTooltip && (
                  <span className="absolute left-1/2 -translate-x-1/2 bottom-full mb-2 z-50 w-64 px-3 py-2 text-xs font-normal normal-case tracking-normal text-theme-foreground bg-obsidian-surface border border-obsidian-stroke rounded-lg shadow-xl pointer-events-none whitespace-normal leading-relaxed">
                    {helpText}
                    <span className="absolute left-1/2 -translate-x-1/2 top-full w-0 h-0 border-x-[6px] border-x-transparent border-t-[6px] border-t-obsidian-stroke" />
                  </span>
                )}
              </span>
            )}
          </label>
        </div>
      )}

      {/* Description - displayed between label and input */}
      {description && !hasError && (
        <div className="px-0.5 pb-1.5">
          <span className="text-xs text-theme-muted/70 leading-relaxed">
            {description}
          </span>
        </div>
      )}

      {/* Form Control */}
      {children}

      {/* Error message - below input */}
      {hasError && (
        <div className="label pt-1 pb-0">
          <span className="label-text-alt text-error font-medium" role="alert">
            {errorMessage}
          </span>
        </div>
      )}
    </div>
  );
});

FormFieldWrapper.displayName = 'FormFieldWrapper';
