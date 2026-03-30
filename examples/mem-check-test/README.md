# Memory Safety Checker Tests

Test programs that intentionally trigger memory management errors detected by
the ARIA checker. Each file contains exactly one class of memory fault.

Run all tests with:

```bash
for f in examples/mem-check-test/test_*.aria; do
  echo "--- $(basename $f) ---"
  ariac "$f" --check
  echo
done
```

## Test catalog

### Basic pointer safety

| File | Expected diagnostic | Description |
|------|-------------------|-------------|
| `test_use_after_free.aria` | `ERROR: use of freed pointer` | Dereferences a pointer after calling `free` on it |
| `test_double_free.aria` | `ERROR: double free of` | Calls `free` twice on the same pointer |
| `test_free_non_pointer.aria` | `ERROR: free of non-pointer variable` | Calls `free` on an `i32` variable |
| `test_null_deref.aria` | `ERROR: dereference of null/uninitialized pointer` | Dereferences a pointer initialized to literal `0` |
| `test_memory_leak.aria` | `WARN: potential memory leak` | Allocates memory and returns without freeing it |

### Alias and struct tracking

| File | Expected diagnostic | Description |
|------|-------------------|-------------|
| `test_alias_use_after_free.aria` | `ERROR: use of freed pointer` (on alias) | Creates an alias `%alias = %p`, frees `%p`, then dereferences `%alias` |
| `test_field_dangling.aria` | `ERROR: use of freed pointer via field` | Stores a pointer into a struct field, frees the original, then loads through the field |

### Cross-function inference (multi-round analysis)

These tests exercise the cross-function free inference system, which analyzes
callee bodies to determine which parameters they free — without any ownership
annotations in the source.

| File | Expected diagnostic | Description |
|------|-------------------|-------------|
| `test_crossfn_double_free.aria` | `ERROR: double free of` | Callee `$consume` frees its parameter; caller frees the same pointer again |
| `test_crossfn_use_after_free.aria` | `ERROR: use of freed pointer` | Callee `$release` frees its parameter; caller dereferences the pointer after the call |
| `test_crossfn_transitive.aria` | `ERROR: double free of` | `$outer_wrapper` delegates to `$inner_free` which frees the parameter; caller double-frees — tests transitive propagation through call chains |
| `test_crossfn_alias_free.aria` | `ERROR: double free of` | Callee creates a local alias of its parameter and frees the alias; caller double-frees — tests alias tracking within the callee scan |
| `test_crossfn_no_false_positive.aria` | No errors, no warnings | Callee has `mem` effect but only reads through the pointer (no free); caller correctly frees after the call — verifies absence of false positives |

### Bounds checking (static out-of-bounds detection)

These tests verify that the checker catches pointer arithmetic accesses that
exceed the known allocation size. Only works when both the allocation size and
the offset are compile-time constants.

| File | Expected diagnostic | Description |
|------|-------------------|-------------|
| `test_oob_literal.aria` | `ERROR: out-of-bounds access on` | Reads at offset 10 from an 8-element array |
| `test_oob_store.aria` | `ERROR: out-of-bounds access on` | Writes at offset 4 (== capacity) of a 4-element buffer |
| `test_oob_derived.aria` | `ERROR: out-of-bounds access on` | Derives a sub-pointer `%tail = %arr + 6` (remaining capacity 2), then reads at offset 2 — tests capacity propagation through pointer arithmetic |
| `test_oob_negative.aria` | `ERROR: negative offset on` | Accesses a pointer at literal offset -1 |
| `test_bounds_no_false_positive.aria` | No errors, no warnings | Accesses all valid indices 0..7 of an 8-element array — verifies absence of false positives |

### Return value safety

| File | Expected diagnostic | Description |
|------|-------------------|-------------|
| `test_return_freed.aria` | `ERROR: returning freed pointer` | Function allocates, frees, then returns the freed pointer |
| `test_return_null.aria` | `WARN: returning null literal from pointer-returning function` | Function with pointer return type returns literal `0` |

### Leak detection on reassignment

| File | Expected diagnostic | Description |
|------|-------------------|-------------|
| `test_leak_reassign.aria` | `WARN: reassigning owned pointer without free` | Reassigns a mutable pointer to a new allocation without freeing the old one |

### Unsafe casts

| File | Expected diagnostic | Description |
|------|-------------------|-------------|
| `test_unsafe_cast.aria` | `WARN: unsafe cast: integer to pointer` | Casts an `i32` value to `(ptr i32)` |

### Format string validation

| File | Expected diagnostic | Description |
|------|-------------------|-------------|
| `test_format_mismatch.aria` | `ERROR: print: argument count does not match format specifiers` | Format string expects 3 arguments but only 2 are provided |

### Conditional free precision (path-sensitive)

| File | Expected diagnostic | Description |
|------|-------------------|-------------|
| `test_conditional_free.aria` | `WARN: use of potentially freed pointer` | Pointer is freed in only one branch of an `if`; subsequent use gets a warning (state=4, may-be-freed) instead of a false error |

### Uninitialized memory detection

| File | Expected diagnostic | Description |
|------|-------------------|-------------|
| `test_uninit_read.aria` | `WARN: read of potentially uninitialized memory at offset on` | Reads from an allocated array offset that was never written to |

### Loop leak detection

| File | Expected diagnostic | Description |
|------|-------------------|-------------|
| `test_leak_loop.aria` | `WARN: allocation in loop body without free (leak per iteration)` | Allocates inside a loop without freeing — leaks every iteration |

### Constant propagation

| File | Expected diagnostic | Description |
|------|-------------------|-------------|
| `test_const_prop_oob.aria` | `ERROR: out-of-bounds access on` | Alloc size comes from a constant variable; offset exceeds propagated capacity |

### Alloc size overflow

| File | Expected diagnostic | Description |
|------|-------------------|-------------|
| `test_alloc_overflow.aria` | `WARN: alloc size uses multiplication (risk of integer overflow)` | Alloc count uses multiplication which could overflow i32 |

### Format string type validation

| File | Expected diagnostic | Description |
|------|-------------------|-------------|
| `test_format_type.aria` | `WARN: format '%d' expects integer, got pointer` | Passes a pointer argument to a `%d` format specifier |

### Cross-function conditional free

| File | Expected diagnostic | Description |
|------|-------------------|-------------|
| `test_crossfn_conditional.aria` | `WARN: use of potentially freed pointer` | Callee conditionally frees parameter; caller gets may-be-freed warning, not definitive error |

### Global pointer state tracking

| File | Expected diagnostic | Description |
|------|-------------------|-------------|
| `test_global_use_after_free.aria` | `ERROR: use of freed global pointer` | Function A allocates global, function B frees it, function C uses it — cross-function global use-after-free |
