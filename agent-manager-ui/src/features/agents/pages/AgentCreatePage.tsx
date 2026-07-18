import React, { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { configApi } from '../../../shared/api/configApi';
import type { AgentTemplateDTO } from '../../../shared/api/configApi';
import { PageHeader } from '../../../shared/components/ui/PageHeader';
import { Button } from '../../../shared/components/ui/Button';
import { Alert } from '../../../shared/components/ui/Alert';
import { TemplatePickerGrid } from '../../../shared/components/ui/TemplatePickerGrid';
import type { TemplateCardItem } from '../../../shared/components/ui/TemplatePickerGrid';
import { LuArrowLeft, LuLayoutGrid } from 'react-icons/lu';
import { PageContainer } from '../../../shared/components/ui/PageContainer';

const TEMPLATE_ICONS: Record<string, string> = {
  chat: '\u{1F4AC}',
  search: '\u{1F50D}',
  'dollar-sign': '\u{1F4B0}',
  code: '\u{1F4BB}',
  globe: '\u{1F310}',
  braces: '\u{1F4CB}',
  headphones: '\u{1F3A7}',
  'pen-tool': '\u{270F}\u{FE0F}',
  shield: '\u{1F6E1}\u{FE0F}',
  database: '\u{1F5C4}\u{FE0F}',
  settings: '\u{2699}\u{FE0F}',
};

export const AgentCreatePage: React.FC = () => {
  const navigate = useNavigate();
  const [templates, setTemplates] = useState<AgentTemplateDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    configApi.getAgentTemplates()
      .then(data => { if (Array.isArray(data)) setTemplates(data); })
      .catch(() => setError('Failed to load templates. You can still create an agent manually.'))
      .finally(() => setLoading(false));
  }, []);

  const handleSelect = (templateId: string) => {
    navigate(`/agents?create=${templateId}`);
  };

  const items: TemplateCardItem[] = templates.map(tpl => ({
    id: tpl.id,
    icon: TEMPLATE_ICONS[tpl.icon] || '\u{2699}\u{FE0F}',
    name: tpl.name,
    description: tpl.description,
    badge: tpl.finOpsRiskTier || undefined,
    metadata: tpl.defaultTools && tpl.defaultTools.length > 0
      ? `${tpl.defaultTools.length} tool${tpl.defaultTools.length !== 1 ? 's' : ''} pre-configured`
      : undefined,
  }));

  return (
    <PageContainer>
      <div className="flex items-center gap-4">
        <Button variant="ghost" size="sm" className="shrink-0" onClick={() => navigate('/agents')}>
          <LuArrowLeft className="w-4 h-4" />
        </Button>
        <PageHeader
          icon={LuLayoutGrid}
          title="Choose an Agent Profile"
          subtitle="Select a pre-configured template to get started quickly, or start from scratch."
        />
      </div>

      {error && <Alert severity="warning">{error}</Alert>}

      <TemplatePickerGrid items={items} onSelect={handleSelect} loading={loading} />
    </PageContainer>
  );
};
