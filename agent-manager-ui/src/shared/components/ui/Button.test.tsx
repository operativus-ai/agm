import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { Button } from './Button';

describe('Button', () => {
  it('renders children text', () => {
    render(<Button>Click Me</Button>);
    expect(screen.getByText('Click Me')).toBeInTheDocument();
  });

  it('calls onClick when clicked', () => {
    const onClick = vi.fn();
    render(<Button onClick={onClick}>Click</Button>);
    fireEvent.click(screen.getByText('Click'));
    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it('is disabled when disabled prop is true', () => {
    render(<Button disabled>Disabled</Button>);
    expect(screen.getByText('Disabled')).toBeDisabled();
  });

  it('is disabled when loading', () => {
    render(<Button loading>Loading</Button>);
    expect(screen.getByText('Loading').closest('button')).toBeDisabled();
  });

  it('shows loading spinner when loading', () => {
    const { container } = render(<Button loading>Loading</Button>);
    expect(container.querySelector('.loading-spinner')).toBeInTheDocument();
  });

  it('does not show loading spinner when not loading', () => {
    const { container } = render(<Button>Normal</Button>);
    expect(container.querySelector('.loading-spinner')).not.toBeInTheDocument();
  });

  it('applies primary variant classes by default', () => {
    const { container } = render(<Button>Primary</Button>);
    const button = container.querySelector('button');
    expect(button?.className).toContain('btn-primary');
  });

  it('applies danger variant classes', () => {
    const { container } = render(<Button variant="danger">Delete</Button>);
    const button = container.querySelector('button');
    expect(button?.className).toContain('btn-error');
  });

  it('applies fullWidth class', () => {
    const { container } = render(<Button fullWidth>Wide</Button>);
    const button = container.querySelector('button');
    expect(button?.className).toContain('w-full');
  });

  it('applies size classes', () => {
    const { container } = render(<Button size="sm">Small</Button>);
    const button = container.querySelector('button');
    expect(button?.className).toContain('btn-sm');
  });
});
