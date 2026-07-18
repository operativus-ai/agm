import { Component, type ErrorInfo, type ReactNode } from 'react';
import { logger } from '../../utils/logger';

interface Props {
  children?: ReactNode;
  fallback?: ReactNode;
}

interface State {
  hasError: boolean;
  error: Error | null;
}

export class ErrorBoundary extends Component<Props, State> {
  public state: State = {
    hasError: false,
    error: null
  };

  public static getDerivedStateFromError(error: Error): State {
    // Update state so the next render will show the fallback UI.
    return { hasError: true, error };
  }

  public componentDidCatch(error: Error, errorInfo: ErrorInfo) {
    logger.error('Uncaught component error:', error, errorInfo.componentStack);
  }

  public render() {
    if (this.state.hasError) {
      if (this.props.fallback) {
          return this.props.fallback;
      }
      return (
        <div className="flex flex-col items-center justify-center min-h-[400px] p-8 text-center bg-base-200 rounded-box border border-error/20">
          <h2 className="text-2xl font-bold text-error mb-4">Something went wrong</h2>
          <p className="text-base-content/70 mb-6">
            An unexpected error occurred in this section of the application.
          </p>
          {import.meta.env.DEV && this.state.error && (
            <div className="w-full text-left bg-base-300 p-4 rounded-lg overflow-auto max-h-[300px]">
                <pre className="text-xs text-error font-mono whitespace-pre-wrap">
                    {this.state.error.toString()}
                </pre>
            </div>
          )}
          <button
            className="btn btn-primary mt-6"
            onClick={() => this.setState({ hasError: false, error: null })}
          >
            Try Again
          </button>
        </div>
      );
    }

    return this.props.children;
  }
}
