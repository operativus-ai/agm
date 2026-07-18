import { cn } from '../../utils/cn';
import React, { createContext, useContext, useState } from 'react';

interface TabsContextValue {
  activeTab: string;
  setActiveTab: (value: string) => void;
}

const TabsContext = createContext<TabsContextValue | undefined>(undefined);

export interface TabsProps {
  children: React.ReactNode;
  defaultValue: string;
  value?: string;
  onValueChange?: (value: string) => void;
  className?: string;
}

export const TabsRoot: React.FC<TabsProps> = ({
  children,
  defaultValue,
  value,
  onValueChange,
  className,
}) => {
  const [ activeTab, setActiveTab ] = useState(value || defaultValue);

  const handleTabChange = (newValue: string) => {
    if (value === undefined) {
      setActiveTab(newValue);
    }
    onValueChange?.(newValue);
  };

  const contextValue = {
    activeTab: value || activeTab,
    setActiveTab: handleTabChange,
  };

  return (
    <TabsContext value={contextValue}>
      <div className={cn('w-full', className)}>
        {children}
      </div>
    </TabsContext>
  );
};

export interface TabsListProps {
  children: React.ReactNode;
  className?: string;
}

export const TabsList: React.FC<TabsListProps> = ({ children, className }) => (
  <div role="tablist" className={cn('tabs tabs-boxed bg-(--theme-card) border border-(--theme-muted)/10 p-1', className)}>
    {children}
  </div>
);

export interface TabsTriggerProps {
  children: React.ReactNode;
  value: string;
  className?: string;
}

export const TabsTrigger: React.FC<TabsTriggerProps> = ({ children, value, className }) => {
  const context = useContext(TabsContext);
  if (!context) {
    throw new Error('TabsTrigger must be used within Tabs');
  }

  const { activeTab, setActiveTab } = context;
  const isActive = activeTab === value;

  return (
    <button
      role="tab"
      aria-selected={isActive}
      className={cn(
        'tab transition-all duration-200',
        {
          'tab-active bg-(--theme-background) shadow-sm font-medium text-(--theme-foreground)': isActive,
          'text-(--theme-muted) hover:text-(--theme-foreground)': !isActive,
        },
        className,
      )}
      onClick={() => setActiveTab(value)}
    >
      {children}
    </button>
  );
};

export interface TabsContentProps {
  children: React.ReactNode;
  value: string;
  className?: string;
}

export const TabsContent: React.FC<TabsContentProps> = ({ children, value, className }) => {
  const context = useContext(TabsContext);
  if (!context) {
    throw new Error('TabsContent must be used within Tabs');
  }

  const { activeTab } = context;

  if (activeTab !== value) {
    return null;
  }

  return (
    <div 
      role="tabpanel" 
      className={cn('mt-4 animate-in fade-in zoom-in-95 duration-200', className)}
    >
      {children}
    </div>
  );
};

export const TabsComposite = Object.assign(TabsRoot, {
  List: TabsList,
  Trigger: TabsTrigger,
  Content: TabsContent,
});

export { TabsComposite as Tabs };
export default TabsComposite;
