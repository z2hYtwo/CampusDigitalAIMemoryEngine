import os
from docx import Document
from pypdf import PdfReader

def read_docx(file_path):
    doc = Document(file_path)
    full_text = []
    for para in doc.paragraphs:
        full_text.append(para.text)
    return '\n'.join(full_text)

def read_pdf(file_path):
    reader = PdfReader(file_path)
    full_text = []
    print(f"DEBUG: PDF has {len(reader.pages)} pages")
    for i, page in enumerate(reader.pages):
        text = page.extract_text()
        print(f"DEBUG: Page {i} text length: {len(text) if text else 0}")
        full_text.append(text if text else "")
    return '\n'.join(full_text)

def main():
    files = [
        '技术架构.docx',
        'CampusMemory.docx',
        '阶段设计.pdf'
    ]
    
    for file in files:
        if not os.path.exists(file):
            print(f"File not found: {file}")
            continue
            
        print(f"\n--- CONTENT OF {file} ---")
        try:
            if file.endswith('.docx'):
                print(read_docx(file))
            elif file.endswith('.pdf'):
                print(read_pdf(file))
        except Exception as e:
            print(f"Error reading {file}: {e}")

if __name__ == "__main__":
    main()
