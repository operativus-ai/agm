import { useEffect } from 'react';

/**
 * Module-level stack of open-modal close handlers. A single shared document
 * listener invokes only the top-most one, so pressing Escape with nested modals
 * open closes just the inner-most (children mount last, so their handler sits on
 * top). Independent per-hook listeners would instead close the whole stack at
 * once.
 */
const closeStack: Array<() => void> = [];
let listening = false;

function handleKeyDown(e: KeyboardEvent) {
  if (e.key !== 'Escape') return;
  const top = closeStack[closeStack.length - 1];
  if (top) {
    e.stopPropagation();
    top();
  }
}

/**
 * Closes a modal when Escape is pressed.
 *
 * For raw daisyUI modals (`<div className="modal modal-open">`) that neither go
 * through `ModalContainer` (which already handles Escape) nor use
 * `useUnsavedChangesGuard` (which binds its own Escape). Call it unconditionally
 * at the top of the component — before any early return — to satisfy the Rules
 * of Hooks, passing the open flag as `isOpen` so an always-mounted modal (the
 * `if (!isOpen) return null` pattern) doesn't listen while hidden.
 */
export function useEscapeToClose(onClose: () => void, isOpen: boolean = true) {
  useEffect(() => {
    if (!isOpen) return;
    const handler = () => onClose();
    closeStack.push(handler);
    if (!listening) {
      document.addEventListener('keydown', handleKeyDown);
      listening = true;
    }
    return () => {
      const i = closeStack.lastIndexOf(handler);
      if (i !== -1) closeStack.splice(i, 1);
    };
  }, [isOpen, onClose]);
}
