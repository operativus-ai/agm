import React from 'react';
import { useQuery } from '@tanstack/react-query';
import { securityApi } from '../api/securityApi';
import type { SandboxCapability } from '../api/securityApi';

export const AgentSandboxViewer: React.FC = () => {
    const { data: vtSandboxes = [] } = useQuery({
        queryKey: ['sandboxCapabilities'],
        queryFn: () => securityApi.getSandboxCapabilities()
    });

    return (
        <div className="bg-[#1a1a1a] border border-[#333] rounded-box p-4 h-full shadow-inner font-mono">
            <h3 className="font-bold text-gray-300 mb-2 flex items-center gap-2 text-sm uppercase tracking-wider">
                <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor" className="w-5 h-5 text-warning">
                    <path strokeLinecap="round" strokeLinejoin="round" d="M16.5 10.5V6.75a4.5 4.5 0 10-9 0v3.75m-.75 11.25h10.5a2.25 2.25 0 002.25-2.25v-6.75a2.25 2.25 0 00-2.25-2.25H6.75a2.25 2.25 0 00-2.25 2.25v6.75a2.25 2.25 0 002.25 2.25z" />
                </svg>
                Thread-Safe Sandbox Viewer
            </h3>
            <p className="text-xs text-gray-500 mb-4">Real-time isolation per Virtual Thread execution mapping</p>

            <div className="grid gap-4">
                {vtSandboxes.map((box: SandboxCapability, idx: number) => (
                    <div key={idx} className="bg-[#222] p-3 rounded border border-[#444]">
                        <div className="flex justify-between items-center border-b border-[#333] pb-2 mb-2">
                            <span className="text-primary font-bold text-sm">{box.agentId}</span>
                            <span className="text-secondary text-xs bg-[#111] px-2 py-1 rounded">vt-thread: {box.threadId}</span>
                        </div>
                        <div className="grid grid-cols-2 gap-4 text-xs">
                            <div>
                                <span className="opacity-50">Active Auth:</span>
                                <div className="mt-1 flex flex-wrap gap-1">
                                    {box.activeCapabilities.map((c: string) => <span key={c} className="bg-success text-[#000] px-1 rounded">{c}</span>)}
                                </div>
                            </div>
                            <div>
                                <span className="opacity-50">Paths Restricted:</span>
                                <div className="mt-1 flex flex-wrap gap-1">
                                    {box.restrictedPaths.map((p: string) => <span key={p} className="bg-error text-[#000] px-1 rounded">{p}</span>)}
                                </div>
                            </div>
                        </div>
                        <div className="mt-3 text-[10px] text-gray-500 flex justify-between items-center border-t border-[#333] pt-2">
                            <span>Isolation Level</span>
                            <span className="text-gray-300 font-bold">{box.memoryIsolation}</span>
                        </div>
                    </div>
                ))}
            </div>
        </div>
    );
};
