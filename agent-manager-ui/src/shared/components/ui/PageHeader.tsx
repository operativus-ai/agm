import React from 'react';
import { Typography } from './Typography';

interface PageHeaderProps {
  /** Page icon component (e.g. LuBot) */
  icon?: React.ComponentType<{ className?: string }>;
  /** Page title */
  title: string;
  /** Subtitle / description text */
  subtitle?: string;
  /** Action buttons rendered on the right side */
  actions?: React.ReactNode;
}

export const PageHeader: React.FC<PageHeaderProps> = ({ icon: Icon, title, subtitle, actions }) => (
  <div className="flex justify-between items-start gap-4">
    <div className="flex items-start gap-3">
      {Icon && <Icon className="w-5 h-5 text-primary mt-1.5 shrink-0" />}
      <div>
        <Typography.Heading level={2} className="tracking-tight">
          {title}
        </Typography.Heading>
        {subtitle && (
          <Typography.Text className="text-sm text-(--theme-muted) mt-0.5">
            {subtitle}
          </Typography.Text>
        )}
      </div>
    </div>
    {actions && <div className="flex items-center gap-3 shrink-0">{actions}</div>}
  </div>
);
