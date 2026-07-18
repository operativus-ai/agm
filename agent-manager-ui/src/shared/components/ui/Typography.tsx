import React from 'react';
import { cn } from '../../utils/cn';

interface HeadingProps extends React.HTMLAttributes<HTMLHeadingElement> {
  level?: 1 | 2 | 3 | 4 | 5 | 6;
  variant?: 'h1' | 'h2' | 'h3' | 'h4' | 'h5' | 'h6';
}

const Heading: React.FC<HeadingProps> = ({ 
  level = 1, 
  variant, 
  className, 
  children, 
  ...props 
}) => {
  const Tag = `h${level}` as React.ElementType;
  const styleVariant = variant || `h${level}`;

  const styles = {
    h1: 'text-4xl font-bold tracking-tight',
    h2: 'text-3xl font-semibold tracking-tight',
    h3: 'text-2xl font-semibold',
    h4: 'text-xl font-medium',
    h5: 'text-lg font-medium',
    h6: 'text-base font-medium',
  };

  return (
    <Tag className={cn(styles[styleVariant as keyof typeof styles], className)} {...props}>
      {children}
    </Tag>
  );
};

interface TextProps extends React.HTMLAttributes<HTMLParagraphElement> {
  variant?: 'body' | 'small' | 'tiny' | 'muted' | 'lead';
  as?: React.ElementType;
}

const Text: React.FC<TextProps> = ({ 
  variant = 'body', 
  as: Component = 'p', 
  className, 
  children, 
  ...props 
}) => {
  const styles = {
    body: 'text-base text-theme-foreground',
    small: 'text-sm text-theme-foreground/80',
    tiny: 'text-xs text-theme-muted',
    muted: 'text-sm text-theme-muted',
    lead: 'text-xl text-theme-foreground/90 font-light',
  };

  return (
    <Component className={cn(styles[variant], className)} {...props}>
      {children}
    </Component>
  );
};

export const Typography = {
  Heading,
  Text,
};
