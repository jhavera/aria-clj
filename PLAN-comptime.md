# PLAN: Compile-Time Evaluation (`comptime`) for ARIA

## Motivation

ARIA is a single-module, single-pass IR with no metaprogramming. This limits
composability and prevents compile-time code generation. Adding `comptime`
enables:

- **Compile-time computation**: pre-calculate constants, lookup tables, config
- **Code generation**: emit ARIA s-expressions at compile time (macro-like)
- **Generics / monomorphization**: type-parameterized templates via AST substitution

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

## Two features, layered

### Layer 1: AST Templates (generics)

Pure AST substitution — no compilation or execution needed. The compiler copies
a template AST subtree, replacing type parameters with concrete types. This is
a `clojure.walk/postwalk` operation on the AST data structures.

```aria
(generic $Pair ($T $U)
  (type $Pair_$T_$U (struct (field $first $T) (field $second $U))))

(instantiate $Pair f64 i32)
;; Expands to: (type $Pair_f64_i32 (struct (field $first f64) (field $second i32)))
```

**Why separate from comptime**: generics don't need compilation or execution.
They are mechanical AST transformations. Simpler to implement, simpler to
reason about, and cover the most common use case (type-parameterized structs
and functions).

### Layer 2: Comptime (two-pass evaluation)

For arbitrary compile-time computation. The comptime block is compiled and
executed as a normal ARIA program. Its stdout is captured and spliced back
into the source as ARIA s-expressions.

```aria
(comptime
  (func $main (result i32)
    (print "(global $TABLE_SIZE i32 %d)\n" (mul.i32 16 1024))
    (return 0)))

;; After pass 1, becomes: (global $TABLE_SIZE i32 16384)
```

Since ARIA's syntax is s-expressions, comptime output IS valid ARIA source.
The `print` function with format strings is the code generation mechanism —
no new string manipulation primitives needed.

---

## Implementation Plan

### Phase 1: AST Templates (generics)

**Files to modify:**

#### 1.1 `src/aria/ast.clj` — new node types

```clojure
(defn generic-node [name type-params body-forms]
  {:node/type :generic
   :name name
   :type-params type-params    ;; ["$T" "$U"]
   :body body-forms})          ;; vector of top-level forms (unparsed)

(defn instantiate-node [name type-args]
  {:node/type :instantiate
   :name name
   :type-args type-args})      ;; [type-map ...]
```

Add `:generics` key to the module node (alongside `:types`, `:globals`, etc.).

#### 1.2 `src/aria/parser.clj` — parse generic and instantiate

In `parse-module` dispatch (around line 370), add cases:

```clojure
"generic"     → parse-generic: collect name, type-params, and raw body forms
"instantiate" → parse-instantiate: collect template name and concrete type args
```

The generic body is stored as **raw s-expression forms** (not parsed), because
parsing requires knowing the concrete types. Parsing happens after expansion.

#### 1.3 `src/aria/parser.clj` — expand generics before full parse

Add an expansion step between reading and parsing:

```clojure
(defn expand-generics [forms]
  ;; 1. Collect all (generic ...) definitions into a registry
  ;; 2. For each (instantiate $Name Type ...), look up the generic
  ;; 3. Substitute type params in the raw s-expression body
  ;; 4. Replace the (instantiate ...) form with the expanded forms
  ;; 5. Remove (generic ...) definitions from the form list
  ;; 6. Return the expanded form list for normal parsing
  )
```

The substitution is textual on the raw s-expressions (Clojure symbols/lists):
- Walk the body forms with `clojure.walk/postwalk`
- Replace symbols matching type param names with the concrete type symbols
- Generate unique names by appending type suffixes (e.g., `$Pair` → `$Pair_f64_i32`)

#### 1.4 No changes needed in checker or codegen

After expansion, the AST contains only standard nodes (types, functions, etc.).
The checker and codegen backends see no difference — generics are fully erased
before they run.

---

### Phase 2: Comptime (two-pass)

**Files to modify:**

#### 2.1 `src/aria/ast.clj` — comptime node

```clojure
(defn comptime-block [forms]
  {:node/type :comptime
   :forms forms})              ;; raw top-level forms for the comptime module
```

#### 2.2 `src/aria/parser.clj` — recognize comptime

In `parse-module` dispatch, add:

```clojure
"comptime" → store raw forms (not parsed yet) in a :comptime-blocks accumulator
```

#### 2.3 `src/aria/main.clj` — two-pass orchestration

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

#### 2.4 `src/aria/main.clj` — comptime evaluation

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

#### 2.5 `src/aria/main.clj` — result splicing

```clojure
(defn- splice-results [forms comptime-results]
  ;; Replace each (comptime ...) form in the source with the parsed
  ;; s-expressions from its stdout output.
  ;; Uses clojure.edn/read to parse the captured output back into forms.
  )
```

#### 2.6 No changes to checker or codegen

Like generics, comptime is fully resolved before the checker and codegen run.
Pass 2 sees only standard ARIA forms.

---

### Phase 3: Expression-level comptime (optional, future)

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

## Interaction between layers

Generics and comptime compose naturally:

```aria
;; Define a generic container
(generic $Array ($T)
  (type $Array_$T (struct (field $data (ptr $T)) (field $len i32)))
  (func $array_$T_new (param $cap i32) (result i32)
    (let %arr i32 (alloc $Array_$T 1))
    (store-field %arr $Array_$T $data (alloc $T (local.get $cap)))
    (store-field %arr $Array_$T $len (i32.const 0))
    (return %arr)))

;; Instantiate at comptime based on some condition, or just directly
(instantiate $Array f64)
(instantiate $Array i32)
```

For comptime-driven instantiation (instantiating templates based on
compile-time logic), phase 2 comptime would generate `(instantiate ...)`
forms in its output, which phase 1 generics expansion would then process.
This means the order is: **comptime first, then generic expansion, then
normal compilation**.

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
Expand (generic ...) / (instantiate ...) via AST substitution
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

### Generics tests
- `(generic $Pair ($T) ...)` + `(instantiate $Pair f64)` produces correct struct
- Multiple instantiations of the same generic with different types
- Generic functions with type-parameterized operations
- Name mangling produces unique identifiers

### Comptime tests
- `(comptime ...)` that prints a constant → substituted as literal
- `(comptime ...)` that prints ARIA function definitions → spliced into module
- Comptime with no output → removed cleanly
- Error handling: comptime compilation failure, runtime failure, invalid output

### Integration tests
- Comptime output that includes `(instantiate ...)` → generics expansion works
- Cross-backend: comptime-expanded programs produce identical output in C and WAT
- Existing test suite passes unchanged (no comptime = fast path, no behavior change)

---

## Complexity assessment

| Component | Effort | Lines (est.) |
|-----------|--------|-------------|
| Generic parser + AST nodes | Low | ~40 |
| Generic expansion (postwalk) | Low | ~30 |
| Comptime parser recognition | Low | ~10 |
| Two-pass orchestration in main.clj | Medium | ~60 |
| Comptime temp module generation | Medium | ~40 |
| Comptime execution + capture | Low | ~20 |
| Result splicing | Low | ~20 |
| Tests | Medium | ~100 |
| **Total** | | **~320** |

No changes to checker, codegen_c, or codegen_wat. The complexity is
concentrated in `main.clj` (orchestration) and `parser.clj` (new forms).
