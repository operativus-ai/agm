import React, { useMemo, useRef, useState } from 'react';
import { LuFileUp, LuX } from 'react-icons/lu';
import { Alert } from '../../../shared/components/ui/Alert';
import { Badge } from '../../../shared/components/ui/Badge';
import { Button } from '../../../shared/components/ui/Button';
import { useEscapeToClose } from '../../../shared/hooks/useEscapeToClose';
import {
    UserAdminApi,
    type BulkCreateItem,
    type BulkCreateRequest,
} from '../api/userAdminApi';
import type { UserCreateRequest } from '../../../shared/types/api';

interface BulkUserImportModalProps {
    isOpen: boolean;
    onClose: () => void;
    onComplete: () => void;
}

interface ParseError {
    line: number;
    message: string;
}

interface ParsedRow {
    line: number;
    user: UserCreateRequest;
}

const SAMPLE = `username,email,password,roles
alice,alice@example.com,Tempo!2026,ROLE_USER
bob,bob@example.com,Tempo!2026,ROLE_USER;ROLE_ADMIN`;

const splitCsvLine = (line: string): string[] =>
    line.split(',').map(c => c.trim());

const parseCsv = (raw: string): { rows: ParsedRow[]; errors: ParseError[] } => {
    const errors: ParseError[] = [];
    const rows: ParsedRow[] = [];
    const lines = raw.split(/\r?\n/).map(l => l.trim()).filter(l => l.length > 0);
    if (lines.length === 0) {
        errors.push({ line: 0, message: 'Empty file.' });
        return { rows, errors };
    }
    const header = splitCsvLine(lines[0]).map(c => c.toLowerCase());
    const expected = ['username', 'email', 'password', 'roles'];
    for (const col of expected) {
        if (!header.includes(col)) {
            errors.push({ line: 1, message: `Header missing required column: ${col}` });
        }
    }
    if (errors.length > 0) return { rows, errors };

    const idx = {
        username: header.indexOf('username'),
        email: header.indexOf('email'),
        password: header.indexOf('password'),
        roles: header.indexOf('roles'),
    };

    for (let i = 1; i < lines.length; i++) {
        const cells = splitCsvLine(lines[i]);
        const lineNum = i + 1; // 1-based with header
        const username = cells[idx.username] ?? '';
        const email = cells[idx.email] ?? '';
        const password = cells[idx.password] ?? '';
        const rolesCell = cells[idx.roles] ?? '';

        if (!username) errors.push({ line: lineNum, message: 'username is empty' });
        if (!email) errors.push({ line: lineNum, message: 'email is empty' });
        if (!password) errors.push({ line: lineNum, message: 'password is empty' });
        if (!email.includes('@')) errors.push({ line: lineNum, message: `email "${email}" is not valid` });

        const roles = rolesCell
            .split(/[;|]/) // semicolons or pipes inside the cell
            .map(r => r.trim())
            .filter(r => r.length > 0);
        if (roles.length === 0) errors.push({ line: lineNum, message: 'at least one role required' });

        rows.push({ line: lineNum, user: { username, email, password, roles } });
    }

    return { rows, errors };
};

export const BulkUserImportModal: React.FC<BulkUserImportModalProps> = ({ isOpen, onClose, onComplete }) => {
    const [csvText, setCsvText] = useState('');
    const [submitting, setSubmitting] = useState(false);
    const [submitError, setSubmitError] = useState<string | null>(null);
    const [results, setResults] = useState<BulkCreateItem[] | null>(null);
    const idempotencyKeyRef = useRef<string>(crypto.randomUUID());
    const fileInputRef = useRef<HTMLInputElement>(null);

    const parsed = useMemo(() => (csvText.trim() ? parseCsv(csvText) : { rows: [], errors: [] }), [csvText]);

    const handleFile = (file: File | undefined | null) => {
        if (!file) return;
        const reader = new FileReader();
        reader.onload = () => setCsvText(typeof reader.result === 'string' ? reader.result : '');
        reader.readAsText(file);
    };

    const reset = () => {
        setCsvText('');
        setResults(null);
        setSubmitError(null);
        idempotencyKeyRef.current = crypto.randomUUID();
        if (fileInputRef.current) fileInputRef.current.value = '';
    };

    const handleClose = () => {
        if (submitting) return;
        reset();
        onClose();
    };

    const submit = async () => {
        if (parsed.errors.length > 0 || parsed.rows.length === 0) return;
        setSubmitting(true);
        setSubmitError(null);
        setResults(null);
        try {
            const req: BulkCreateRequest = { users: parsed.rows.map(r => r.user) };
            const resp = await UserAdminApi.bulkCreate(req, idempotencyKeyRef.current);
            setResults(resp.items);
            onComplete();
        } catch (err) {
            setSubmitError((err as Error).message || 'Bulk import failed.');
        } finally {
            setSubmitting(false);
        }
    };

    useEscapeToClose(handleClose, isOpen);

    if (!isOpen) return null;

    return (
        <div className="modal modal-open">
            <div className="modal-box bg-obsidian-raised border border-obsidian-stroke max-w-3xl">
                <div className="flex items-start justify-between mb-4">
                    <div>
                        <h3 className="font-bold text-lg flex items-center gap-2">
                            <LuFileUp className="w-5 h-5" />
                            Bulk import users
                        </h3>
                        <p className="text-xs text-(--theme-muted) mt-1">
                            Paste CSV or upload a file. Required columns: <span className="font-mono">username, email, password, roles</span>.
                            Roles within a row are separated by <span className="font-mono">;</span> or <span className="font-mono">|</span>.
                        </p>
                    </div>
                    <button
                        type="button"
                        onClick={handleClose}
                        className="btn btn-ghost btn-sm btn-square"
                        aria-label="Close"
                        disabled={submitting}
                    >
                        <LuX className="w-4 h-4" />
                    </button>
                </div>

                {!results ? (
                    <>
                        <div className="flex items-center gap-3 mb-3">
                            <input
                                ref={fileInputRef}
                                type="file"
                                accept=".csv,text/csv,text/plain"
                                onChange={(e) => handleFile(e.target.files?.[0])}
                                className="file-input file-input-bordered file-input-sm bg-obsidian-surface border-obsidian-stroke flex-1"
                            />
                            <Button
                                variant="ghost"
                                size="sm"
                                onClick={() => setCsvText(SAMPLE)}
                                disabled={submitting}
                            >
                                Use sample
                            </Button>
                        </div>

                        <textarea
                            className="textarea textarea-bordered w-full font-mono text-xs h-48 bg-obsidian-surface border-obsidian-stroke"
                            placeholder={SAMPLE}
                            value={csvText}
                            onChange={(e) => setCsvText(e.target.value)}
                            disabled={submitting}
                        />

                        {parsed.errors.length > 0 && (
                            <div className="mt-3">
                                <Alert severity="error" title={`${parsed.errors.length} validation error${parsed.errors.length === 1 ? '' : 's'}`}>
                                    <ul className="text-xs list-disc list-inside space-y-0.5 mt-1">
                                        {parsed.errors.slice(0, 10).map((err, i) => (
                                            <li key={i}>Line {err.line}: {err.message}</li>
                                        ))}
                                        {parsed.errors.length > 10 && <li>…and {parsed.errors.length - 10} more</li>}
                                    </ul>
                                </Alert>
                            </div>
                        )}

                        {csvText.trim() && parsed.errors.length === 0 && parsed.rows.length > 0 && (
                            <div className="mt-3 text-xs text-(--theme-muted)">
                                Ready to import <span className="text-(--theme-foreground) font-semibold">{parsed.rows.length}</span> user{parsed.rows.length === 1 ? '' : 's'}.
                            </div>
                        )}

                        {submitError && (
                            <Alert severity="error" className="mt-3">{submitError}</Alert>
                        )}

                        <div className="flex justify-end gap-2 mt-5">
                            <Button variant="ghost" size="sm" onClick={handleClose} disabled={submitting}>
                                Cancel
                            </Button>
                            <Button
                                variant="primary"
                                size="sm"
                                onClick={submit}
                                disabled={submitting || parsed.errors.length > 0 || parsed.rows.length === 0}
                                className="gap-1.5"
                            >
                                {submitting && <span className="loading loading-spinner loading-xs" />}
                                Import {parsed.rows.length > 0 ? `(${parsed.rows.length})` : ''}
                            </Button>
                        </div>
                    </>
                ) : (
                    <ResultsView results={results} onDone={handleClose} onImportMore={reset} />
                )}
            </div>
            <div className="modal-backdrop" onClick={handleClose} />
        </div>
    );
};

const ResultsView: React.FC<{ results: BulkCreateItem[]; onDone: () => void; onImportMore: () => void }> = ({ results, onDone, onImportMore }) => {
    const created = results.filter(r => r.status === 'created').length;
    const existed = results.filter(r => r.status === 'already_exists').length;

    return (
        <>
            <Alert severity={existed > 0 ? 'warning' : 'success'}>
                <span className="font-semibold">{created}</span> created
                {existed > 0 && <> · <span className="font-semibold">{existed}</span> already existed</>}
            </Alert>

            <div className="bg-obsidian-surface border border-obsidian-stroke rounded-md mt-3 max-h-72 overflow-y-auto">
                <table className="w-full text-sm">
                    <thead>
                        <tr className="border-b border-obsidian-stroke text-(--theme-muted) text-xs">
                            <th className="px-3 py-2 text-left font-medium">Status</th>
                            <th className="px-3 py-2 text-left font-medium">Username</th>
                            <th className="px-3 py-2 text-left font-medium">Email</th>
                        </tr>
                    </thead>
                    <tbody>
                        {results.map((r, i) => (
                            <tr key={r.id ?? `${r.username}-${i}`} className="border-b border-obsidian-stroke/30 last:border-b-0">
                                <td className="px-3 py-2">
                                    <Badge variant={r.status === 'created' ? 'success' : 'warning'} className="text-xs">
                                        {r.status === 'created' ? 'created' : 'exists'}
                                    </Badge>
                                </td>
                                <td className="px-3 py-2 font-mono text-xs">{r.username}</td>
                                <td className="px-3 py-2 text-xs text-(--theme-muted)">{r.email}</td>
                            </tr>
                        ))}
                    </tbody>
                </table>
            </div>

            <div className="flex justify-end gap-2 mt-5">
                <Button variant="ghost" size="sm" onClick={onImportMore}>
                    Import more
                </Button>
                <Button variant="primary" size="sm" onClick={onDone}>
                    Done
                </Button>
            </div>
        </>
    );
};
