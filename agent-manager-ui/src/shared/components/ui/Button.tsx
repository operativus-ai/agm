import { forwardRef } from 'react';
import { cn } from '../../utils/cn';

import type { ButtonProps } from '../../types/component-props';

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(({ 
  className, 
  variant = 'primary', 
  size = 'md', 
  loading = false, 
  children,
  disabled,
  fullWidth,
  ...props 
}, ref) => {
  
  const variants: Record<string, string> = {
    primary: 'btn-primary',
    secondary: 'btn-secondary',
    ghost: 'btn-ghost',
    outline: 'btn-outline',
    danger: 'btn-error',
    link: 'btn-link',
    destructive: 'btn-error',
  };

  const sizes: Record<string, string> = {
    xs: 'btn-xs',
    sm: 'btn-sm',
    md: 'btn-md',
    lg: 'btn-lg',
    xl: 'btn-lg', // Fallback for xl
  };

  return (
    <button
      ref={ref}
      className={cn(
        'btn',
        variants[variant],
        sizes[size],
        loading && 'loading',
        fullWidth && 'w-full',
        // Obsidian Overrides
        variant === 'primary' && 'bg-agent-blue border-agent-blue text-white hover:bg-agent-blue/90 hover:border-agent-blue/90',
        variant === 'secondary' && 'bg-obsidian-elevated border-obsidian-stroke text-theme-foreground hover:bg-obsidian-stroke hover:border-obsidian-stroke',
        variant === 'ghost' && 'text-theme-muted hover:bg-obsidian-elevated hover:text-white',
        (variant === 'danger' || variant === 'destructive') && 'bg-error-red/10 text-error-red border-error-red/20 hover:bg-error-red/20',
        className
      )}
      disabled={disabled || loading}
      {...props}
    >
      {loading && <span className="loading loading-spinner"></span>}
      {children}
    </button>
  );
});

Button.displayName = 'Button';
