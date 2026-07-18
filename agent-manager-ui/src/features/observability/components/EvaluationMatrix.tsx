import React from 'react';
import { useQuery } from '@tanstack/react-query';
import { observabilityApi } from '../api/observabilityApi';
import type { EvaluationMetric } from '../api/observabilityApi';

export const EvaluationMatrix: React.FC = () => {
    const { data: evaluations = [] } = useQuery({
        queryKey: ['evaluationMetrics'],
        queryFn: () => observabilityApi.getEvaluations()
    });

    return (
        <div className="bg-[#1e1e1e] border border-[#333] rounded-box p-4 shadow-inner text-gray-300 font-mono h-full flex flex-col">
            <div className="flex justify-between items-start mb-4 border-b border-[#333] pb-2">
                <div>
                    <h3 className="font-bold text-sm text-gray-100 uppercase tracking-wider">Evaluation Matrix</h3>
                    <p className="text-[10px] text-gray-500">Critic Agent performance observability</p>
                </div>
                <span className="badge badge-error badge-outline text-[10px] font-bold">LLM-as-a-Judge</span>
            </div>

            <div className="grid gap-3 overflow-y-auto">
                {evaluations.map((evalObj: EvaluationMetric, idx: number) => (
                    <div key={idx} className="bg-[#2a2a2a] p-3 rounded flex justify-between items-center border border-[#444]">
                        <div>
                            <div className="text-secondary font-bold text-xs">{evalObj.target}</div>
                            <div className="text-[10px] text-gray-500 mt-1 uppercase">Metric: {evalObj.metric}</div>
                        </div>
                        <div className="text-right">
                            <div className={`text-xl font-bold ${evalObj.score < 80 ? 'text-warning' : 'text-success'}`}>
                                {evalObj.score}/100
                            </div>
                            <div className={`text-[10px] ${evalObj.drift.startsWith('-') ? 'text-error' : 'text-info'}`}>
                                Drift: {evalObj.drift}
                            </div>
                        </div>
                    </div>
                ))}
            </div>
            <div className="mt-auto pt-4 flex justify-between items-center text-[10px]">
                <span>Last full validation: 2 hrs ago</span>
                <button className="text-primary hover:underline uppercase font-bold tracking-widest">Trigger Eval</button>
            </div>
        </div>
    );
};
