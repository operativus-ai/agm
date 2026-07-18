import React from 'react';
import type { EvaluationSuite } from '../../../shared/types/evaluation';
import { Typography } from '../../../shared/components/ui/Typography';
import { useEvaluationStore } from '../store/evaluationStore';

interface SuiteCardProps {
  suite: EvaluationSuite;
}

export const SuiteCard: React.FC<SuiteCardProps> = ({ suite }) => {
  const { deleteSuite, selectSuite } = useEvaluationStore();

  const handleDelete = (e: React.MouseEvent) => {
    e.stopPropagation();
    if (window.confirm('Are you sure you want to delete this suite?')) {
      deleteSuite(suite.id);
    }
  };

  return (
    <div 
        className="card bg-base-100 shadow-sm border border-base-200/50 hover:border-primary/30 hover:shadow-md transition-all cursor-pointer group"
        onClick={() => selectSuite(suite.id)}
    >
      <div className="card-body p-5">
        <div className="flex justify-between items-start mb-2">
          <Typography.Heading level={3} className="text-lg font-semibold group-hover:text-primary transition-colors">
            {suite.name}
          </Typography.Heading>
           <button 
             className="btn btn-ghost btn-xs btn-square text-error opacity-0 group-hover:opacity-100 transition-opacity"
             onClick={handleDelete}
             title="Delete Suite"
           >
             ✕
           </button>
        </div>
        <Typography.Text className="text-sm text-base-content/70 line-clamp-2 min-h-[40px]">
          {suite.description || 'No description provided.'}
        </Typography.Text>
        
        <div className="card-actions justify-end mt-4 pt-4 border-t border-base-200/50">
           <button className="btn btn-sm btn-outline">
              Manage Cases
           </button>
           <button className="btn btn-sm btn-primary">
              Run
           </button>
        </div>
      </div>
    </div>
  );
};
