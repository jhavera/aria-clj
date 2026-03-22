# ARIA WAT Backend: C/WASM Parity Plan (8 Phases)

Six known incompatibilities exist between the C and WASM backends.
Each is addressed in its own phase, followed by integration testing
and cross-backend validation.

## Phase Dependencies

```
Phase 1 (structs)       ─┐
Phase 2 (globals)       ─┤
Phase 3 (print specs)   ─┼── Independent, any order
Phase 5 (float edges)   ─┤
Phase 6 (overflow docs) ─┘
Phase 4 (free-list)     ─── Independent, most complex
Phase 7 (integration)   ─── Depends on 1–6
Phase 8 (cross-backend) ─── Depends on 7
```

Recommended order: 1 → 2 → 3 → 5 → 6 → 4 → 7 → 8

---

## Phase 1: Struct Field Offsets (`load-field` / `store-field`)

**Problem**: C backend emits `ptr->field`; C compiler computes offsets.
WAT backend emits bare `i32.load` / `i32.store` with no offset — any
struct with more than one field reads/writes the wrong memory location.

**Implementation**:

1. Add `struct-types` atom to `make-codegen` — map from struct name to
   struct type definition. Populate from `(:types module)` in `generate`.

2. Create `compute-field-offset` — walk `:type/fields` in order, sum
   `type-size` for each field with natural alignment (capped at 4 for
   WASM32). Return `{:offset N :wat-type "i32"}`.

3. Create `resolve-struct-type` — use `infer-type` on the ptr expression,
   extract `:type/pointee`, look up in `@(:struct-types cg)`.

4. Update `emit-expr!` for `:load-field`:
   ```clojure
   (let [struct-t (resolve-struct-type cg (:ptr node))
         {:keys [offset wat-type]} (compute-field-offset struct-t (:field-name node))]
     (emit-expr! cg (:ptr node))
     (emit! cg (str wat-type ".load offset=" offset)))
   ```

5. Update `emit-stmt!` for `:store-field` — same pattern with `.store offset=`.

6. Update `infer-type` for `:load-field` — return the field's type.

7. Update `type-size` for struct types — sum of field sizes with alignment.

**Files**: `src/aria/codegen_wat.clj`

**Verify**: ARIA module with a 3-field struct, store to all fields, load
from the third — confirm correct value via wasmtime.

---

## Phase 2: Global Variables

**Problem**: C backend emits globals. WAT `generate` ignores `(:globals module)`.

**Implementation**:

1. Add `global-names` set to `make-codegen` — populated from `(:globals module)`.

2. Emit `(global ...)` declarations in `generate`, after memory:
   ```wat
   (global $counter (mut i32) (i32.const 0))
   ```
   Use literal init values when available; default to zero otherwise.

3. Create `default-init-value` helper — maps WAT type to const expression:
   `"i32"` → `"i32.const 0"`, `"f64"` → `"f64.const 0"`, etc.

4. Update `emit-expr!` `:var-ref` — check `global-names`; emit `global.get`
   instead of `local.get` for globals.

5. Update `emit-stmt!` `:set-var` — emit `global.set` for globals.

6. Register global types in `local-types` for pointer arithmetic inference.

**Files**: `src/aria/codegen_wat.clj`

**Verify**: Module with mutable global, increment in a function, print it.
Confirm `global.get`/`global.set` in WAT output and correct wasmtime result.

---

## Phase 3: Print Format Specifiers

**Problem**: WASI runtime only handles `%d`, `%ld`, `%f`. Missing: `%u`,
`%x`/`%X`, `%o`, width/padding, precision.

**Implementation**:

1. Enhance `split-format-string` to parse full C-style format specs —
   consume flags (`-+0#`), width, `.precision`, length modifier, conversion
   character. Return full spec string (e.g., `"05d"`, `"x"`).

2. Add WASI runtime functions:
   - `$__print_u32` — unsigned i32 (no negative sign, `div_u`/`rem_u`)
   - `$__print_hex` — i32 as lowercase hex (digits 0-9 + a-f)
   - `$__print_HEX` — i32 as uppercase hex (digits 0-9 + A-F)
   - `$__print_oct` — i32 as octal

3. Update `placeholder->print-func` — map `"u"`, `"x"`, `"X"`, `"o"`,
   `"lu"` to new functions.

4. Extend `:print-types` atom to conditionally emit new runtime functions.

5. Width/padding (`%05d`, `%10.2f`): extract conversion char, ignore
   width for now, add TODO comment. Full width support is non-trivial
   in pure WASM and can be deferred.

**Files**: `src/aria/codegen_wat.clj`

**Verify**: Module with `(print "%u %x %X\n" 42 255 255)` — confirm
correct wasmtime output (`42 ff FF`).

---

## Phase 4: Free-List Allocator

**Problem**: Bump allocator never frees. Programs doing alloc/free in
loops exhaust the single 64KB page.

**Implementation**:

1. Add free-list global:
   ```wat
   (global $__free_list (mut i32) (i32.const 0))  ;; 0 = empty
   ```

2. Block header layout (8 bytes before the returned pointer):
   - offset -8: block size (i32)
   - offset -4: next-free pointer (i32)

3. Replace `emit-alloc-function!` with free-list-aware allocator:
   - Walk free list for block ≥ requested size
   - If found: unlink, return pointer (header + 8)
   - If not found: bump-allocate (advance `$__stack_ptr` by size + 8)
   - Store block size in header

4. Replace `free` no-op in `emit-stmt!`:
   - Compute header address (`ptr - 8`)
   - Prepend block to free list head

5. Add `memory.grow` support in alloc:
   - After computing new `$__stack_ptr`, check against `memory.size * 65536`
   - If exceeded, call `(memory.grow (i32.const 1))`
   - If returns -1, trap

6. Keep bump allocator as option — add `:allocator` key to `generate`
   options (`:bump` or `:free-list`, default `:free-list`).

**Files**: `src/aria/codegen_wat.clj`

**Verify**: Alloc/free 1000 times in a loop — confirm memory doesn't grow
unboundedly. Alloc, free, alloc again — confirm reuse (stack_ptr stable).

---

## Phase 5: Float Precision Edge Cases

**Problem**: `$__print_f64` calls `i64.trunc_f64_s` which traps on NaN,
Infinity, and values outside i64 range.

**Implementation**:

1. Add NaN check at the start of `$__print_f64`:
   ```wat
   ;; NaN: val != val
   (if (f64.ne (local.get $val) (local.get $val))
     (then ;; print "NaN", return
   ))
   ```

2. Add Infinity checks:
   ```wat
   (if (f64.eq (local.get $val) (f64.const inf))
     (then ;; print "inf", return
   ))
   (if (f64.eq (local.get $val) (f64.const -inf))
     (then ;; print "-inf", return
   ))
   ```

3. Add overflow guard — for |val| ≥ 2^53, skip fractional part (no
   meaningful precision) and print integer part only.

4. Add doc comment in `codegen_wat.clj` explaining:
   - WASM: strict IEEE 754 (no extended precision)
   - C on x86: may use 80-bit internally
   - Results may differ in last ULP; not a bug

**Files**: `src/aria/codegen_wat.clj`

**Verify**: `(div.f64 0.0 0.0)` prints "NaN", `(div.f64 1.0 0.0)` prints
"inf" — no traps.

---

## Phase 6: Signed Integer Overflow Documentation

**Problem**: WASM wraps (modular). C signed overflow is UB. Compilers
may optimize assuming no overflow, producing different results at `-O2+`.

**Implementation**:

1. Add doc comments in both `codegen_c.clj` and `codegen_wat.clj`
   explaining the semantic difference.

2. Document recommendation: use unsigned types (`u32`, `u64`) when
   wrapping behavior is intended.

3. For cross-backend tests (Phase 8), compile C with `-fwrapv` to
   match WASM wrapping semantics.

**Files**: `src/aria/codegen_c.clj`, `src/aria/codegen_wat.clj`

**Verify**: Documentation only — verify comments exist in generated output.

---

## Phase 7: Integration Tests

Create `test/aria/backend_compat_test.clj` exercising all 6 areas:

| Test | What it covers |
|------|---------------|
| Struct with 3 fields | Phase 1: field offset computation |
| Mutable global | Phase 2: global get/set |
| Format specifiers `%u %x %X` | Phase 3: extended print |
| Alloc/free loop (100×) | Phase 4: free-list reuse |
| NaN / Infinity printing | Phase 5: float edge cases |
| `add.i32 MAX_INT 1` | Phase 6: overflow wrapping |

Each test: parse → check → generate WAT → structural assertions.
Optionally: `wat2wasm` → wasmtime (guarded by tool availability).

**Files**: `test/aria/backend_compat_test.clj`

---

## Phase 8: Cross-Backend Validation

Create `test/aria/cross_backend_test.clj` that runs the same `.aria`
source through both backends and compares stdout output.

1. Helpers: `run-c-backend` (parse → codegen-c → gcc → capture stdout),
   `run-wasm-backend` (parse → codegen-wat WASI → wat2wasm → wasmtime
   → capture stdout).

2. Test cases: all 3 existing examples + Phase 7 test modules.

3. Float comparison: epsilon tolerance, not exact match.

4. C compilation: use `gcc -fwrapv` to match WASM wrapping semantics.

5. Guard: skip when external tools (gcc, wat2wasm, wasmtime) unavailable.

**Files**: `test/aria/cross_backend_test.clj`

---

## Effort Summary

| Phase | Effort | Priority |
|-------|--------|----------|
| 1. Struct fields | Medium | Critical — broken |
| 2. Globals | Low | Critical — missing |
| 3. Print specifiers | Medium | Important |
| 4. Free-list allocator | High | Important |
| 5. Float edge cases | Low | Nice to have |
| 6. Overflow docs | Low | Nice to have |
| 7. Integration tests | Medium | Required |
| 8. Cross-backend | Medium | Required |
