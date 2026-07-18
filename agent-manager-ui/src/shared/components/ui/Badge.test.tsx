import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { Badge } from './Badge';

describe('Badge', () => {
  it('renders children text', () => {
    render(<Badge>Active</Badge>);
    expect(screen.getByText('Active')).toBeInTheDocument();
  });

  it('applies neutral variant by default', () => {
    const { container } = render(<Badge>Default</Badge>);
    const badge = container.querySelector('.badge');
    expect(badge?.className).toContain('badge-neutral');
  });

  it('applies success variant classes', () => {
    const { container } = render(<Badge variant="success">Online</Badge>);
    const badge = container.querySelector('.badge');
    expect(badge?.className).toContain('badge-success');
  });

  it('applies error variant classes', () => {
    const { container } = render(<Badge variant="error">Failed</Badge>);
    const badge = container.querySelector('.badge');
    expect(badge?.className).toContain('badge-error');
  });

  it('applies warning variant classes', () => {
    const { container } = render(<Badge variant="warning">Pending</Badge>);
    const badge = container.querySelector('.badge');
    expect(badge?.className).toContain('badge-warning');
  });

  it('applies outline modifier', () => {
    const { container } = render(<Badge outline>Outlined</Badge>);
    const badge = container.querySelector('.badge');
    expect(badge?.className).toContain('badge-outline');
  });

  it('applies size classes for non-default sizes', () => {
    const { container } = render(<Badge size="sm">Small</Badge>);
    const badge = container.querySelector('.badge');
    expect(badge?.className).toContain('badge-sm');
  });

  it('does not apply size class for default md size', () => {
    const { container } = render(<Badge size="md">Medium</Badge>);
    const badge = container.querySelector('.badge');
    expect(badge?.className).not.toContain('badge-md');
  });

  it('passes additional props', () => {
    render(<Badge data-testid="my-badge">Test</Badge>);
    expect(screen.getByTestId('my-badge')).toBeInTheDocument();
  });
});
