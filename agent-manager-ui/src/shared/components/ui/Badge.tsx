import React from 'react';
import { cn } from '../../utils/cn';

interface BadgeProps extends React.HTMLAttributes<HTMLSpanElement> {
  variant?: 'default' | 'neutral' | 'primary' | 'secondary' | 'accent' | 'ghost' | 'info' | 'success' | 'warning' | 'error';
  size?: 'xs' | 'sm' | 'md' | 'lg';
  outline?: boolean;
}

export const Badge: React.FC<BadgeProps> = ({
  className,
  variant = 'neutral',
  size = 'md',
  outline = false,
  children,
  ...props
}) => {
  return (
    <span
      className={cn(
        'badge',
        `badge-${variant}`,
        size !== 'md' && `badge-${size}`,
        outline && 'badge-outline',
        // Obsidian Overrides
        variant === 'primary' && 'bg-agent-blue text-white border-agent-blue',
        variant === 'secondary' && 'bg-obsidian-elevated text-theme-muted border-obsidian-stroke',
        variant === 'success' && 'bg-active-green/10 text-active-green border-active-green/20',
        variant === 'warning' && 'bg-warn-amber/10 text-warn-amber border-warn-amber/20',
        variant === 'error' && 'bg-error-red/10 text-error-red border-error-red/20',
        className
      )}
      {...props}
    >
      {children}
    </span>
  );
};
