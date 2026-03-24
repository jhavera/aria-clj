# PLAN: Compile-Time Evaluation (`comptime`) for ARIA

## Motivation

ARIA is a single-module, single-pass IR with no metaprogramming. This limits
composability and prevents compile-time code generation. Adding `comptime`
enables:

- **Compile-time computation**: pre-calculate constants, lookup tables, config
- **Code generation**: emit ARIA s-expressions at compile time (macro-like)
- **Generics / monomorphization**: as a natural consequence of comptime, like
  Zig — no separate generics feature needed

An additional practical concern: while "the AI doesn't mind writing more" is
true in terms of effort, **every token consumes context window**. A program
without abstractions may need 10x more tokens to generate and read than one
with type-parameterized code. Comptime is not just ergonomics — it's a
**context window optimization** for AI-generated code.

### Key design insight

ARIA already has a complete compilation pipeline (parse → check → codegen →
compile → execute). Instead of building an interpreter inside the compiler,
**the compiler uses itself as the comptime evaluator** via a two-pass model:

```
Pass 1: Extract (comptime ...) blocks → compile as temporary module → execute → capture output
Pass 2: Substitute comptime results into original source → compile normally
```

This gives comptime access to the full language at native speed, with zero
compiler complexity increase beyond orchestration logic.

---

## How comptime subsumes generics

Like Zig, generics are not a separate language feature — they are a natural
consequence of comptime. A comptime function that receives a type and outputs
type-parameterized code IS a generic:

```aria
;; A comptime block that generates a Vector type for a given element type.
;; The comptime code is a normal ARIA program — it uses print with %s to
;; emit ARIA s-expressions parameterized by the type name.
(comptime
  (func $main (result i32)
    ;; Generate Vector for f64
    (print "(type $Vector_f64 (struct (field $x f64) (field $y f64)))\n")
    (print "(func $vec_f64_new (param $x f64) (param $y f64) (result i32)\n")
    (print "  (let %%v i32 (alloc $Vector_f64 1))\n")
    (print "  (store-field %%v $Vector_f64 $x %s)\n" "$x")
    (print "  (store-field %%v $Vector_f64 $y %s)\n" "$y")
    (print "  (return %%v))\n")
    (return 0)))
```

This is more verbose than Zig's `fn Vector(comptime T: type) type { ... }`,
but the AI generating the code doesn't mind the verbosity — and the output
is compact. The key insight is that **one feature (comptime) covers both
use cases (computation + generics)**, keeping the language simple.

---

## Implementation Plan

### Phase 1: Core comptime (two-pass)

**Files to modify:**

#### 1.1 `src/aria/ast.clj` — comptime node

```clojure
(defn comptime-block [forms]
  {:node/type :comptime
   :forms forms})              ;; raw top-level forms for the comptime module
```

#### 1.2 `src/aria/parser.clj` — recognize comptime

In `parse-module` dispatch, add:

```clojure
"comptime" → store raw forms (not parsed yet) in a :comptime-blocks accumulator
```

The comptime body is stored as **raw s-expression forms** (not parsed), because
it will be compiled as a separate temporary module.

#### 1.3 `src/aria/main.clj` — two-pass orchestration

This is the core of the comptime implementation. Modify `process-file`:

```clojure
(defn- process-file [path opts]
  (let [source (slurp path)
        raw-forms (read-all source)]        ;; Step 0: read s-expressions

    ;; === PASS 1: Comptime evaluation ===
    (let [{:keys [comptime-blocks normal-forms]} (separate-comptime raw-forms)]
      (if (empty? comptime-blocks)
        ;; No comptime — proceed normally (fast path)
        (compile-module normal-forms opts)

        ;; Has comptime — evaluate and substitute
        (let [comptime-results (evaluate-comptime-blocks comptime-blocks opts)
              expanded-forms (splice-results normal-forms comptime-results)]

          ;; === PASS 2: Normal compilation with expanded source ===
          (compile-module expanded-forms opts))))))
```

#### 1.4 `src/aria/main.clj` — comptime evaluation

```clojure
(defn- evaluate-comptime-blocks [blocks opts]
  ;; For each comptime block:
  ;; 1. Wrap in a (module "comptime_N" ...) with a $main that executes the code
  ;; 2. Parse → check → codegen-c → compile with gcc → execute
  ;; 3. Capture stdout as a string
  ;; 4. Return the stdout string (valid ARIA s-expressions)
  ;;
  ;; Uses the C backend for comptime (always runs on host machine).
  ;; Temp files in system temp dir, cleaned up after.
  )
```

#### 1.5 `src/aria/main.clj` — result splicing

```clojure
(defn- splice-results [forms comptime-results]
  ;; Replace each (comptime ...) form in the source with the parsed
  ;; s-expressions from its stdout output.
  ;; Uses clojure.edn/read to parse the captured output back into forms.
  )
```

#### 1.6 No changes to checker or codegen

Comptime is fully resolved before the checker and codegen run.
Pass 2 sees only standard ARIA forms.

---

### Phase 2: Expression-level comptime (future)

Allow comptime inside expressions, not just at the top level:

```aria
(let %x i32 (comptime (mul.i32 6 7)))
;; → (let %x i32 42)
```

This requires the comptime evaluator to handle individual expressions and
return typed values (not s-expression source). The two-pass model still works,
but the temporary module would print values in a structured format that the
compiler can parse back as typed literals.

---

## Execution order in the compiler

```
Source file
    │
    ▼
Read raw s-expressions (clojure.edn/read)
    │
    ▼
Separate (comptime ...) blocks from normal forms
    │
    ├─── Has comptime? ──► Compile & execute comptime blocks (C backend)
    │                       Capture stdout → parse as s-expressions
    │                       Splice results back into form list
    │
    ▼
Parse expanded forms (parser/parse) → AST
    │
    ▼
Type-check (checker/check) → validated AST
    │
    ▼
Codegen (C or WAT) → target source
    │
    ▼
Backend compiler (gcc / wat2wasm) → binary
```

---

## Verification

### Comptime tests
- `(comptime ...)` that prints a constant → substituted as literal
- `(comptime ...)` that prints ARIA function definitions → spliced into module
- `(comptime ...)` that generates type-parameterized code (generic pattern)
- Comptime with no output → removed cleanly
- Error handling: comptime compilation failure, runtime failure, invalid output

### Integration tests
- Cross-backend: comptime-expanded programs produce identical output in C and WAT
- Existing test suite passes unchanged (no comptime = fast path, no behavior change)

---

## Complexity assessment

| Component | Effort | Lines (est.) |
|-----------|--------|-------------|
| Comptime parser recognition | Low | ~10 |
| Two-pass orchestration in main.clj | Medium | ~60 |
| Comptime temp module generation | Medium | ~40 |
| Comptime execution + capture | Low | ~20 |
| Result splicing | Low | ~20 |
| Tests | Medium | ~80 |
| **Total** | | **~230** |

No changes to checker, codegen_c, or codegen_wat. The complexity is
concentrated in `main.clj` (orchestration) and `parser.clj` (new form).
