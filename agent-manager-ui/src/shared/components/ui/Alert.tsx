import { forwardRef } from 'react';
import { cn } from '../../utils/cn';
import type { AlertProps } from '../../types/component-props';



export const Alert = forwardRef<HTMLDivElement, AlertProps>(({
  className,
  severity = 'info',
  title,
  description,
  icon: Icon,
  actions,
  dismissible,
  onClose,
  children,
  ...props
}, ref) => {
  return (
    <div
      ref={ref}
      role="alert"
      className={cn(
        'alert shadow-sm border',
        // Base Overrides
        'bg-obsidian-elevated text-theme-foreground border-obsidian-stroke',
        // Severity Overrides
        severity === 'info' && 'border-info-sky/20 bg-info-sky/5 text-info-sky',
        severity === 'success' && 'border-active-green/20 bg-active-green/5 text-active-green',
        severity === 'warning' && 'border-warn-amber/20 bg-warn-amber/5 text-warn-amber',
        severity === 'error' && 'border-error-red/20 bg-error-red/5 text-error-red',
        className
      )}
      {...props}
    >
      {Icon && <Icon className="h-6 w-6 shrink-0 stroke-current" />}
      
      <div className="flex flex-col gap-1 w-full">
        {title && <h3 className="font-bold">{title}</h3>}
        {description && <div className="text-sm">{description}</div>}
        {children}
      </div>

      {(actions || dismissible) && (
        <div className="flex items-center gap-2">
          {actions}
          {dismissible && (
            <button 
              className="btn btn-sm btn-ghost btn-circle"
              onClick={onClose}
              aria-label="Close"
            >
              ✕
            </button>
          )}
        </div>
      )}
    </div>
  );
});

Alert.displayName = 'Alert';
