# Plan: ARIA Self-Hosting via Zig Backend

## Context

ARIA is an s-expression IR with a Clojure compiler (~1800 LOC) targeting C and WAT. The goal is **true self-hosting**: write the ARIA compiler in ARIA itself, targeting Zig, so users only need two binaries (`aria` + `zig`) to compile ARIA programs. Additionally, ARIA comptime should map to Zig's native comptime, eliminating the current two-pass subprocess model.

> **Priority #1: Bootstrap the ARIA-IR compiler.**
> Before optimizing backends or adding features, the primary objective is to get an ARIA compiler written in ARIA-IR that can compile itself. Everything else (Zig backend polish, native comptime, etc.) is secondary until the self-hosting loop closes: ARIA source → Clojure compiler → executable → that executable compiles its own source.

---

## Phase 0: Zig Backend (from Clojure)

**Prerequisite for everything else.** A plan already exists (`PLAN-zig-backend-multi-module.md`).

Create `src/aria/codegen_zig.clj` as drop-in replacement for `codegen_c.clj`:
- Type mapping is near-identity: `i32`→`i32`, `f64`→`f64`, `(ptr T)`→`*T`
- `%var` → `const`, `$var` → `var` (perfect mapping)
- `print` → `std.io.getStdOut().writer().print()` with C→Zig format conversion
- `alloc`/`free` → `std.heap.page_allocator`
- No forward declarations, no headers needed

**Milestone**: All examples compile to Zig and produce identical output to C backend.

---

## Phase 1: Language Features for Self-Hosting

Minimal feature set needed to write a compiler in ARIA. **Strict rule**: if ARIA doesn't have it, don't add it. Use existing types and constructs wherever possible.

### 1.1 String literals as values (using existing types)
Strings are `(ptr u8)` — no new type needed. ARIA already has `(ptr T)`, `load`, `store`, `alloc`.
- String literals assignable to variables: `(let %s (ptr u8) "hello")`
- The codegen maps this to `[]const u8` in Zig / `const char*` in C
- String operations are **library functions in ARIA**, not language features:
  - `$str_len`, `$str_eq`, `$str_get`, `$str_slice`, `$str_concat` — written in ARIA using existing pointer/memory ops
- Only parser/codegen change: allow string literals in `let` expressions (currently only in `print`)
- **Files**: `parser.clj` (minor), `codegen_zig.clj` (string literal emission)

### 1.2 Switch/match (CRITICAL)
- `(switch %expr (case value body...) ... (default body...))`
- Without this, the parser would be 30+ nested `if`s
- For integer tags: codegen emits native switch/if-chain
- For `(ptr u8)` comparisons: codegen emits chained `if/else if` with `std.mem.eql` (Zig) or `strcmp` (C)
- **Files**: `ast.clj`, `parser.clj`, `checker.clj`, `codegen_zig.clj`

### 1.3 Dynamic containers via comptime
- `DynArray_T` (append, get, len) and `HashMap_PtrU8_T` (put, get, contains)
- Built as ARIA structs using existing `(ptr T)`, `alloc`, `store`, `load`
- Generated via existing comptime generics pattern for ~5 concrete types
- **~300 lines of ARIA** for the containers themselves

### 1.4 File I/O + CLI args + error handling
- Extern declarations using existing `(extern ...)` syntax:
  - `(extern "io" $file_read_all (param $path (ptr u8)) (result (ptr u8)))`
  - `(extern "io" $file_write (param $path (ptr u8)) (param $content (ptr u8)))`
  - `(extern "sys" $args_count (result i32))`, `$args_get (param $i i32) (result (ptr u8))`
  - `(extern "sys" $trap (param $msg (ptr u8)))` → stderr + exit(1)
- Codegen injects Zig runtime implementations for these externs
- **Files**: `codegen_zig.clj` (runtime functions)

### Implementation order: 1.1 → 1.2 → 1.4 (parallel) → 1.3

### What does NOT change in Phase 1
- No new types added to the type system
- No new node types except `switch` (1.2)
- Strings remain `(ptr u8)` — string operations are ARIA functions, not builtins

---

## Phase 2: ARIA Compiler in ARIA

Rewrite each compiler module in ARIA. Test each independently against the Clojure compiler.

### AST representation: flat struct with tag
```
(type $AstNode (struct
  (field $tag i32)
  (field $int_val i64)
  (field $float_val f64)
  (field $str_val (ptr u8))
  (field $str_val2 (ptr u8))
  (field $left (ptr $AstNode))
  (field $right (ptr $AstNode))
  (field $children (ptr $DynArray_AstNode))
  (field $type_info (ptr $TypeInfo))
  (field $bool_val bool)))
```
~100 bytes/node × 1000 nodes = 100KB. Irrelevant for a compiler.

### Modules (~2600-3800 LOC ARIA total)

| Module | ARIA LOC | Replaces |
|--------|----------|----------|
| `std/` (str ops on `(ptr u8)`, dynarray, hashmap, io) | 500-800 | — |
| Reader (s-expression parser) | 200-300 | `reader.clj` (175 LOC) |
| Parser (validate + build AST) | 600-800 | `parser.clj` (395 LOC) |
| Checker (types + effects) | 500-700 | `checker.clj` (291 LOC) |
| Codegen Zig (emit .zig text) | 600-900 | `codegen_zig.clj` (~400 LOC) |
| Main/CLI (orchestration) | 200-300 | `main.clj` (424 LOC) |

### Key design decisions
- **No closures, no GC, no generics syntax** — use comptime for generics, manual alloc/free
- **First version emits `.zig` only** — a wrapper script calls `zig build-exe`
- **Die on first error** — no error recovery in bootstrap compiler
- **Single large file initially** — multi-module can come later

### Testing strategy
Compile each ARIA module with the Clojure compiler → run against test `.aria` files → compare output with Clojure compiler output.

---

## Phase 3: Bootstrap

```
aria-compiler.aria
    → [Clojure compiler --backend zig] → aria-compiler.zig
    → [zig build-exe] → aria (native binary)
    → aria aria-compiler.aria --emit-zig → aria-compiler-v2.zig
    → diff aria-compiler.zig aria-compiler-v2.zig  # MUST be identical
```

**Verification**: byte-identical output from Clojure-compiled and self-compiled versions.

**Risk**: Non-deterministic codegen (e.g., hashmap iteration order) breaks this. Mitigation: use ordered structures where output order matters.

---

## Phase 4: Native Comptime (ARIA → Zig comptime)

### Two mappings

**comptime-val → trivial**:
```
(let %x i32 (comptime-val i32 (mul.i32 6 7)))
→ const x: i32 = comptime 6 * 7;
```

**Generics → new `(generic ...)` form**:
```aria
(generic $max (comptime-param $T type)
  (param $a $T) (param $b $T) (result $T)
  (if (gt.$T $a $b) (then (return $a)) (else (return $b))))
```
→
```zig
fn max(comptime T: type, a: T, b: T) T {
    return if (a > b) a else b;
}
```

### Steps
1. Map `comptime-val` → `comptime { expr }` in Zig codegen
2. Add `(generic ...)` as new top-level form in parser/checker/codegen
3. Rewrite std containers as native generics
4. Remove subprocess model from self-hosted compiler

---

## What we deliberately DON'T add
- Garbage collector (manual alloc/free; compiler has simple lifetimes)
- Closures/lambdas (function pointers if needed)
- Full generic type system (comptime patterns suffice)
- Optimizations (Zig `-O ReleaseFast` handles this)
- Error recovery (die on first error is fine for bootstrap)
- Tagged unions as language feature (flat structs with tag i32)
- New types (strings = `(ptr u8)`, not a new `str` type)

---

## Risks

1. **Scope creep in Phase 1**: Each feature tempts adding more. Discipline: only what the compiler needs.
2. **Feature discovery in Phase 2**: Writing the compiler will reveal missing features. Budget extra time.
3. **Verbosity**: ~3500 LOC of ARIA without closures/destructuring will be repetitive. Acceptable for bootstrap.
4. **Debugging**: No debugger for ARIA. Mitigation: abundant `print` + incremental testing.

---

## Verification

- **Phase 0**: `clojure -M:run examples/fibonacci.aria --backend zig --run` produces identical output to C
- **Phase 1**: New features tested with dedicated `.aria` examples
- **Phase 2**: Each module tested against Clojure compiler output
- **Phase 3**: Bootstrap byte-identical test passes
- **Phase 4**: Comptime examples compile without subprocess
