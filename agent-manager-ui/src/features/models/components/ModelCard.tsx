import React from 'react';
import type { ModelConfig } from '../types/models.types';
import { Card } from '../../../shared/components/ui/Card';
import { Badge } from '../../../shared/components/ui/Badge';
import { Button } from '../../../shared/components/ui/Button';
import { Typography } from '../../../shared/components/ui/Typography';
import { LuPencil, LuTrash2 } from 'react-icons/lu';
import { ProviderIcon } from './ProviderIcon';

interface ModelCardProps {
  model: ModelConfig;
  onEdit?: (model: ModelConfig) => void;
  onDelete?: (model: ModelConfig) => void;
}

export const ModelCard: React.FC<ModelCardProps> = ({ model, onEdit, onDelete }) => {
  return (
    <Card className="hover:border-primary/50 transition-colors duration-300">
      <Card.Body className="space-y-4">
        <div className="flex items-start justify-between">
          <div className="flex items-center gap-3">
             <div className="p-2 bg-obsidian-elevated/50 rounded-lg">
                 <ProviderIcon provider={model.provider} size={24} />
             </div>
             <div>
                <Typography.Heading level={4} className="leading-none">{model.name}</Typography.Heading>
                <Typography.Text variant="small" className="text-muted-foreground">{model.provider}</Typography.Text>
             </div>
          </div>
        </div>
        
        <Typography.Text className="line-clamp-2 min-h-6 font-mono text-sm opacity-80">
            {model.modelName || 'System Default'}
        </Typography.Text>

        <div className="flex flex-wrap gap-1 mt-2">
            <Badge variant="secondary" outline className="text-[10px] bg-secondary/10">
                {model.modelType === 'EMBEDDING' ? 'Embedding' : 'Chat'}
            </Badge>
            {/* §7 Model Pinger: tri-state liveness badge.
                undefined → no badge (never pinged); true → green Live; false → amber Unavailable.
                Tooltip carries the last-pinged-at instant so operators can judge staleness. */}
            {model.available === true && (
                <Badge
                    variant="neutral"
                    outline
                    className="text-[10px] text-success border-success/30 bg-success/10"
                    title={model.lastPingedAt ? `Last checked ${new Date(model.lastPingedAt).toLocaleString()}` : undefined}
                >
                    Live
                </Badge>
            )}
            {model.available === false && (
                <Badge
                    variant="neutral"
                    outline
                    className="text-[10px] text-warning border-warning/40 bg-warning/10"
                    title={model.lastPingedAt ? `Last checked ${new Date(model.lastPingedAt).toLocaleString()}` : undefined}
                >
                    Unavailable
                </Badge>
            )}
            {model.supportsTools !== false && <Badge variant="neutral" outline className="text-[10px]">Tools</Badge>}
            {model.supportsSystemInstructions !== false && <Badge variant="neutral" outline className="text-[10px]">System</Badge>}
            {model.supportsVision === true && <Badge variant="neutral" outline className="text-[10px] text-info border-info/30 bg-info/10">Vision</Badge>}
            {model.maxContextTokens && <Badge variant="neutral" outline className="text-[10px] opacity-70">{Math.round(model.maxContextTokens / 1000)}k Ctx</Badge>}
            {model.maxOutputTokens && <Badge variant="neutral" outline className="text-[10px] opacity-70">{model.maxOutputTokens} Out</Badge>}
        </div>

        <div className="pt-2 flex items-center justify-between border-t border-base-200 dark:border-base-800">
            <Typography.Text variant="small" className="font-mono opacity-50 text-[10px] truncate max-w-37.5">
                {model.id}
            </Typography.Text>
            <div className="flex gap-2">
                {onEdit && (
                    <Button 
                        size="sm" 
                        variant="ghost" 
                        title="Edit Model"
                        onClick={() => onEdit(model)}
                    >
                        <LuPencil className="text-muted-foreground hover:text-primary transition-colors" />
                    </Button>
                )}
                {onDelete && (
                    <Button 
                        size="sm" 
                        variant="ghost" 
                        title="Delete Model"
                        className="text-error hover:bg-error/10"
                        onClick={() => onDelete(model)}
                    >
                        <LuTrash2 />
                    </Button>
                )}
            </div>
        </div>
      </Card.Body>
    </Card>
  );
};
