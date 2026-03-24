# Pending C ↔ WAT Backend Incompatibilities

Status: **Mostly resolved** — only inherent platform differences remain (not fixable in codegen).

---

## ~~1. `if` as Expression~~ — RESOLVED

Added `:if` case to `emit-expr!` in `codegen_wat.clj`. Emits block-typed
`if (result <type>)` with the last node of each branch as the value expression.
The result type is inferred via `infer-type` on the last then-branch node.
`emit-stmt!` keeps the existing void-typed `if ... end` form unchanged.

---

## ~~2. Print `%c` and `%p`~~ — RESOLVED

Added `"c"` and `"p"` cases to `placeholder->print-func` in `codegen_wat.clj`.

- `%c` → `$__print_char`: stores the i32 value as a single byte at scratch
  offset 16, then calls `$__print` with len=1. Prints the ASCII character.
- `%p` → `$__print_ptr`: writes `0x` prefix at scratch offsets 16–17, calls
  `$__print` for the prefix, then delegates to `$__print_hex` for the hex
  digits. Automatically emits `$__print_hex` if not already requested.

Both functions are conditionally emitted (only when `:char` / `:ptr` appear
in `print-types`), following the existing on-demand pattern.

---

## ~~3. Print `%e`/`%E`/`%g`/`%G` — Scientific Notation~~ — RESOLVED

Added `"e"`, `"E"`, `"g"`, `"G"` cases to `placeholder->print-func` in
`codegen_wat.clj`.

- `%e`/`%E` → `$__print_f64e` / `$__print_f64E`: normalizes the value to
  `[1.0, 10.0)` via repeated multiply/divide by 10, prints mantissa as
  `d.dddddd`, then `e+`/`e-`/`E+`/`E-` followed by the exponent (at least
  2 digits). Handles NaN, ±Infinity, and zero as special cases.
- `%g`/`%G` → `$__print_f64g` / `$__print_f64G`: checks if |val| is in
  `[0.0001, 1000000)` — if so, delegates to `$__print_f64` (fixed-point);
  otherwise delegates to the matching scientific notation function.

Dependencies (`$__print_i64`, `$__print_f64`, `$__print_f64e`) are
automatically emitted when needed, even if not explicitly requested by
other format specifiers.

---

## ~~4. Print Width/Precision (`%05d`, `%10.2f`)~~ — RESOLVED

Implemented via a format buffer mode in `codegen_wat.clj`:

- **`parse-format-modifiers`**: Clojure function that extracts width, precision,
  zero-pad flag, and left-align flag from the full-spec string at codegen time.
- **Buffer mode in `$__print`**: Two globals (`$__buf_mode`, `$__buf_len`) and
  a 256-byte buffer at scratch offsets 64–319. When `$__buf_mode=1`, `$__print`
  appends to the buffer instead of writing to `fd_write`.
- **`$__flush_padded`**: Takes width, pad-char (space/zero), and left-align flag.
  Compares buffered length to requested width, emits padding before or after
  content. Handles the special case of zero-padding negative numbers (prints `-`
  before zeros, then digits).
- **`$__print_f64_p`**: Precision-aware variant of `$__print_f64` that accepts
  the number of decimal places as a parameter. Uses a loop to compute `10^prec`
  and prints exactly N fractional digits with leading zeros.
- **Emit site**: When a placeholder has width or precision modifiers, the codegen
  activates buffer mode, calls the print function, then flushes with padding.

Verified output matches `printf` for: `%05d`, `%10.2f`, `%-10d`, `%08d` (negative).

---

## ~~5. Math Functions~~ — NOT APPLICABLE

ARIA's parser only accepts `neg`, `not`, `sqrt`, `abs`, `ceil`, `floor` as unary
ops (see `parser.clj:82`). These are exactly the operations WASM provides natively.
Transcendental functions (`sin`, `cos`, `log`, `exp`) are not part of the ARIA IR
and would be rejected at parse time. No gap exists between backends.

> **Future opportunity — two complementary approaches**:
>
> ### A. C FFI via `extern` (works today)
>
> ARIA's `extern` already supports declaring host-provided functions with a
> module name. This maps directly to WASM imports, enabling C library linking
> at runtime or statically.
>
> **Runtime linking (immediate, no codegen changes):**
>
> Write a thin C wrapper that re-exports libm functions:
> ```c
> #include <math.h>
> __attribute__((export_name("sin"))) double w_sin(double x) { return sin(x); }
> __attribute__((export_name("cos"))) double w_cos(double x) { return cos(x); }
> ```
>
> Compile once with wasi-sdk:
> ```bash
> clang --target=wasm32-wasi -mexec-model=reactor -o libm.wasm libm_wasm.c -lm
> ```
>
> ARIA source uses extern with matching module name:
> ```aria
> (extern "math" $sin (param $x f64) (result f64))
> (extern "math" $cos (param $x f64) (result f64))
> ```
>
> Run with preloaded module:
> ```bash
> wasmtime --preload math=libm.wasm app.wasm
> ```
>
> This works with the existing codegen — no compiler changes needed. The WASM
> runtime resolves `"math"` → `libm.wasm` at instantiation time. Applies to
> any C library, not just math.
>
> **Static linking (future evolution):**
>
> For a single self-contained `.wasm` binary with no runtime dependencies:
> ```bash
> clang --target=wasm32-wasi -c -o libm.o libm_wasm.c
> wat2wasm --relocatable app.wat -o app.o
> wasm-ld app.o libm.o -o app.wasm --export-all -lc --sysroot=...
> ```
>
> This requires the WAT codegen to emit relocation sections and linking
> metadata that `wasm-ld` expects — a significant change. The codegen would
> need to produce relocatable object files instead of plain text WAT, or
> emit the WAT with the proper symbol/relocation directives.
>
> **Effort**: Low for runtime linking (works today), High for static linking
> (needs codegen changes for relocatable output).
>
> **Note**: `wasm-opt -O3` performs tree-shaking — it eliminates unreferenced
> functions, dead globals, and unreachable code. This means statically linking
> an entire C library (e.g., all of libm) carries no size penalty: only the
> functions actually called from ARIA and their transitive dependencies survive
> in the final binary. This makes static linking the ideal long-term target —
> a single self-contained `.wasm` with no runtime module dependencies and no
> dead code bloat.
>
> ### B. Pure ARIA math stdlib (alternative)
>
> A `stdlib/math.aria` library could implement transcendental functions
> (`sin`, `cos`, `tan`, `log`, `exp`, `atan2`, `pow`, etc.) as pure ARIA
> using polynomial approximations (Chebyshev/minimax) on top of the existing
> float primitives and `sqrt`. Since it would be written in ARIA-IR, it would
> compile identically through both backends, and any ARIA program could
> import it — no FFI, no external toolchain, fully portable.
>
> However, this means reimplementing well-tested C library code. The FFI
> approach (A) is more practical for leveraging existing C libraries at
> scale; the pure ARIA approach (B) is better for self-contained, portable
> modules that need zero external dependencies.

---

## ~~6. Global with Non-Literal Init~~ — RESOLVED

Globals whose `:init` is not a literal (`:int-literal`, `:float-literal`,
`:bool-literal`) are now initialized at runtime in `$__start` (`_start`).

- `global-init-expr` still emits zero-init for non-literal inits (required by
  WASM's constant-expression constraint).
- `generate` computes `globals-needing-init` by filtering globals with
  non-literal init expressions.
- `emit-wasi-start!` now accepts `globals-needing-init` and emits
  `emit-expr!` + `global.set` for each one, before calling `$main`.

---

## 7. Signed Integer Overflow (Inherent)

**Problem**: This is a fundamental semantic difference between the two targets, not
a codegen bug.

- **WASM**: All integer arithmetic wraps modularly (2^32 for i32, 2^64 for i64).
  `2147483647 + 1 = -2147483648` always.
- **C**: Signed integer overflow is undefined behavior (UB). At `-O0`, most
  compilers wrap. At `-O2+`, compilers may optimize assuming overflow never
  happens, producing different results or removing "impossible" branches.

**Example**:
```aria
(let %x i32 (add.i32 2147483647 1))
(print "%d\n" %x)
```

- WASM: always prints `-2147483648`.
- C at `-O0`: likely prints `-2147483648`.
- C at `-O2`: **undefined** — compiler may eliminate the print, assume `%x > 0`,
  or do anything.

**Mitigation**: Compile C output with `gcc -fwrapv` or `clang -fwrapv` to force
wrapping semantics matching WASM. This is already documented in the codegen source
and used in cross-backend tests.

**Not fixable in codegen** — this is a platform semantics difference.

---

## 8. Float Precision (Inherent — negligible on modern hardware)

**Problem**: The legacy x87 FPU on x86 uses 80-bit extended precision for
intermediate values, which can cause the last 1-2 ULP (unit in the last place)
to differ from WASM's strict IEEE 754 binary64 (64-bit) results.

**In practice this does not apply on modern hardware**: GCC and Clang on x86-64
default to SSE2 for floating-point math, which uses strict 64-bit precision
matching WASM exactly. Since virtually all modern laptops and servers are x86-64,
both backends produce identical float results out of the box.

**Edge cases where it could matter** (increasingly rare):
- Compiling C for x86-32 targets (legacy)
- Explicitly forcing x87 math via `-mfpmath=387`
- Some embedded/microcontroller targets

**Mitigation** (only needed for the edge cases above):
- Compile C with `-msse2 -mfpmath=sse`
- Use epsilon tolerance in cross-backend comparison (the test suite uses
  `epsilon = 0.001`)

---

## Priority Order

| # | Gap | Effort | Impact | Status |
|---|-----|--------|--------|--------|
| 1 | ~~`if` as expression~~ | Medium | High | **RESOLVED** |
| 2 | ~~`%c`, `%p`~~ | Low | Low | **RESOLVED** |
| 3 | ~~`%e`/`%E`/`%g`/`%G`~~ | Medium | Low | **RESOLVED** |
| 4 | ~~Width/precision~~ | High | Medium | **RESOLVED** |
| 5 | ~~Math functions~~ | — | — | **N/A** — not in ARIA IR |
| 6 | ~~Non-literal global init~~ | Low | Low | **RESOLVED** |
| 7 | Overflow | — | Inherent | Document, use `-fwrapv` |
| 8 | Float precision | — | Inherent | Negligible on x86-64 (SSE2 default) |
