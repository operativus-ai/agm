import React from 'react';
import { createPortal } from 'react-dom';

interface TooltipProps {
  label: string;
  children: React.ReactNode;
  enabled?: boolean;
}

/**
 * Portal-based tooltip. Renders content at document.body so it escapes
 * overflow-y:auto scroll containers (e.g. the collapsed sidebar nav).
 * Positions to the right of the trigger element.
 */
export const Tooltip: React.FC<TooltipProps> = ({ label, children, enabled = true }) => {
  const [visible, setVisible] = React.useState(false);
  const [coords, setCoords] = React.useState({ top: 0, left: 0 });
  const wrapperRef = React.useRef<HTMLDivElement>(null);

  if (!enabled) return <>{children}</>;

  const handleMouseEnter = () => {
    if (wrapperRef.current) {
      const rect = wrapperRef.current.getBoundingClientRect();
      setCoords({ top: rect.top + rect.height / 2, left: rect.right + 8 });
    }
    setVisible(true);
  };

  return (
    <div ref={wrapperRef} onMouseEnter={handleMouseEnter} onMouseLeave={() => setVisible(false)}>
      {children}
      {visible && createPortal(
        <div
          className="fixed z-[9999] -translate-y-1/2 bg-obsidian-elevated border border-obsidian-stroke rounded px-2 py-1 text-xs font-medium text-theme-foreground whitespace-nowrap pointer-events-none"
          style={{ top: coords.top, left: coords.left }}
        >
          {label}
        </div>,
        document.body
      )}
    </div>
  );
};
