/**
 * STANDARDIZED COMPONENT PROP INTERFACES
 * Ported from frontend DNA
 */

import React from 'react';

// ===========================
// BASE PROPS
// ===========================

export interface BaseComponentProps {
  className?: string;
  'data-testid'?: string;
  style?: React.CSSProperties;
  children?: React.ReactNode;
}

export interface InteractiveProps extends BaseComponentProps {
  disabled?: boolean;
  onClick?: (event: React.MouseEvent) => void;
  onKeyDown?: (event: React.KeyboardEvent) => void;
  tabIndex?: number;
  role?: string;
}

export interface AccessibilityProps {
  'aria-label'?: string;
  'aria-describedby'?: string;
  'aria-labelledby'?: string;
  'aria-expanded'?: boolean;
  'aria-pressed'?: boolean;
  'aria-disabled'?: boolean;
}

// ===========================
// THEME PROPS
// ===========================

export type AutomotiveTheme = 'racing' | 'chrome' | 'carbon' | 'steel' | 'default';

export interface AutomotiveThemeProps {
  theme?: AutomotiveTheme;
  variant?: 'primary' | 'secondary' | 'accent' | 'neutral' | 'ghost' | 'outline';
}

export type ComponentSize = 'xs' | 'sm' | 'md' | 'lg' | 'xl';

export interface SizeProps {
  size?: ComponentSize;
}

// ===========================
// FORM PROPS
// ===========================

export interface BaseFormFieldProps extends BaseComponentProps, AccessibilityProps, SizeProps {
  label?: string;
  description?: string;
  /** Tooltip text shown on hover over an info icon next to the label */
  helpText?: string;
  error?: string | boolean;
  required?: boolean;
  disabled?: boolean;
  loading?: boolean;
  name?: string;
  id?: string;
}

export interface InputProps extends BaseFormFieldProps, Omit<React.InputHTMLAttributes<HTMLInputElement>, 'size' | 'disabled' | 'type' | 'name' | 'aria-label' | 'aria-describedby' | 'aria-labelledby' | 'aria-expanded' | 'aria-pressed' | 'aria-disabled'> {
  type?: 'text' | 'email' | 'password' | 'number' | 'tel' | 'url' | 'search';
  placeholder?: string;
  startIcon?: React.ComponentType<{ className?: string }>;
  endIcon?: React.ComponentType<{ className?: string }>;
  /** When set, the endIcon renders inside a button instead of a decorative span.
   *  Use for actionable adornments like password-visibility toggles. */
  onEndIconClick?: () => void;
  /** Accessible label for the endIcon button. Required when {@link onEndIconClick} is set. */
  endIconAriaLabel?: string;
  onValueChange?: (value: string) => void;
}

export interface SelectOption {
  value: string;
  label: string;
  disabled?: boolean;
  group?: string;
}

export interface SelectProps extends BaseFormFieldProps, Omit<React.SelectHTMLAttributes<HTMLSelectElement>, 'size' | 'disabled' | 'name' | 'aria-label' | 'aria-describedby' | 'aria-labelledby' | 'aria-expanded' | 'aria-pressed' | 'aria-disabled'> {
  options: SelectOption[];
  placeholder?: string;
  allowEmpty?: boolean;
  onValueChange?: (value: string) => void;
}

export interface TextareaProps extends BaseFormFieldProps, Omit<React.TextareaHTMLAttributes<HTMLTextAreaElement>, 'disabled' | 'name' | 'aria-label' | 'aria-describedby' | 'aria-labelledby' | 'aria-expanded' | 'aria-pressed' | 'aria-disabled'> {
  placeholder?: string;
  autoResize?: boolean;
  minRows?: number;
  maxRows?: number;
  onValueChange?: (value: string) => void;
}

export interface CheckboxProps extends BaseFormFieldProps, Omit<React.InputHTMLAttributes<HTMLInputElement>, 'size' | 'disabled' | 'type' | 'name' | 'aria-label' | 'aria-describedby' | 'aria-labelledby' | 'aria-expanded' | 'aria-pressed' | 'aria-disabled'> {
  checked?: boolean;
  indeterminate?: boolean;
  onCheckedChange?: (checked: boolean) => void;
}

export interface RadioProps extends BaseFormFieldProps, Omit<React.InputHTMLAttributes<HTMLInputElement>, 'size' | 'disabled' | 'type' | 'name' | 'aria-label' | 'aria-describedby' | 'aria-labelledby' | 'aria-expanded' | 'aria-pressed' | 'aria-disabled'> {
  value: string;
  checked?: boolean;
  onValueChange?: (value: string) => void;
}

export interface ButtonProps extends BaseComponentProps, Omit<AutomotiveThemeProps, 'variant'>, AccessibilityProps, SizeProps, Omit<React.ButtonHTMLAttributes<HTMLButtonElement>, 'disabled' | 'aria-label' | 'aria-describedby' | 'aria-labelledby' | 'aria-expanded' | 'aria-pressed' | 'aria-disabled'> {
  variant?: 'primary' | 'secondary' | 'outline' | 'ghost' | 'link' | 'destructive' | 'danger';
  automotiveVariant?: 'racing' | 'chrome' | 'carbon' | 'steel';
  disabled?: boolean;
  loading?: boolean;
  fullWidth?: boolean;
  startIcon?: React.ComponentType<{ className?: string }>;
  endIcon?: React.ComponentType<{ className?: string }>;
  asChild?: boolean;
}

export interface ValidationResult {
  isValid: boolean;
  error?: string;
  fieldErrors?: Record<string, string>;
}

// ===========================
// FEEDBACK PROPS
// ===========================

export type AlertSeverity = 'info' | 'success' | 'warning' | 'error';

export interface AlertProps extends BaseComponentProps, AccessibilityProps {
  severity?: AlertSeverity;
  title?: string;
  description?: string;
  dismissible?: boolean;
  onClose?: () => void;
  icon?: React.ComponentType<{ className?: string }>;
  actions?: React.ReactNode;
}

export interface BadgeProps extends BaseComponentProps, Omit<AutomotiveThemeProps, 'variant'>, SizeProps {
  variant?: 'default' | 'primary' | 'secondary' | 'outline' | 'destructive' | 'info' | 'success' | 'warning' | 'error';
  outline?: boolean;
}

// ===========================
// MODAL PROPS
// ===========================

export interface ModalProps extends BaseComponentProps, AccessibilityProps {
  open: boolean;
  onClose: () => void;
  title?: string;
  description?: string;
  size?: 'sm' | 'md' | 'lg' | 'xl' | 'full';
  closeOnOverlayClick?: boolean;
  closeOnEscape?: boolean;
  showCloseButton?: boolean;
  header?: React.ReactNode;
  footer?: React.ReactNode;
}
