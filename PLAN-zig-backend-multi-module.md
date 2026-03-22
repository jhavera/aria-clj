# Plan: Zig Backend + Multi-Module Support for aria-clj

## Context

ARIA-IR currently compiles to C99 as a single monolithic file. This limits the system to relatively small programs. This plan adds: (1) a Zig code generation backend as an alternative to C, and (2) multi-module support with imports/exports for large projects that translate into multiple files calling each other. Zig is an ideal target because its native `@import`, absence of headers, and integrated build system make multi-module codegen much cleaner than C.

Phases 1 and 2 are independent and can be developed in parallel. Phase 3 requires both. Phase 4 is polish.

---

## Phase 1: Single-Module Zig Backend

**Goal:** Create `codegen_zig.clj` as a drop-in replacement for `codegen_c.clj`.

### Create: `src/aria/codegen_zig.clj`

Same structure as `codegen_c.clj` (atoms-based state: `make-codegen`, `emit!`, `indent-str`). Key functions:

- **`type->zig`** — Near-identity mapping: `"i32"→"i32"`, `"f64"→"f64"`, `"bool"→"bool"`. Pointers: `(ptr T)→"*T"`. Arrays: `(array T N)→"[N]T"`. No `stdint.h` needed.
- **`var->zig`** — Same as `var->c`: strip `$`/`%`, replace `.`→`_`, `-`→`_`.
- **`expr->zig`** — Key translations vs C:
  - `:alloc` → `allocator.alloc(T, count) catch unreachable` (use `std.heap.page_allocator`)
  - `:load` → `ptr.*` (instead of `(*ptr)`)
  - `:load-field` → `ptr.field_name` (instead of `ptr->field`)
  - `:cast` → `@intCast`, `@floatCast`, `@as` depending on type pair
  - `:if` expr → `if (cond) expr else expr` (Zig supports this natively)
- **`gen-stmt!`** — Key differences:
  - `:let` → `const name: Type = val;` (immutable) / `var name: Type = val;` (mutable). Perfect mapping to `$var`/`%var`.
  - `:store` → `ptr.* = val;`
  - `:free` → `allocator.free(ptr);`
  - `:print` → `std.io.getStdOut().writer().print(fmt, .{args}) catch {};` with C→Zig format conversion (`%d`→`{d}`, `%f`→`{d:.6}`, `%s`→`{s}`)
  - `:loop` → `while (true) { ... }` with labeled breaks: `label: while (true) { ... break :label; }`
- **`gen-struct!`** → `const Name = struct { field: Type, ... };` (no typedef needed)
- **`gen-function!`** → `fn name(p: T) RetType { ... }`, with `pub` for exported functions. No forward declarations.
- **`generate`** — Top-level:
  - `const std = @import("std");`
  - `const allocator = std.heap.page_allocator;` (if `mem` effects are present)
  - Structs, globals, functions
  - Main wrapper: generate `$main` as `aria_main` + `pub fn main() void` that calls `aria_main` and uses `std.process.exit`

**Helper `fmt-c-to-zig`**: converts C format strings to Zig (`%d`→`{d}`, escape `{}`→`{{}}`).

### Modify: `src/aria/main.clj`

- Add `--emit-zig` and `--backend zig` flags in `parse-args`
- Add `compile-zig!`: runs `zig build-exe <file.zig> -o <output>`
- In `process-file`, branch based on selected backend

### Create: `test/aria/codegen_zig_test.clj`

- Tests for `type->zig` across all types
- Tests for `generate` with all 3 existing examples
- Tests for `fmt-c-to-zig`
- Tests for `var`/`const` generation from `$var`/`%var`

### Verification

```bash
clojure -M:run examples/fibonacci.aria --emit-zig > /tmp/fib.zig
zig build-exe /tmp/fib.zig -o /tmp/fib && /tmp/fib
# Compare output with C version
```

### Anticipated Challenges

- **Pointer arithmetic**: `(add.i32 $arr $j)` in ARIA is pointer arithmetic. In Zig it becomes `arr[j]` or `arr + j`. Requires detecting when `add` operates on pointers vs integers — may need type info from checker in codegen.
- **Cast builtins**: Zig has `@intCast`, `@floatCast`, `@intFromFloat` etc. Requires logic based on source/target type pair.

---

## Phase 2: Multi-Module IR Support with Package Hierarchy

**Goal:** Extend ARIA-IR with `(import ...)` and hierarchical directory-based packages, update parser/AST/checker.

### Package Convention

Module names use `/` as separator, mirroring directory structure. The module name in the `(module ...)` form must match its file path relative to the project source root:

```
src/
├── main.aria                → (module "main" ...)
├── math/
│   ├── algebra.aria         → (module "math/algebra" ...)
│   └── stats.aria           → (module "math/stats" ...)
├── io/
│   └── csv.aria             → (module "io/csv" ...)
└── utils/
    └── strings.aria         → (module "utils/strings" ...)
```

### New Syntax

```lisp
(module "main"
  (import "math/algebra" $gcd $factorial)
  (import "io/csv" $read_csv)
  (func $main ...)
  (export $main))
```

Wildcard import for convenience:

```lisp
(import "math/algebra" *)    ;; imports all exported symbols
```

### Modify: `src/aria/ast.clj`

- Add `import-node [module-path symbols]` → `{:node/type :import :module-path module-path :symbols symbols}`
  - `:module-path` is the full hierarchical name (e.g. `"math/algebra"`)
  - `:symbols` is a vector of symbol names, or `[:all]` for wildcard
- Extend `module` with:
  - `:imports` field (default `[]` for backward compat)
  - `:module-path` field — the full hierarchical name (e.g. `"math/algebra"`)

### Modify: `src/aria/parser.clj`

- Add `parse-import` that parses `(import "math/algebra" $sym1 $sym2 ...)` and `(import "math/algebra" *)`
- Update `parse-module` to handle `"import"` in the dispatch
- Add `discover-modules [source-root]` — recursively scans a directory for `.aria` files, parses each, returns `{module-path -> module-ast}`. Module path is derived from the file's relative path (e.g. `src/math/algebra.aria` → `"math/algebra"`)
- Add `parse-file-set [paths]` → `{module-path -> module-ast}` (for explicit file lists)
- Validate that the module name declared in `(module "name" ...)` matches the file path

### New: `src/aria/module_graph.clj`

Separate namespace for dependency resolution (keeps checker focused on types):

- `build-dep-graph [module-map]` → `{module-path -> #{dep-path ...}}`
- `topo-sort [graph]` → ordered list or throws with cycle description
- `detect-cycles [graph]` → `nil` or vector describing the cycle path
- `resolve-module-file [source-root module-path]` → absolute file path (e.g. `"math/algebra"` → `src/math/algebra.aria`)

### Modify: `src/aria/checker.clj`

- Add `check-module-set [module-map dep-order]`:
  - Takes pre-sorted module list from `module_graph/topo-sort`
  - Checks modules in dependency order
  - After checking each module, extracts exported signatures into a shared registry `{module-path -> {symbol -> signature}}`
- Extend `make-checker` with `external-symbols` map: `{symbol -> {:module-path path :signature sig}}`
- Before checking a module, populate external-symbols from its imports by looking up the registry
- In `:call` resolution, if symbol not found locally, look up in external-symbols
- Validate that imported symbols exist in the target module's exports
- For wildcard imports (`*`), import all exported symbols from the target module
- Error on importing from a module that doesn't exist in the module set

### Modify: `src/aria/main.clj`

- Add `--source-root dir` flag (defaults to current directory)
- When `--source-root` is given, use `discover-modules` to find all `.aria` files recursively
- Alternative: support multiple input files explicitly: `clojure -M:run a.aria math/algebra.aria ...`
- When multiple modules are found, use `module_graph/topo-sort` + `checker/check-module-set`
- Single file without imports: behavior unchanged (backward compat)

### Create: `examples/multi_module/`

Hierarchical example project:

```
examples/multi_module/
├── main.aria                 → imports math/algebra, utils/format
├── math/
│   ├── algebra.aria          → exports $gcd, $factorial (pure)
│   └── common.aria           → exports $abs, $max (used by algebra)
└── utils/
    └── format.aria           → exports $print_result (io effect)
```

This tests: nested packages, transitive dependencies (`main` → `math/algebra` → `math/common`), and cross-package imports.

### Verification

- Test: parsing module with `(import "math/algebra" $gcd)` produces correct node with hierarchical path
- Test: `discover-modules` finds all `.aria` files and builds correct module-path map
- Test: `topo-sort` orders `math/common` before `math/algebra` before `main`
- Test: checker accepts valid cross-package imports
- Test: checker rejects import of unexported symbol
- Test: checker detects cycles A→B→A
- Test: checker validates module name matches file path
- Test: wildcard import `(import "math/algebra" *)` imports all exports
- Test: all existing single-module tests still pass

---

## Phase 3: Multi-File Zig Codegen with Directory Structure

**Goal:** Generate multiple `.zig` files preserving the package hierarchy, plus `build.zig`, for multi-module projects.

### Output Structure

The generated Zig project mirrors the ARIA package hierarchy:

```
build/                          (output root)
├── build.zig                   (generated build script)
└── src/
    ├── main.zig
    ├── math/
    │   ├── algebra.zig
    │   └── common.zig
    └── utils/
        └── format.zig
```

### Modify: `src/aria/codegen_zig.clj`

- **`generate-module [module imports-map exports-set]`** — generates a single `.zig` file:
  - For each import, emit `@import` with the relative path between modules:
    - `main.zig` importing `math/algebra` → `const algebra = @import("math/algebra.zig");`
    - `math/algebra.zig` importing `math/common` → `const common = @import("common.zig");` (same package, relative path)
  - Cross-module calls: `algebra.gcd(a, b)` — use the last segment of the module path as the binding name
  - `pub fn` for exported functions, `fn` for the rest
- **`module-import-binding [module-path]`** — derives the Zig const name from a module path: `"math/algebra"` → `algebra`, `"utils/format"` → `format`. Handle collisions if two imports have the same leaf name.
- **`relative-import-path [from-module-path to-module-path]`** — computes the relative `.zig` path between two modules. Examples:
  - from `"main"` to `"math/algebra"` → `"math/algebra.zig"`
  - from `"math/algebra"` to `"math/common"` → `"common.zig"`
  - from `"math/algebra"` to `"utils/format"` → `"../utils/format.zig"`
- **`generate-multi [module-map dep-graph]`** → `{"src/main.zig" "...", "src/math/algebra.zig" "...", "build.zig" "..."}`
  - Keys are output file paths relative to the build root
  - Creates subdirectories as needed
- **`generate-build-zig [project-name main-module-path]`** — generates `build.zig` pointing to the root source file. Zig resolves `@import` transitively, so `build.zig` only needs to know the entry point:
  ```zig
  const std = @import("std");
  pub fn build(b: *std.Build) void {
      const exe = b.addExecutable(.{
          .name = "project_name",
          .root_source_file = b.path("src/main.zig"),
      });
      b.installArtifact(exe);
      const run_cmd = b.addRunArtifact(exe);
      const run_step = b.step("run", "Run the application");
      run_step.dependOn(&run_cmd.step);
  }
  ```
- Extend `make-codegen` with `:imports` map `{symbol → {:module-path path :binding zig-const-name}}` so `expr->zig` can prefix cross-module calls correctly

### Modify: `src/aria/main.clj`

- When `--backend zig` and multiple modules: create output directory tree, write each `.zig` in its subdirectory, write `build.zig`, run `zig build`
- `-o dir/` flag sets the output root directory
- `--run` in multi-module mode: `zig build run` after generating

### (Optional) Modify: `src/aria/codegen_c.clj`

- Add `generate-header` for `.h` files with forward declarations of exports
- Add `#include` with relative paths for imports
- Mirror the directory structure in output
- Lower priority than the Zig path

### Verification

```bash
clojure -M:run --source-root examples/multi_module --backend zig -o /tmp/build
cd /tmp/build && zig build run
```

- Verify directory structure: `src/math/algebra.zig` exists, `src/utils/format.zig` exists
- Verify `src/math/algebra.zig` contains `pub fn gcd(...)` and `const common = @import("common.zig");`
- Verify `src/main.zig` contains `const algebra = @import("math/algebra.zig");`
- Verify `build.zig` points to `src/main.zig` as root source
- Verify relative import paths are correct (same-package vs cross-package)
- Verify `zig build` compiles without errors
- Single-module still works unchanged (flat output, no `build.zig` needed)

---

## Phase 4: Update LLM Generator and Tests

**Goal:** Update `gen.clj` to support multi-module and Zig backend.

### Modify: `src/aria/gen.clj`

- Update `condensed-spec` with `(import ...)` syntax
- Add `--backend zig` to the generator CLI
- Add `--multi-module` flag for LLM to generate multiple modules

### Create/Update Tests

- `test/aria/codegen_zig_test.clj` — comprehensive Zig codegen tests
- `test/aria/module_graph_test.clj` — topological sort, cycles, diamond deps
- Update `test/aria/parser_test.clj` with import tests

### End-to-End Verification

```bash
# Single module Zig
clojure -M:gen --backend zig --run "compute fibonacci numbers"

# Multi-module
clojure -M:gen --multi-module --backend zig --run "create a math library and a main program that uses it"
```

---

## Critical Files

| File | Phases |
|---|---|
| `src/aria/codegen_c.clj` | Template for Phase 1, optional Phase 3 |
| `src/aria/codegen_zig.clj` (new) | Phase 1, 3 |
| `src/aria/ast.clj` | Phase 2 |
| `src/aria/parser.clj` | Phase 2 |
| `src/aria/module_graph.clj` (new) | Phase 2, 3 |
| `src/aria/checker.clj` | Phase 2 |
| `src/aria/main.clj` | Phase 1, 2, 3 |
| `src/aria/gen.clj` | Phase 4 |
