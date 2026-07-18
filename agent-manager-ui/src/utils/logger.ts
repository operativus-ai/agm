type LogLevel = 'DEBUG' | 'INFO' | 'WARN' | 'ERROR';

// Colors for console styling
const colors: Record<LogLevel, string> = {
  DEBUG: '#808080', // Gray
  INFO: '#2196F3',  // Blue
  WARN: '#FF9800',  // Orange
  ERROR: '#F44336'  // Red
};

// Check if we are in development mode
const isDev = import.meta.env.DEV;

function log(level: LogLevel, message: string, ...args: any[]) {
  // Suppress DEBUG and INFO in production
  if (!isDev && (level === 'DEBUG' || level === 'INFO')) {
    return;
  }

  // Format: [LEVEL] Message
  const prefix = `[%c${level}%c] ${message}`;
  const cssLevel = `color: ${colors[level]}; font-weight: bold;`;
  const cssReset = 'color: inherit; font-weight: normal;';

  // Apply basic PII masking for strings in the arguments (e.g. Bearer tokens)
  const maskedArgs = args.map(arg => {
      if (typeof arg === 'string') {
          return arg.replace(/(Bearer\s+)[A-Za-z0-9-_=\.]+/g, '$1***');
      }
      return arg;
  });

  switch (level) {
    case 'DEBUG':
      console.debug(prefix, cssLevel, cssReset, ...maskedArgs);
      break;
    case 'INFO':
      console.info(prefix, cssLevel, cssReset, ...maskedArgs);
      break;
    case 'WARN':
      console.warn(prefix, cssLevel, cssReset, ...maskedArgs);
      break;
    case 'ERROR':
      console.error(prefix, cssLevel, cssReset, ...maskedArgs);
      // Here we could easily add integration with Sentry, Datadog, etc. later.
      break;
  }
}

export const logger = {
  debug: (message: string, ...args: any[]) => log('DEBUG', message, ...args),
  info: (message: string, ...args: any[]) => log('INFO', message, ...args),
  warn: (message: string, ...args: any[]) => log('WARN', message, ...args),
  error: (message: string, ...args: any[]) => log('ERROR', message, ...args),
};
