import React from 'react';
import { Badge } from './Badge';

export interface TemplateCardItem {
  id: string;
  icon: React.ReactNode;
  name: string;
  description: string;
  badge?: string;
  metadata?: string;
}

interface TemplatePickerGridProps {
  items: TemplateCardItem[];
  onSelect: (id: string) => void;
  loading?: boolean;
  skeletonCount?: number;
}

/**
 * Standardized template selection grid used across Teams, Agents, and Workflows.
 * Renders a responsive card grid with consistent sizing, hover states, and layout.
 */
export const TemplatePickerGrid: React.FC<TemplatePickerGridProps> = ({
  items,
  onSelect,
  loading,
  skeletonCount = 6,
}) => {
  if (loading) {
    return (
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
        {Array.from({ length: skeletonCount }, (_, i) => (
          <div key={i} className="h-40 bg-obsidian-elevated/50 rounded-xl animate-pulse" />
        ))}
      </div>
    );
  }

  return (
    <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
      {items.map(item => (
        <button
          key={item.id}
          type="button"
          className="group text-left bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl p-6 shadow-sm hover:border-primary/40 hover:shadow-lg transition-all duration-200 cursor-pointer flex flex-col"
          onClick={() => onSelect(item.id)}
        >
          <div className="flex items-start justify-between mb-3">
            <span className="text-2xl">{item.icon}</span>
            {item.badge && (
              <Badge variant="ghost" className="text-[10px] font-mono uppercase">
                {item.badge}
              </Badge>
            )}
          </div>
          <div className="font-semibold text-(--theme-foreground) mb-1 group-hover:text-primary transition-colors">
            {item.name}
          </div>
          <div className="text-xs text-(--theme-muted) leading-relaxed mb-3 flex-1">
            {item.description}
          </div>
          {item.metadata && (
            <div className="text-[10px] text-(--theme-muted) uppercase tracking-wider font-bold">
              {item.metadata}
            </div>
          )}
        </button>
      ))}
    </div>
  );
};
