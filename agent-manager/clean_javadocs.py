import os

def clean_file(filepath):
    with open(filepath, 'r') as f:
        content = f.read()

    tags = ['@transactional', '@security', '@sideEffects', '@throws', '@param', '@return', '@author', '@version', '@see']
    
    lines = content.split('\n')
    new_lines = []
    modified = False
    
    in_javadoc = False
    for line in lines:
        stripped = line.strip()
        if stripped.startswith('/**'):
            in_javadoc = True
            
        if in_javadoc:
            remove = False
            for tag in tags:
                if stripped.startswith('* ' + tag) or stripped == '*' + tag:
                    remove = True
                    modified = True
                    break
            if remove:
                continue
        
        if stripped.endswith('*/') or stripped == '*/':
            in_javadoc = False
            
        new_lines.append(line)
        
    if modified:
        with open(filepath, 'w') as f:
            f.write('\n'.join(new_lines))
        print(f"Cleaned {filepath}")

for root_dir in ['src/main/java/com/operativus/agentmanager/core', 'src/main/java/com/operativus/agentmanager/control']:
    for root, dirs, files in os.walk(root_dir):
        for file in files:
            if file.endswith('.java'):
                clean_file(os.path.join(root, file))
