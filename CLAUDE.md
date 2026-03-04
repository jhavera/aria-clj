# aria-clj — ARIA Toolchain in Clojure

## What is this?

Clojure reimplementation of the ARIA compiler toolchain. ARIA (AI Representation 
for Instruction Architecture) is an s-expression IR designed for AI authorship.
The Python prototype lives in the `aria` repo — this is the production rewrite.

## Key insight

ARIA-IR is s-expressions. Clojure is s-expressions. The parser should collapse 
to clojure.edn/read plus validation. Immutable data structures mirror SSA semantics.

## Port from Python prototype

The Python source files to port are in the sibling `aria` repo:
- src/lexer.py → largely replaced by clojure.edn/read
- src/parser.py → ARIA-specific validation + AST construction
- src/ast_nodes.py → Clojure records or maps
- src/type_checker.py → type + effect verification
- src/codegen_c.py → ARIA-C transpiler backend
- ariac.py → CLI entry point
- aria_gen.py → LLM-powered generator (ported to src/aria/gen.clj)

## Build tool

Use deps.edn (not Leiningen). Target Clojure 1.12+.

## Goals

1. Reader-based parsing (no hand-written lexer)
2. Spec or Malli for IR validation
3. Same ARIA-C backend (generate identical C output)
4. Run the same .aria example files and produce identical binaries