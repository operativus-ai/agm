-- Inserts a Shadow Agent record so the Webhook Extension is visible in the UI Workflow Builder 
INSERT INTO agents (id, "name", description, configuration, system_prompt, "type", is_active) 
VALUES (
    'http://localhost:8000/process-script',
    'Python Webhook Extension',
    'Executes a custom Python script locally on port 8000.',
    '{"extension_type": "WEBHOOK"}',
    'You are a deterministic Python process.',
    'WEBHOOK_SCRIPT',
    true
) ON CONFLICT (id) DO NOTHING;
