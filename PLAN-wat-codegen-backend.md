# Plan: WASM Backend for ARIA — Direct WAT Code Generation

## Context

ARIA-IR was designed as a mirror of WebAssembly instructions: `loop`/`br`/`br_if`/`block` map directly to WASM control flow, type-suffixed operations (`add.i32`) correspond to WASM opcodes (`i32.add`), and both use s-expressions as their textual format. Currently ARIA only emits C99 via `codegen_c.clj`, requiring gcc as an external dependency.

This plan adds a `codegen_wat.clj` that takes the same AST and emits WAT (WebAssembly Text format) directly. The pipeline becomes:

```
.aria → Reader → Parser → Checker → codegen_wat → .wat → wat2wasm → .wasm → wasm-opt → .wasm (optimized)
```

No C compiler, no Zig compiler. ARIA becomes self-sufficient for WASM targets, using only `wat2wasm` (WABT) for binary conversion and `wasm-opt` (Binaryen) for optimization.

---

## Phase 1: Core WAT Codegen

**Goal:** Create `codegen_wat.clj` that generates valid WAT from the ARIA AST for programs using i32/i64/f32/f64 primitives, control flow, and function calls.

### Create: `src/aria/codegen_wat.clj`

Follows the same structure as `codegen_c.clj` (atoms-based state with `make-codegen`, `emit!`, `indent-str`).

#### State

```clojure
(defn- make-codegen []
  {:indent (atom 0)
   :output (atom [])
   :locals (atom [])          ;; collected during function body traversal
   :local-index (atom {})     ;; var-name -> local index
   :data-segments (atom [])   ;; string literals accumulated for data section
   :data-offset (atom 1024)   ;; current offset in linear memory for static data
   :stack-ptr (atom 0)})      ;; tracks struct/alloc layout offsets
```

#### Type Mapping: `type->wat`

WASM has only 4 value types. All ARIA types collapse to these:

| ARIA type | WAT type | Notes |
|---|---|---|
| `i1, i8, i16, i32, u8, u16, u32, bool` | `i32` | WASM has no sub-32-bit integers |
| `i64, u64` | `i64` | |
| `f32` | `f32` | |
| `f64` | `f64` | |
| `(ptr T)` | `i32` | Pointers are offsets into linear memory |
| `void` | (no result) | |

```clojure
(defn- type->wat [t]
  (case (:type/kind t)
    :primitive (case (:type/name t)
                 ("i64" "u64") "i64"
                 ("f32") "f32"
                 ("f64") "f64"
                 "i32")  ;; everything else is i32
    :ptr "i32"           ;; pointers are memory offsets
    :array "i32"         ;; arrays are pointers
    nil))                ;; void -> no type
```

#### Expression Codegen: `emit-expr!`

WASM is a stack machine. Expressions emit instructions in post-order (children first, then operator). This replaces the string-returning `expr->c` pattern with a stack-emitting `emit-expr!` pattern.

```clojure
(defn- emit-expr! [cg node]
  (case (:node/type node)
    :int-literal   (emit! cg (str "i32.const " (:value node)))
    :float-literal (emit! cg (str "f64.const " (:value node)))
    :bool-literal  (emit! cg (str "i32.const " (if (:value node) 1 0)))

    :var-ref       (emit! cg (str "local.get $" (var->wat (:name node))))

    :bin-op        (do (emit-expr! cg (:left node))
                       (emit-expr! cg (:right node))
                       (emit! cg (str (:type-suffix node) "." (:op node))))
                       ;; e.g. "i32.add", "f64.mul"

    :comparison    (do (emit-expr! cg (:left node))
                       (emit-expr! cg (:right node))
                       (emit! cg (str (:type-suffix node) "." (:op node) "_s")))
                       ;; e.g. "i32.lt_s" (signed comparison)

    :call          (do (doseq [arg (:args node)]
                         (emit-expr! cg arg))
                       (emit! cg (str "call $" (var->wat (:target node)))))

    :if            ;; ternary: emit as if/else with results
                   (do (emit-expr! cg (:cond node))
                       (emit! cg "(if (result i32)")  ;; adjust result type
                       (emit! cg "  (then")
                       ;; emit then-body last expr
                       (emit! cg "  )")
                       (emit! cg "  (else")
                       ;; emit else-body last expr
                       (emit! cg "  )")
                       (emit! cg ")"))

    :load          (do (emit-expr! cg (:ptr node))
                       (emit! cg (str (:type-suffix node) ".load")))

    :alloc         ;; call imported or built-in allocator
                   (do (when (:count node)
                         (emit-expr! cg (:count node)))
                       ;; emit size calculation + alloc call
                       )

    :cast          (do (emit-expr! cg (:value node))
                       (emit-cast! cg (:from-type node) (:to-type node)))))
```

Key pattern: **children first, operator last** — the mechanical post-order traversal.

#### Comparison operators mapping

WASM distinguishes signed (`_s`) and unsigned (`_u`) comparisons:

| ARIA op | WAT (signed context) | WAT (unsigned context) |
|---|---|---|
| `eq` | `i32.eq` | `i32.eq` |
| `ne` | `i32.ne` | `i32.ne` |
| `lt` | `i32.lt_s` | `i32.lt_u` |
| `le` | `i32.le_s` | `i32.le_u` |
| `gt` | `i32.gt_s` | `i32.gt_u` |
| `ge` | `i32.ge_s` | `i32.ge_u` |

Use the type suffix to determine signedness: `i32`/`i64` → `_s`, `u32`/`u64` → `_u`.

#### Cast mapping: `emit-cast!`

| From → To | WAT instruction |
|---|---|
| `i32` → `i64` | `i64.extend_i32_s` |
| `i64` → `i32` | `i32.wrap_i64` |
| `i32` → `f64` | `f64.convert_i32_s` |
| `f64` → `i32` | `i32.trunc_f64_s` |
| `i32` → `f32` | `f32.convert_i32_s` |
| `f32` → `f64` | `f64.promote_f32` |
| `f64` → `f32` | `f32.demote_f64` |

#### Statement Codegen: `emit-stmt!`

```clojure
(defn- emit-stmt! [cg node]
  (case (:node/type node)
    :intent  (emit! cg (str ";; INTENT: " (:description node)))

    :let     (do (register-local! cg (:name node) (:aria/type node) (:mutable? node))
                 (emit-expr! cg (:value node))
                 (emit! cg (str "local.set $" (var->wat (:name node)))))

    :set-var (do (emit-expr! cg (:value node))
                 (emit! cg (str "local.set $" (var->wat (:name node)))))

    :store   (do (emit-expr! cg (:ptr node))    ;; address on stack
                 (emit-expr! cg (:value node))   ;; value on stack
                 (emit! cg (str (:type-suffix node) ".store")))

    :free    nil  ;; no-op without GC; or call imported free

    :return  (do (when (:value node)
                   (emit-expr! cg (:value node)))
                 (emit! cg "return"))

    :if      (do (emit-expr! cg (:cond node))
                 (emit! cg "if")
                 (swap! (:indent cg) inc)
                 (doseq [n (:then-body node)] (emit-stmt! cg n))
                 (swap! (:indent cg) dec)
                 (when (seq (:else-body node))
                   (emit! cg "else")
                   (swap! (:indent cg) inc)
                   (doseq [n (:else-body node)] (emit-stmt! cg n))
                   (swap! (:indent cg) dec))
                 (emit! cg "end"))

    :loop    (do (emit! cg (str "block $break_" (var->wat (:label node))))
                 (emit! cg (str "  loop $" (var->wat (:label node))))
                 (swap! (:indent cg) + 2)
                 (doseq [n (:body node)] (emit-stmt! cg n))
                 ;; implicit continue: branch back to loop head
                 (emit! cg (str "br $" (var->wat (:label node))))
                 (swap! (:indent cg) - 2)
                 (emit! cg "  end")
                 (emit! cg "end"))

    :branch  (if (:cond node)
               ;; br_if — conditional break
               (do (emit-expr! cg (:cond node))
                   (emit! cg (str "br_if $break_" (var->wat (:label node)))))
               ;; br — unconditional break
               (emit! cg (str "br $break_" (var->wat (:label node)))))

    :call    (do (doseq [arg (:args node)] (emit-expr! cg arg))
                 (emit! cg (str "call $" (var->wat (:target node)))))

    :print   ;; see Phase 2 — requires imported print function

    :seq     (doseq [n (:body node)] (emit-stmt! cg n))))
```

#### Loop/Break Pattern

ARIA's `(loop $L body (br $L))` means "break out of loop L". In WASM this maps to a `block`+`loop` pair:

```wat
;; ARIA: (loop $outer ... (if cond (then (br $outer))) ...)
block $break_outer          ;; br $outer jumps HERE (exits)
  loop $outer               ;; continue jumps here (loops back)
    ;; body
    condition
    br_if $break_outer      ;; break out
    br $outer               ;; continue looping
  end
end
```

#### Function Codegen: `emit-function!`

```clojure
(defn- emit-function! [cg func exports]
  ;; First pass: traverse body to collect all locals
  (reset! (:locals cg) [])
  (reset! (:local-index cg) {})
  (collect-locals! cg func)

  (let [export? (contains? exports (:name func))
        fname (var->wat (:name func))
        params (map (fn [p] (str "(param $" (var->wat (:param/name p))
                                " " (type->wat (:param/type p)) ")"))
                    (:params func))
        result (when (:result func)
                 (when-let [wt (type->wat (:result func))]
                   (str "(result " wt ")")))
        locals @(:locals cg)]

    ;; Export declaration if needed
    (when export?
      (emit! cg (str "(export \"" fname "\" (func $" fname "))")))

    ;; Function signature
    (emit! cg (str "(func $" fname
                   (when (seq params) (str " " (str/join " " params)))
                   (when result (str " " result))))
    (swap! (:indent cg) inc)

    ;; Local declarations (collected from let/local in body)
    (doseq [[name wat-type] locals]
      (emit! cg (str "(local $" name " " wat-type ")")))

    ;; Body
    (doseq [node (:body func)]
      (emit-stmt! cg node))

    (swap! (:indent cg) dec)
    (emit! cg ")")))
```

**`collect-locals!`**: Pre-pass that walks the function body and registers every `:let` and `:set-var` binding as a WASM local. WASM requires all locals declared at the top of the function — they cannot be declared inline like in C.

#### Top-Level: `generate`

```clojure
(defn generate [module]
  (let [cg (make-codegen)
        export-names (set (map :name (:exports module)))]

    (emit! cg "(module")
    (swap! (:indent cg) inc)

    ;; Memory declaration (1 page = 64KB, growable)
    (emit! cg "(memory (export \"memory\") 1)")
    (emit! cg "")

    ;; Data segments (string literals, populated during function codegen)
    ;; (deferred — emitted after functions are processed)

    ;; Global mutable stack pointer for alloc
    (emit! cg "(global $__stack_ptr (mut i32) (i32.const 1024))")
    (emit! cg "")

    ;; Functions
    (doseq [func (:functions module)]
      (emit-function! cg func export-names)
      (emit! cg ""))

    ;; Data segments (string literals collected during codegen)
    (doseq [{:keys [offset bytes]} @(:data-segments cg)]
      (emit! cg (str "(data (i32.const " offset ") \"" bytes "\")")))

    (swap! (:indent cg) dec)
    (emit! cg ")")

    (str/join "\n" @(:output cg))))
```

### Modify: `src/aria/main.clj`

- Add `--emit-wat` flag in `parse-args`
- Add `--backend wasm` flag
- Add `compile-wat!` function:
  ```clojure
  (defn compile-wat! [wat-path wasm-path]
    ;; wat2wasm input.wat -o output.wasm
    (let [proc (.start (ProcessBuilder. ["wat2wasm" wat-path "-o" wasm-path]))]
      (.waitFor proc)
      (.exitValue proc)))
  ```
- Add `optimize-wasm!` function:
  ```clojure
  (defn optimize-wasm! [wasm-path out-path]
    ;; wasm-opt -O3 input.wasm -o output.wasm
    (let [proc (.start (ProcessBuilder. ["wasm-opt" "-O3" wasm-path "-o" out-path]))]
      (.waitFor proc)
      (.exitValue proc)))
  ```
- In `process-file`, when backend is wasm:
  ```
  codegen-wat/generate → write .wat → wat2wasm → .wasm → wasm-opt → .wasm
  ```
- Add `--run` support for WASM via `wasmtime` or Node.js:
  ```clojure
  (defn run-wasm! [wasm-path]
    (let [proc (.start (doto (ProcessBuilder. ["wasmtime" wasm-path]) (.inheritIO)))]
      (.waitFor proc)
      (.exitValue proc)))
  ```

### Create: `test/aria/codegen_wat_test.clj`

- Test `type->wat` for all ARIA types
- Test `generate` on `fibonacci.aria` — verify output contains `i32.add`, `call $fibonacci`, `loop`, `br_if`
- Test that generated WAT is valid by running `wat2wasm` on it
- Test the full pipeline: `.aria` → `.wat` → `.wasm` → `wasmtime` → correct output

### Verification

```bash
# Emit WAT
clojure -M:run examples/fibonacci.aria --emit-wat

# Full pipeline
clojure -M:run examples/fibonacci.aria --backend wasm -o /tmp/fib.wasm
wasmtime /tmp/fib.wasm
```

### Concrete Example: fibonacci.aria → WAT

Input:
```lisp
(func $fibonacci (param $n i32) (result i32) (effects pure)
  (if (le.i32 $n 1) (then (return $n))
    (else
      (let %a i32 (call $fibonacci (sub.i32 $n 1)))
      (let %b i32 (call $fibonacci (sub.i32 $n 2)))
      (return (add.i32 %a %b)))))
```

Expected output:
```wat
(func $fibonacci (param $n i32) (result i32)
  (local $a i32)
  (local $b i32)
  local.get $n
  i32.const 1
  i32.le_s
  if (result i32)
    local.get $n
  else
    local.get $n
    i32.const 1
    i32.sub
    call $fibonacci
    local.set $a
    local.get $n
    i32.const 2
    i32.sub
    call $fibonacci
    local.set $b
    local.get $a
    local.get $b
    i32.add
  end)
```

---

## Phase 2: Memory, Strings, and Print

**Goal:** Handle memory operations (`alloc`/`free`/`load`/`store`), string literals, and `print` statements so that `bubble_sort.aria` and `math_demo.aria` compile correctly.

### Memory Model

WASM linear memory is a flat byte array. The codegen manages it with a simple bump allocator:

- Bytes 0–1023: reserved (null zone + static data)
- Bytes 1024+: static data (string literals), placed at compile time
- After static data: heap (bump-allocated at runtime via `$__stack_ptr` global)

#### `alloc` → bump allocator

```wat
;; (alloc i32 8) → allocate 8 * 4 = 32 bytes
(global $__stack_ptr (mut i32) (i32.const 1024))

(func $__alloc (param $size i32) (result i32)
  (local $ptr i32)
  global.get $__stack_ptr
  local.set $ptr
  global.get $__stack_ptr
  local.get $size
  i32.add
  global.set $__stack_ptr
  local.get $ptr)
```

Emit this built-in function automatically when the module uses `mem` effects.

#### `free` → no-op (bump allocator)

For Phase 2, `free` is a no-op. A proper free-list allocator can be added later or provided as an imported function.

#### `store`/`load`

Direct mapping:

| ARIA | WAT |
|---|---|
| `(store.i32 ptr val)` | `emit ptr; emit val; i32.store` |
| `(load.i32 ptr)` | `emit ptr; i32.load` |
| `(store.i64 ptr val)` | `emit ptr; emit val; i64.store` |
| `(load.field ptr "x")` | `emit ptr; i32.load offset=<field_offset>` |

Field offsets computed from struct type definitions using `sizeof` rules:
- i32/u32/f32/ptr: 4 bytes
- i64/u64/f64: 8 bytes
- bool/i8/u8: 1 byte (but aligned to 4)

### String Literals

String literals are placed in the WASM data section at compile time:

```clojure
(defn- intern-string! [cg s]
  (let [offset @(:data-offset cg)
        bytes (count s)]
    (swap! (:data-segments cg) conj {:offset offset :bytes (escape-wat-string s)})
    (swap! (:data-offset cg) + bytes)
    {:ptr offset :len bytes}))
```

When a string literal appears in code, emit `i32.const <offset>` (pointer) and `i32.const <len>` (length).

### Print

`print` requires a host function. Emit an import declaration:

```wat
(import "env" "print" (func $__print (param i32 i32)))  ;; ptr, len
```

For format strings with arguments (`"fib(%d) = %d\n"`), the codegen must:
1. Format the string at runtime — OR
2. Split into multiple print calls: `print("fib(")`, `print_i32(val)`, `print(") = ")`, etc.

Option 2 is simpler and avoids runtime string formatting. Emit imports for typed printers:

```wat
(import "env" "print" (func $__print (param i32 i32)))       ;; string: ptr + len
(import "env" "print_i32" (func $__print_i32 (param i32)))   ;; integer
(import "env" "print_i64" (func $__print_i64 (param i64)))   ;; long
(import "env" "print_f64" (func $__print_f64 (param f64)))   ;; float
```

Then `(print "fib(%d) = %d\n" $i %val)` becomes:

```wat
i32.const 100    ;; ptr to "fib("
i32.const 4      ;; len
call $__print
local.get $i
call $__print_i32
i32.const 104    ;; ptr to ") = "
i32.const 4      ;; len
call $__print
local.get $val
call $__print_i32
i32.const 108    ;; ptr to "\n"
i32.const 1      ;; len
call $__print
```

A helper `split-format-string` parses the C-style format string and returns a sequence of `[:literal "text"]` or `[:placeholder "d" arg-node]` entries.

### Struct Layout: `compute-struct-layout`

```clojure
(defn- compute-struct-layout [struct-type]
  ;; Returns {field-name -> {:offset N :size N :wat-type "i32"}}
  (loop [fields (:type/fields struct-type)
         offset 0
         layout {}]
    (if (empty? fields)
      layout
      (let [[fname ftype] (first fields)
            size (type-size ftype)
            aligned-offset (align offset size)]
        (recur (rest fields)
               (+ aligned-offset size)
               (assoc layout fname {:offset aligned-offset
                                    :size size
                                    :wat-type (type->wat ftype)}))))))
```

### Verification

```bash
# bubble_sort uses alloc, store, load, free, print
clojure -M:run examples/bubble_sort.aria --emit-wat > /tmp/bsort.wat
wat2wasm /tmp/bsort.wat -o /tmp/bsort.wasm

# math_demo uses cast, multiple functions, print
clojure -M:run examples/math_demo.aria --backend wasm --run
```

---

## Phase 3: WASM Imports for External Functions

**Goal:** Allow ARIA modules to declare external functions that will be provided by the host environment (browser, Node.js, WASI runtime). This is the mechanism for calling DOM APIs, HTTP, storage, etc.

### Approach

Rather than adding new syntax to ARIA, use a convention: functions declared with `(extern ...)` at the module level are emitted as WASM imports instead of function definitions.

### Modify: `src/aria/ast.clj`

Add extern function node:

```clojure
(defn extern-func [name params result module-name]
  {:node/type :extern
   :name name
   :params params
   :result result
   :extern/module module-name})  ;; e.g. "env", "wasi"
```

### Modify: `src/aria/parser.clj`

Parse `(extern "env" $print (param $ptr i32) (param $len i32))`:

- New `parse-extern` function — similar to `parse-function` but only parses signature (no body, no effects)
- Update `parse-module` dispatch to handle `"extern"`
- Externs are collected into a new `:externs` field on the module

### Modify: `src/aria/checker.clj`

- Register extern functions in the function registry alongside regular functions
- Extern calls are valid — treat them as having all effects (or specific declared effects)

### Modify: `src/aria/codegen_wat.clj`

Emit externs as WASM imports:

```clojure
(doseq [ext (:externs module)]
  (let [params (str/join " " (map #(str "(param " (type->wat (:param/type %)) ")") (:params ext)))
        result (when (:result ext) (str "(result " (type->wat (:result ext)) ")"))]
    (emit! cg (str "(import \"" (:extern/module ext) "\" \"" (var->wat (:name ext)) "\""
                   " (func $" (var->wat (:name ext)) " " params " " result "))"))))
```

### Modify: `src/aria/codegen_c.clj`

Emit externs as C `extern` declarations (for compatibility with the C backend):

```c
extern void print(int32_t ptr, int32_t len);
```

### Example ARIA with externs

```lisp
(module "webapp"
  (extern "env" $log (param $ptr i32) (param $len i32))
  (extern "env" $dom_replace (param $ptr i32) (param $len i32))
  (extern "env" $http_get (param $url_ptr i32) (param $url_len i32) (param $cb i32))

  (func $main (result i32) (effects io)
    (call $log 0 5)  ;; log string at memory offset 0, length 5
    (return 0))

  (export $main))
```

### Verification

- Test: parse `(extern ...)` produces correct AST node
- Test: checker accepts calls to extern functions
- Test: codegen emits `(import ...)` in WAT output
- Test: generated WASM loads in a JS host that provides the imported functions

---

## Phase 4: CLI Integration and LLM Generator Update

**Goal:** Integrate the WASM backend into the CLI and update the LLM generator.

### Modify: `src/aria/main.clj`

Final CLI interface:

```
clojure -M:run <file.aria>                          # Default: emit C
clojure -M:run <file.aria> --emit-wat               # Emit WAT to stdout
clojure -M:run <file.aria> --backend wasm -o out.wasm  # Full pipeline: WAT → wasm → opt
clojure -M:run <file.aria> --backend wasm --run      # Compile + run with wasmtime
clojure -M:run <file.aria> --backend wasm --optimize # Apply wasm-opt
```

Add `:wasm` alias to `deps.edn`:

```clojure
:wasm {:main-opts ["-m" "aria.main" "--backend" "wasm"]}
```

### Modify: `src/aria/gen.clj`

- Add `--backend wasm` to generator CLI
- Update `condensed-spec` with `(extern ...)` syntax
- When backend is wasm, validate generated WAT with `wat2wasm --validate`

### Comprehensive Test Suite

Create `test/aria/codegen_wat_test.clj`:

- `fibonacci.aria` → WAT → wasm → wasmtime → correct output
- `bubble_sort.aria` → WAT → wasm → wasmtime → correct output (tests memory ops)
- `math_demo.aria` → WAT → wasm → wasmtime → correct output (tests casts)
- Extern functions parse and emit correctly
- Invalid programs produce checker errors (not WAT errors)

---

## Critical Files

| File | Phase | Action |
|---|---|---|
| `src/aria/codegen_wat.clj` | 1, 2, 3 | **Create** — core of this plan |
| `src/aria/main.clj` | 1, 4 | Modify — add `--emit-wat`, `--backend wasm`, `compile-wat!`, `optimize-wasm!` |
| `src/aria/ast.clj` | 3 | Modify — add `extern-func` node |
| `src/aria/parser.clj` | 3 | Modify — add `parse-extern`, update `parse-module` |
| `src/aria/checker.clj` | 3 | Modify — register externs in function registry |
| `src/aria/codegen_c.clj` | — | Reference only — structural template for codegen_wat |
| `src/aria/gen.clj` | 4 | Modify — add `--backend wasm` |
| `test/aria/codegen_wat_test.clj` | 1, 2, 4 | **Create** — test suite |
| `examples/fibonacci.aria` | 1 | Reference — first test target |
| `examples/bubble_sort.aria` | 2 | Reference — memory ops test target |
| `examples/math_demo.aria` | 2 | Reference — cast test target |

## External Tools Required

| Tool | Package | Purpose |
|---|---|---|
| `wat2wasm` | [WABT](https://github.com/WebAssembly/wabt) | WAT text → WASM binary |
| `wasm-opt` | [Binaryen](https://github.com/WebAssembly/binaryen) | WASM optimization |
| `wasmtime` | [Wasmtime](https://wasmtime.dev/) | WASM execution (for `--run` and tests) |
