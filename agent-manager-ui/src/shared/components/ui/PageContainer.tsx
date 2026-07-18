import React from 'react';
import { cn } from '../../utils/cn';

/**
 * Content-type variants mapped to max-width constraints.
 *
 * - general:   Standard application pages (1200px)
 * - form:      Forms and data-entry views (768px)
 * - text:      Text-heavy / reading views (672px)
 * - dashboard: Data tables, charts, dense layouts (no constraint)
 */
const variantStyles = {
  general:   'max-w-[1200px]',
  form:      'max-w-[768px]',
  text:      'max-w-[672px]',
  dashboard: 'max-w-none',
} as const;

type ContentVariant = keyof typeof variantStyles;

interface PageContainerProps extends React.HTMLAttributes<HTMLDivElement> {
  /** Content-type that determines the max-width constraint */
  variant?: ContentVariant;
}

export const PageContainer: React.FC<PageContainerProps> = ({
  variant = 'general',
  className,
  children,
  ...props
}) => (
  <div
    className={cn(
      'w-full mx-auto space-y-6 animate-in fade-in duration-300',
      variantStyles[variant],
      className,
    )}
    {...props}
  >
    {children}
  </div>
);
