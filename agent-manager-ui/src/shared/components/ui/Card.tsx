import React, { forwardRef } from 'react';
import { cn } from '../../utils/cn';

// Sub-component props
interface CardProps extends React.HTMLAttributes<HTMLDivElement> {
  variant?: 'default' | 'bordered' | 'compact';
}

const CardRoot = forwardRef<HTMLDivElement, CardProps>(({ 
  className, 
  variant = 'default', 
  ...props 
}, ref) => {
  const variants = {
    default: 'card bg-(--theme-card) border border-(--theme-muted)/10 shadow-sm',
    bordered: 'card bg-(--theme-card) border border-(--theme-muted)/10 shadow-sm',
    compact: 'card card-compact bg-(--theme-card) border border-(--theme-muted)/10 shadow-sm',
  };

  return (
    <div
      ref={ref}
      className={cn(variants[variant], className)}
      {...props}
    />
  );
});
CardRoot.displayName = 'Card';

const CardHeader = forwardRef<HTMLDivElement, React.HTMLAttributes<HTMLDivElement>>(({ className, ...props }, ref) => (
  <div ref={ref} className={cn("card-title px-6 pt-6", className)} {...props} />
));
CardHeader.displayName = 'Card.Header';

const CardBody = forwardRef<HTMLDivElement, React.HTMLAttributes<HTMLDivElement>>(({ className, ...props }, ref) => (
  <div ref={ref} className={cn("card-body", className)} {...props} />
));
CardBody.displayName = 'Card.Body';

const CardFooter = forwardRef<HTMLDivElement, React.HTMLAttributes<HTMLDivElement>>(({ className, ...props }, ref) => (
  <div ref={ref} className={cn("card-actions px-6 pb-6", className)} {...props} />
));
CardFooter.displayName = 'Card.Footer';

export const Card = Object.assign(CardRoot, {
  Header: CardHeader,
  Body: CardBody,
  Footer: CardFooter,
});
