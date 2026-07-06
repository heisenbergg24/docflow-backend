#!/usr/bin/env python3
"""
Converts a PDF to a Word (.docx) file using pdf2docx, which does real
table/layout reconstruction instead of a flat text dump.

Usage:
    python3 pdf_to_docx.py <input.pdf> <output.docx>

Exits with code 0 and prints nothing on success.
Exits with code 1 and prints the error to stderr on failure.
"""

import sys
from pdf2docx import Converter

def main():
    if len(sys.argv) != 3:
        print("Usage: pdf_to_docx.py <input.pdf> <output.docx>", file=sys.stderr)
        sys.exit(1)

    input_path = sys.argv[1]
    output_path = sys.argv[2]

    try:
        cv = Converter(input_path)
        cv.convert(output_path, start=0, end=None)
        cv.close()
    except Exception as e:
        print(f"pdf2docx conversion failed: {e}", file=sys.stderr)
        sys.exit(1)

    sys.exit(0)

if __name__ == "__main__":
    main()