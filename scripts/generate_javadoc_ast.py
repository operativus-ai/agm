#!/usr/bin/env python3
"""
AST-Manipulation script for bulk "High-Fidelity" Javadoc Generation.
Requirements: 
  pip install javalang google-genai
Usage: 
  export GEMINI_API_KEY="your-api-key"
  python3 scripts/generate_javadoc_ast.py path/to/YourService.java
"""

import os
import sys
import argparse
import javalang
from google import genai

# Setup Google GenAI Client
client = genai.Client()

SYSTEM_PROMPT = """You are a Senior Java Developer and Technical Documentation Specialist.
Your mission is to generate 'High-Fidelity Internal Documentation' (JavaDoc) for the provided Java method.
Do not output anything other than the raw JavaDoc string. Do not include markdown code block formatting like ```java.
The output MUST exactly match this format:
/**
 * @summary A 1-sentence business purpose.
 * @logic
 * - Bulleted list of internal algorithm steps.
 * @transactional Describe the ACID boundary.
 * @security Note the required @PreAuthorize roles or PII handling logic.
 * @sideEffects List external calls (Database, Kafka, Redis, External APIs).
 * @throws Document the 'Business Condition' that triggers any exceptions.
 */
"""

def generate_method_javadoc(prompt_code: str) -> str:
    response = client.models.generate_content(
        model='gemini-2.5-flash',
        contents=[SYSTEM_PROMPT, f"Generate High-Fidelity JavaDoc for this snippet:\n\n{prompt_code}"]
    )
    return response.text.strip().replace("```java\n", "").replace("```", "").strip()

def generate_class_javadoc(class_name: str, annotations: list) -> str:
    prompt = (f"Target: class {class_name}\n"
              f"Annotations: {annotations}\n"
              "Generate a class-level header explaining 'Domain Responsibility' and 'State' (Stateful/Stateless):\n"
              "/**\n"
              " * Domain Responsibility: [Desc]\n"
              " * State: [Desc]\n"
              " */\n"
              "Only output the raw javadoc comment.")
    response = client.models.generate_content(
        model='gemini-2.5-flash',
        contents=[prompt]
    )
    return response.text.strip().replace("```java\n", "").replace("```", "").strip()

def process_file(filepath: str):
    print(f"Parsing AST for: {filepath}...")
    with open(filepath, 'r') as f:
        source_code = f.read()

    try:
        tree = javalang.parse.parse(source_code)
    except Exception as e:
        print(f"Error parsing AST in {filepath}: {e}")
        return

    insertions = {}
    lines = source_code.splitlines()
    
    is_target = False
    for path, node in tree.filter(javalang.tree.ClassDeclaration):
        annotations = [a.name for a in node.annotations] if node.annotations else []
        
        # Check if the class is a Spring Component explicitly targeted by the user
        if any(ann in annotations for ann in ["Service", "RestController", "Controller", "Component"]):
            is_target = True
            
            # Generate and stage Class-level JavaDoc
            print(f" -> Generating Docs for Class: {node.name}")
            class_javadoc = generate_class_javadoc(node.name, annotations)
            
            start_line = node.position.line
            if node.annotations:
                start_line = node.annotations[0].position.line
            insertions[start_line] = class_javadoc + "\n"
            
            # Iterate through methods to generate docs
            for m in node.methods:
                if 'public' not in m.modifiers:
                    continue # Skip private methods, focus on internal APIs
                
                print(f"   -> Processing Method: {m.name}()")
                m_start = m.position.line - 1
                
                # Heuristic slice: passing the next 50 lines to the LLM to understand logic context
                m_code_snippet = "\n".join(lines[m_start:min(m_start+60, len(lines))])
                
                javadoc = generate_method_javadoc(m_code_snippet)
                
                # Determine precise insertion point (above any Method annotations)
                ann_start = m.annotations[0].position.line if m.annotations else m.position.line
                insertions[ann_start] = "    " + javadoc.replace("\n", "\n    ") + "\n"

    if not is_target:
        print(f"Skipping {filepath} - Target annotations not found.")
        return

    # Apply all insertions systematically based on line numbers
    print("Applying AST insertions back into source file...")
    new_lines = []
    for i, line in enumerate(lines, 1):
        if i in insertions:
            new_lines.append(insertions[i])
        new_lines.append(line)

    new_source = "\n".join(new_lines) + "\n"
    with open(filepath, 'w') as f:
        f.write(new_source)
    print(f"Successfully completed: {filepath}")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="AST-based High-Fidelity Javadoc Generator")
    parser.add_argument("file", help="Path to Java file to document")
    args = parser.parse_args()
    
    if not os.getenv("GEMINI_API_KEY"):
        print("ERROR: GEMINI_API_KEY environment variable is missing.")
        sys.exit(1)
        
    process_file(args.file)
