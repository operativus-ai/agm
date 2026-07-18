import React from 'react';
import { NavLink } from 'react-router-dom';
import { LuArrowLeft, LuCircleAlert } from 'react-icons/lu';

export const NotFoundPage: React.FC = () => {
  return (
    <div className="flex flex-col items-center justify-center h-full min-h-[60vh] text-center gap-6">
      <LuCircleAlert className="w-16 h-16 text-obsidian-stroke" />
      <div className="space-y-2">
        <h1 className="text-4xl font-bold font-mono text-theme-foreground">404</h1>
        <p className="text-theme-muted text-sm">Page not found</p>
      </div>
      <NavLink
        to="/"
        className="flex items-center gap-2 px-4 py-2 rounded-md bg-agent-blue/10 text-agent-blue border border-agent-blue/20 hover:bg-agent-blue/20 transition-colors text-sm font-medium"
      >
        <LuArrowLeft className="w-4 h-4" />
        Back to Dashboard
      </NavLink>
    </div>
  );
};
