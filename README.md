# aria-clj

ARIA compiler toolchain.

**ARIA** (AI Representation for Instruction Architecture) is an s-expression intermediate representation designed for AI authorship. It compiles to C99, producing portable native binaries.

The reference ARIA-IR compiler is **ariac** — a self-hosted compiler written in ARIA-IR itself. It compiles to native binaries via C99 and can compile itself. The Clojure implementation (`src/aria/`) serves as the initial bootstrap only.

ariac provides capabilities beyond the Clojure bootstrap:

- **Multi-module programs** with `(import "path.aria")` and qualified access (`$module.func`)
- **Compile-time safety** with 45+ static analyses and 64 test cases (see `examples/mem-check-test/`)
- **Mandatory intent annotations** enforced by the checker
- **Error positions** with line and column numbers

## Download

Pre-built uberjars are available from [GitHub Releases](https://github.com/jhavera/aria-clj/releases). Only Java 11+ is required:

```bash
java -jar aria-clj-0.1.0.jar <file.aria> --check
```

## Prerequisites

- **Clojure 1.12+** (with `clojure` CLI / deps.edn) — for development
- **Java 11+**
- **gcc** (optional — required only for the C backend's `--run` flag)

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

## JVM backend

ARIA programs can also be compiled to JVM bytecode:

```bash
clojure -M:run <file.aria> --backend jvm       # Emit .class file
clojure -M:run <file.aria> --emit-jar           # Emit runnable JAR
```

The generated JAR can be executed directly with `java -jar`.

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
;; Heap allocation (requires manual free)
(let %arr (ptr i32) (alloc i32 8))   ; allocate on heap
(store.i32 %arr 42)                  ; write
(let %val i32 (load.i32 %arr))       ; read
(free %arr)                          ; deallocate

;; Stack allocation (automatic cleanup, zero-cost, no free needed)
(local-array $buf i32 16)            ; 16 ints on stack
(store.i32 (add.i32 $buf 0) 42)     ; write
(let %v i32 (load.i32 $buf))        ; read
;; $buf is freed automatically when the scope exits
```

Stack arrays via `local-array` are safer than heap: no memory leaks (automatic cleanup), no double-free (cannot `free`), no dangling returns (checker prevents returning stack pointers). The checker enforces all bounds, uninitialized-read, and escape checks at compile time.

## ariac — Self-hosted compiler

`ariac` is the ARIA compiler written in ARIA-IR itself, split into 6 modules (~4800 LOC total). It compiles ARIA source to native binaries via C99, supports multi-module programs via `(import ...)`, and can compile itself.

### Building ariac

Building ariac is a two-stage process:

```bash
# Stage 0: Build ariac-bootstrap (single-file compiler with minimal import support)
clojure -M:run aria-src/ariac-bootstrap.aria --backend c -o /tmp/ariac_bs.c
gcc -std=c99 -fwrapv -o ariac-bootstrap /tmp/ariac_bs.c -lm

# Stage 1: Build ariac from its split modules using the bootstrap
./ariac-bootstrap aria-src/ariac/main.aria -o ariac
```

`ariac-bootstrap` is a frozen stage0 compiler — it only needs to be built once. After that, `ariac` can rebuild itself:

```bash
./ariac aria-src/ariac/main.aria -o ariac
```

### Using ariac

```
ariac <file.aria>              # Compile to native binary (name = module name)
ariac <file.aria> --run        # Compile and execute
ariac <file.aria> --emit-c     # Emit C to stdout
ariac <file.aria> --check      # Type-check only
ariac <file.aria> --emit-ast   # Print AST
ariac <file.aria> -o <name>    # Compile with custom output name
ariac <file.aria> --quiet      # Suppress status messages
ariac --help                   # Show usage
```

### Multi-module programs

ariac supports importing functions from other ARIA modules:

```lisp
; math.aria
(module "math"
  (func $gcd (param $a i32) (param $b i32) (result i32) (effects pure)
    (if (eq.i32 $b 0) (then (return $a))
      (else (return (call $gcd $b (rem.i32 $a $b))))))
  (export $gcd))

; main.aria
(module "main"
  (import "math.aria")
  (func $main (result i32) (effects io)
    (print "GCD = %d\n" (call $math.gcd 48 18))
    (return 0))
  (export $main))
```

```bash
ariac main.aria --run   # Automatically resolves and compiles math.aria
```

### Running examples with ariac

```bash
ariac examples/fibonacci.aria --run
ariac examples/bubble_sort.aria --run
ariac examples/math_demo.aria --run
ariac examples/float_demo.aria --run
ariac examples/bootstrap_demo.aria --run
ariac examples/import_demo/main.aria --run    # Multi-module example
```

## Examples

| File | Demonstrates |
|------|-------------|
| `fibonacci.aria` | Recursion, conditionals, iterative loops |
| `bubble_sort.aria` | Pointers, memory ops, nested loops, mutation |
| `math_demo.aria` | Multiple algorithms (GCD, factorial, primality, fast exponentiation), type casting |
| `float_demo.aria` | Float arithmetic, pi, temperature conversion, int-to-float cast |
| `bootstrap_demo.aria` | Strings as `(ptr u8)`, externs, file I/O, CLI args |
| `local_array_demo.aria` | Stack-allocated arrays, zero-cost allocation, automatic cleanup |
| `mem-check-test/` | Safety checker test suite (64 tests) |

Run any example with the Clojure compiler:

```bash
clojure -M:run examples/fibonacci.aria --run
```

Or with ariac (once built):

```bash
ariac examples/fibonacci.aria --run
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

### Self-hosted compiler (ariac)

The self-hosted compiler implements the same pipeline in ARIA-IR itself, split into 6 modules:

| Module | File | Role |
|--------|------|------|
| **Types** | `aria-src/ariac/types.aria` | Struct types, pools, accessors, module registry |
| **Reader** | `aria-src/ariac/reader.aria` | S-expression parser |
| **Parser** | `aria-src/ariac/parser.aria` | AST construction from SExp tree |
| **Checker** | `aria-src/ariac/checker.aria` | Scoping, mutability, effect verification, memory safety analysis |
| **Codegen** | `aria-src/ariac/codegen.aria` | C99 emission (single + multi-module) |
| **Main** | `aria-src/ariac/main.aria` | CLI, module resolver, pipeline orchestration |

The bootstrap compiler (`aria-src/ariac-bootstrap.aria`) is a frozen single-file version (~4500 LOC) that bridges the Clojure compiler to the split ariac. It only needs to be built once.

## License

Apache-2.0
