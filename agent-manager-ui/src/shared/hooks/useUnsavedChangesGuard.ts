import { useState, useCallback, useRef, useEffect } from 'react';

/**
 * Hook that guards modal/page close actions when form data has been modified.
 * Shows a confirmation dialog before discarding unsaved changes.
 *
 * Usage in a modal:
 * ```tsx
 * const { dirty, markDirty, guardedClose, confirmDialog } = useUnsavedChangesGuard(onClose);
 *
 * // Mark dirty on any form change
 * const handleChange = (e) => { markDirty(); setFormData(...); };
 *
 * // Use guardedClose instead of onClose for X button, Cancel button, backdrop click
 * <button onClick={guardedClose}>Cancel</button>
 *
 * // Render the confirmation dialog
 * {confirmDialog}
 * ```
 */
export function useUnsavedChangesGuard(onClose: () => void, isOpen: boolean = true) {
  const [dirty, setDirty] = useState(false);
  const [showConfirm, setShowConfirm] = useState(false);
  const dirtyRef = useRef(false);

  const markDirty = useCallback(() => {
    if (!dirtyRef.current) {
      dirtyRef.current = true;
      setDirty(true);
    }
  }, []);

  const resetDirty = useCallback(() => {
    dirtyRef.current = false;
    setDirty(false);
  }, []);

  const guardedClose = useCallback(() => {
    if (dirtyRef.current) {
      setShowConfirm(true);
    } else {
      onClose();
    }
  }, [onClose]);

  const confirmLeave = useCallback(() => {
    setShowConfirm(false);
    dirtyRef.current = false;
    setDirty(false);
    onClose();
  }, [onClose]);

  const cancelLeave = useCallback(() => {
    setShowConfirm(false);
  }, []);

  // Escape acts as Cancel while the modal is open: if the unsaved-changes
  // confirm is showing, Escape dismisses it (Stay); otherwise it runs the
  // guarded close (which prompts before discarding edits, exactly like the
  // Cancel button). Gated on isOpen so always-mounted modals (the
  // `if (!isOpen) return null` pattern) don't listen while hidden.
  useEffect(() => {
    if (!isOpen) return;
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return;
      e.stopPropagation();
      if (showConfirm) {
        cancelLeave();
      } else {
        guardedClose();
      }
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [isOpen, showConfirm, guardedClose, cancelLeave]);

  return {
    dirty,
    markDirty,
    resetDirty,
    guardedClose,
    showConfirm,
    confirmLeave,
    cancelLeave,
  };
}
