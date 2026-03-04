# aria-clj

Clojure implementation of the ARIA compiler toolchain.

**ARIA** (AI Representation for Instruction Architecture) is an s-expression intermediate representation designed for AI authorship. It compiles to C99, producing portable native binaries.

This is the production rewrite of the [Python prototype](https://github.com/jhavera/aria). Because ARIA-IR is s-expressions and Clojure is s-expressions, the parser collapses to `clojure.edn/read` plus validation, and immutable data structures naturally mirror SSA semantics.

## Prerequisites

- **Clojure 1.12+** (with `clojure` CLI / deps.edn)
- **Java 11+**
- **gcc** (for compiling generated C)

## Quick start

```bash
git clone https://github.com/jhavera/aria-clj.git
cd aria-clj

# Compile and run the Fibonacci example
clojure -M:run examples/fibonacci.aria --run
```

## CLI usage

```
clojure -M:run <file.aria>              # Parse + type-check + emit C to stdout
clojure -M:run <file.aria> --emit-c     # Emit C to stdout (explicit)
clojure -M:run <file.aria> --emit-ast   # Dump the AST
clojure -M:run <file.aria> --check      # Type-check only
clojure -M:run <file.aria> --run        # Compile with gcc and execute
clojure -M:run <file.aria> -o out.c     # Write C to a file
```

## ARIA language overview

ARIA-IR uses s-expressions with a few extensions:

- `$name` — mutable variable
- `%name` — immutable (SSA) variable
- `op.type` — typed operations (e.g. `add.i32`, `load.i32`)

### Modules and functions

Every program is a `module` containing one or more `func` definitions:

```lisp
(module "my_program"

  (func $main
    (result i32)
    (effects io)
    (intent "Entry point")

    (print "hello\n")
    (return 0))

  (export $main))
```

### Types

Scalar types: `i32`, `i64`, `f64`, `bool`
Pointer types: `(ptr i32)`, `(ptr f64)`, etc.

### Effects

Functions declare their side-effect profile:

| Effect | Meaning |
|--------|---------|
| `pure` | No side effects |
| `io` | Performs I/O |
| `mem` | Allocates or frees memory |
| `div` | May divide (possible division by zero) |

Multiple effects can be combined: `(effects io mem div)`.

### Intents

Functions and blocks carry human-readable `intent` annotations describing their purpose. These are preserved through compilation for auditability:

```lisp
(intent "Compute the nth Fibonacci number using recursion")
```

### Control flow

```lisp
; Conditionals
(if (le.i32 $n 1)
  (then (return $n))
  (else (return (add.i32 $a $b))))

; Loops (break with br)
(loop $my_loop
  (if (ge.i32 $i 10)
    (then (br $my_loop))
    (else
      (set $i (add.i32 $i 1)))))
```

### Variables and mutation

```lisp
(let %x i32 42)               ; immutable binding
(local mut $counter i32 0)     ; mutable local
(set $counter (add.i32 $counter 1))  ; mutation
```

### Memory operations

```lisp
(let %arr (ptr i32) (alloc i32 8))   ; allocate
(store.i32 %arr 42)                  ; write
(let %val i32 (load.i32 %arr))       ; read
(free %arr)                          ; deallocate
```

## Examples

Three example programs are included in `examples/`:

| File | Demonstrates |
|------|-------------|
| `fibonacci.aria` | Recursion, conditionals, iterative loops |
| `bubble_sort.aria` | Pointers, memory ops, nested loops, mutation |
| `math_demo.aria` | Multiple algorithms (GCD, factorial, primality, fast exponentiation), type casting |

Run any example:

```bash
clojure -M:run examples/fibonacci.aria --run
clojure -M:run examples/bubble_sort.aria --run
clojure -M:run examples/math_demo.aria --run
```

## AI generation

`aria_gen` uses Claude to generate ARIA-IR from natural language prompts. It validates the output through the parser and type checker, retrying with error feedback if needed.

Requires an `ANTHROPIC_API_KEY` environment variable.

```bash
# Generate ARIA-IR and print it
clojure -M:gen "print hello world"

# Generate, compile with gcc, and execute
clojure -M:gen --run "compute fibonacci of 10"

# Generate and emit C
clojure -M:gen --emit-c "sort a list of integers"

# Options
clojure -M:gen --model claude-sonnet-4-20250514 --max-retries 5 -o out.aria "my program"
```

## Running tests

```bash
clojure -M:test
```

## Architecture

The compiler is a four-stage pipeline:

```
.aria source → Reader → Parser → Checker → Codegen → C99

prompt → Claude API → Reader → Parser → Checker → (retry) → Codegen → C99
```

| Stage | File | Role |
|-------|------|------|
| **Reader** | `src/aria/reader.clj` | Preprocesses `$`/`%` variable sigils into valid Clojure symbols, then uses `read-string` to parse s-expressions |
| **Parser** | `src/aria/parser.clj` | Validates structure and constructs AST maps (plain maps with `:node/type` keys) |
| **Checker** | `src/aria/checker.clj` | Verifies types, variable scoping, effect declarations, and mutability constraints |
| **Codegen** | `src/aria/codegen_c.clj` | Transpiles the AST to portable C99, compilable with gcc or clang |
| **Generator** | `src/aria/gen.clj` | LLM-powered ARIA-IR generation from natural language (calls Claude API, validates, retries) |

The compiler CLI (`src/aria/main.clj`) orchestrates the pipeline and optionally invokes gcc to produce a native binary. The generator CLI (`src/aria/gen.clj`) adds an AI front-end that produces validated ARIA-IR from prompts.

## License

MIT
