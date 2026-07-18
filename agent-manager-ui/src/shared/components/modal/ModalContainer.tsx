import React, { useEffect } from 'react';
import { cn } from '../../utils/cn';

interface ModalContainerProps {
  className?: string;
  children?: React.ReactNode;
  isOpen?: boolean;
  onClose?: () => void;
}

export const ModalContainer: React.FC<ModalContainerProps> = ({
  className,
  children,
  isOpen = false,
  onClose,
}) => {
  // Escape closes the modal (mirrors the backdrop click). Registered on document so it fires
  // regardless of focus. Hook runs before the early return to satisfy the Rules of Hooks.
  useEffect(() => {
    if (!isOpen) return;
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose?.();
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [isOpen, onClose]);

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center">
      <div
        className="fixed inset-0 bg-black/50 backdrop-blur-sm transition-opacity"
        onClick={onClose}
        aria-hidden="true"
      />
      <div 
        className={cn(
          "relative bg-obsidian-elevated text-theme-foreground border border-obsidian-stroke rounded-lg shadow-2xl max-w-md w-full mx-4 animate-in fade-in zoom-in-95 duration-200", 
          className
        )}
        role="dialog"
        aria-modal="true"
      >
        {children}
      </div>
    </div>
  );
};

export default ModalContainer;
