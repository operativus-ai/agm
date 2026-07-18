import React, { useRef } from 'react';
import { cn } from '../../utils/cn';
import { ModalContainer } from '../modal/ModalContainer';
import { Card } from './Card';
import { Button } from './Button';
import type { AlertSeverity } from '../../types/component-props';

// Map severities to button variants
const confirmVariants: Record<string, 'primary' | 'danger' | 'secondary' | 'ghost' | 'outline' | 'link' | 'destructive'> = {
  error: 'danger',
  warning: 'danger',
  info: 'primary',
  success: 'primary',
};

export interface DialogProps {
  isOpen: boolean;
  setIsOpen: (isOpen: boolean) => void;
  title: string;
  content?: string;
  children?: React.ReactNode;
  
  // Actions
  onConfirm?: () => void;
  onCancel?: () => void;
  confirmLabel?: string;
  cancelLabel?: string;
  
  // Configuration
  severity?: AlertSeverity;
  canBeCanceled?: boolean;
  shouldCloseOnConfirm?: boolean;
  className?: string;
}

export const Dialog: React.FC<DialogProps> = ({
  isOpen,
  setIsOpen,
  title,
  content,
  children,
  onConfirm,
  onCancel,
  confirmLabel = 'Confirm',
  cancelLabel = 'Cancel',
  severity = 'info',
  canBeCanceled = true,
  shouldCloseOnConfirm = true,
  className,
}) => {
  const cancelBtnRef = useRef<HTMLButtonElement>(null);

  const handleClose = () => {
    if (canBeCanceled) {
      onCancel?.();
      setIsOpen(false);
    }
  };

  const handleConfirm = () => {
    onConfirm?.();
    if (shouldCloseOnConfirm) {
      setIsOpen(false);
    }
  };

  return (
    <ModalContainer isOpen={isOpen} onClose={handleClose}>
      <Card className={cn("w-full shadow-none border-0 bg-transparent", className)}>
        <Card.Header className="flex justify-between items-start pb-2">
          <h3 className="text-lg font-bold">{title}</h3>
          {canBeCanceled && (
            <button 
              className="btn btn-sm btn-circle btn-ghost" 
              onClick={handleClose}
              aria-label="Close dialog"
            >
              ✕
            </button>
          )}
        </Card.Header>
        
        <Card.Body className="py-2">
          {content && <p className="text-theme-foreground/80">{content}</p>}
          {children}
        </Card.Body>

        <Card.Footer className="justify-end gap-2 pt-6">
          {canBeCanceled && (
            <Button
              variant="ghost"
              onClick={handleClose}
              ref={cancelBtnRef}
              className="w-full sm:w-auto"
            >
              {cancelLabel}
            </Button>
          )}
          <Button
            variant={confirmVariants[severity] || 'primary'}
            onClick={handleConfirm}
            className="w-full sm:w-auto"
          >
            {confirmLabel}
          </Button>
        </Card.Footer>
      </Card>
    </ModalContainer>
  );
};
