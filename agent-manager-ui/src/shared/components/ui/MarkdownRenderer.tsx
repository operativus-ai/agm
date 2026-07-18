import React from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { Prism as SyntaxHighlighter } from 'react-syntax-highlighter';
import { vscDarkPlus } from 'react-syntax-highlighter/dist/esm/styles/prism';
import { cn } from '../../utils/cn';

export interface MarkdownRendererProps {
  content: string;
  className?: string;
  'data-testid'?: string;
}

export const MarkdownRenderer: React.FC<MarkdownRendererProps> = ({
  content,
  className,
  'data-testid': testId,
}) => {
  return (
    <div 
      className={cn(
        "prose prose-invert prose-base max-w-none break-words",
        className
      )}
      data-testid={testId}
    >
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={{
          code({ node, inline, className, children, ...props }: any) {
            const match = /language-(\w+)/.exec(className || '');
            const isBlock = !inline && match;
            
            return isBlock ? (
              <SyntaxHighlighter
                style={vscDarkPlus as any}
                language={match[1]}
                PreTag="div"
                customStyle={{
                  margin: 0,
                  borderRadius: '0.375rem',
                  backgroundColor: 'var(--obsidian-elevated)',
                  border: '1px solid var(--obsidian-stroke)',
                }}
                {...props}
              >
                {String(children).replace(/\n$/, '')}
              </SyntaxHighlighter>
            ) : (
              <code className={cn("bg-obsidian-elevated px-1.5 py-0.5 rounded-md border border-obsidian-stroke font-mono text-sm", className)} {...props}>
                {children}
              </code>
            );
          },
          table: ({ node, ...props }: any) => (
            <div className="overflow-x-auto my-4 border border-obsidian-stroke rounded-lg">
              <table className="table w-full m-0" {...props} />
            </div>
          ),
          th: ({ node, ...props }: any) => <th className="bg-obsidian-surface text-theme-foreground border-b border-obsidian-stroke" {...props} />,
          td: ({ node, ...props }: any) => <td className="border-b border-obsidian-stroke" {...props} />
        }}
      >
        {content}
      </ReactMarkdown>
    </div>
  );
};
