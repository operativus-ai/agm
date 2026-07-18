import { useCallback } from 'react';
import Editor, { type Monaco } from '@monaco-editor/react';
import { cn } from '../../utils/cn';
import { FormFieldWrapper } from './FormFieldWrapper';
import type { BaseFormFieldProps } from '../../types/component-props';

export interface MarkdownEditorProps extends BaseFormFieldProps {
  value?: string;
  onValueChange?: (value: string | undefined) => void;
  language?: 'markdown' | 'json';
  height?: string;
  placeholder?: string;
}

export const MarkdownEditor = ({
  size = 'md',
  label,
  description,
  error,
  required,
  disabled,
  loading,
  value,
  onValueChange,
  language = 'markdown',
  height = '300px',
  className,
  id,
}: MarkdownEditorProps) => {
  const editorId = id || `editor-${Math.random().toString(36).substr(2, 9)}`;
  const hasError = Boolean(error && typeof error === 'string');

  const handleEditorWillMount = useCallback((monaco: Monaco) => {
    monaco.editor.defineTheme('obsidian-control', {
      base: 'vs-dark',
      inherit: true,
      rules: [],
      colors: {
        'editor.background': '#0B0E14', // --obsidian-base
        'editor.lineHighlightBackground': '#11141B', // --obsidian-surface
        'editorLineNumber.foreground': '#334155', // --obsidian-stroke
        'minimap.background': '#0B0E14',
        'editorWidget.background': '#1E293B', // --obsidian-elevated
        'editorWidget.border': '#334155',
      }
    });
  }, []);

  const handleChange = useCallback((val: string | undefined) => {
    onValueChange?.(val);
  }, [onValueChange]);

  const editorContainerClasses = cn(
    'w-full border rounded-md overflow-hidden bg-obsidian-base isolate',
    hasError ? 'border-error-red' : 'border-obsidian-stroke focus-within:border-agent-blue transition-colors duration-200',
    className
  );

  const editorElement = (
    <div className="relative flex-1 min-h-[200px] h-full flex flex-col">
      <div className={cn(editorContainerClasses, "flex-1")} style={height !== '100%' ? { height } : undefined}>
        <Editor
          height="100%"
          language={language}
          theme="obsidian-control"
          value={value}
          onChange={handleChange}
          beforeMount={handleEditorWillMount}
          options={{
            minimap: { enabled: false },
            wordWrap: 'on',
            readOnly: disabled || loading,
            padding: { top: 16, bottom: 16 },
            fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace',
            fontSize: 14,
            lineHeight: 1.6,
            scrollBeyondLastLine: false,
            renderLineHighlight: 'all',
            overviewRulerBorder: false,
            hideCursorInOverviewRuler: true,
            scrollbar: {
              vertical: 'hidden',
              horizontal: 'hidden'
            }
          }}
        />
      </div>

      {loading && (
        <div className="absolute right-3 top-3 pointer-events-none z-10">
          <span className="loading loading-spinner loading-sm text-agent-blue"></span>
        </div>
      )}
    </div>
  );

  return (
    <FormFieldWrapper
      label={label}
      description={description}
      error={error}
      required={required}
      loading={loading}
      size={size}
      htmlFor={editorId}
      className={height === '100%' ? 'h-full flex flex-col' : ''}
    >
      {editorElement}
    </FormFieldWrapper>
  );
};
