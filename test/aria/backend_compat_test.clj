(ns aria.backend-compat-test
  "Integration tests for WAT backend parity with C backend.
   Covers: struct field offsets, globals, format specifiers,
   alloc/free, float edge cases, and integer overflow."
  (:require [clojure.test :refer [deftest is testing]]
            [aria.parser :as parser]
            [aria.codegen-wat :as codegen-wat]
            [clojure.string :as str]))

;; ── Helper ───────────────────────────────────────────────────

(defn- gen-wat [source]
  (let [module (parser/parse source)]
    (codegen-wat/generate module)))

(defn- gen-wat-wasi [source]
  (let [module (parser/parse source)]
    (codegen-wat/generate module {:wasi? true})))

;; ── Phase 1: Struct Field Offsets ────────────────────────────

(deftest struct-field-offset-test
  (testing "struct with 3 fields uses correct offsets"
    (let [wat (gen-wat "(module \"t\"
                          (type $Point (struct (x i32) (y i32) (z i32)))
                          (func $f (param $p (ptr $Point)) (result i32)
                            (effects mem)
                            (store.field $p \"x\" 10)
                            (store.field $p \"y\" 20)
                            (store.field $p \"z\" 30)
                            (return (load.field $p \"z\")))
                          (export $f))")]
      ;; x at offset 0, y at offset 4, z at offset 8
      (is (str/includes? wat "i32.store offset=0") "x should be at offset 0")
      (is (str/includes? wat "i32.store offset=4") "y should be at offset 4")
      (is (str/includes? wat "i32.store offset=8") "z should be at offset 8")
      (is (str/includes? wat "i32.load offset=8") "load z should use offset 8"))))

(deftest struct-mixed-types-test
  (testing "struct with mixed-size fields has aligned offsets"
    (let [wat (gen-wat "(module \"t\"
                          (type $Record (struct (flag i8) (count i32) (value f64)))
                          (func $f (param $r (ptr $Record)) (result f64)
                            (effects mem)
                            (store.field $r \"flag\" 1)
                            (store.field $r \"count\" 42)
                            (return (load.field $r \"value\")))
                          (export $f))")]
      ;; flag at 0 (1 byte), count at 4 (aligned to 4), value at 8 (aligned to 4, capped)
      (is (str/includes? wat "i32.store offset=0") "flag at offset 0")
      (is (str/includes? wat "i32.store offset=4") "count at offset 4")
      (is (str/includes? wat "f64.load offset=8") "value at offset 8"))))

;; ── Phase 2: Global Variables ────────────────────────────────

(deftest global-variable-test
  (testing "mutable global emits global.get/global.set"
    (let [wat (gen-wat "(module \"t\"
                          (global mut $counter i32 0)
                          (func $inc (effects mem)
                            (set $counter (add.i32 $counter 1)))
                          (func $get_counter (result i32) (effects mem)
                            (return $counter))
                          (export $inc)
                          (export $get_counter))")]
      (is (str/includes? wat "(global $counter (mut i32) (i32.const 0))"))
      (is (str/includes? wat "global.get $counter"))
      (is (str/includes? wat "global.set $counter")))))

(deftest immutable-global-test
  (testing "immutable global emits correct declaration"
    (let [wat (gen-wat "(module \"t\"
                          (global $MAX_SIZE i32 100)
                          (func $f (result i32) (effects pure)
                            (return $MAX_SIZE))
                          (export $f))")]
      (is (str/includes? wat "(global $MAX_SIZE i32 (i32.const 100))"))
      (is (str/includes? wat "global.get $MAX_SIZE")))))

;; ── Phase 3: Format Specifiers ───────────────────────────────

(deftest format-specifiers-test
  (testing "%u emits unsigned print call"
    (let [wat (gen-wat-wasi "(module \"t\"
                               (func $main (effects io)
                                 (print \"%u\\n\" 42))
                               (export $main))")]
      (is (str/includes? wat "call $__print_u32"))))

  (testing "%x emits lowercase hex print call"
    (let [wat (gen-wat-wasi "(module \"t\"
                               (func $main (effects io)
                                 (print \"%x\\n\" 255))
                               (export $main))")]
      (is (str/includes? wat "call $__print_hex"))))

  (testing "%X emits uppercase hex print call"
    (let [wat (gen-wat-wasi "(module \"t\"
                               (func $main (effects io)
                                 (print \"%X\\n\" 255))
                               (export $main))")]
      (is (str/includes? wat "call $__print_HEX"))))

  (testing "%o emits octal print call"
    (let [wat (gen-wat-wasi "(module \"t\"
                               (func $main (effects io)
                                 (print \"%o\\n\" 8))
                               (export $main))")]
      (is (str/includes? wat "call $__print_oct"))))

  (testing "mixed specifiers"
    (let [wat (gen-wat-wasi "(module \"t\"
                               (func $main (effects io)
                                 (print \"%d %u %x\\n\" 42 42 255))
                               (export $main))")]
      (is (str/includes? wat "call $__print_i32"))
      (is (str/includes? wat "call $__print_u32"))
      (is (str/includes? wat "call $__print_hex")))))

;; ── Phase 4: Free-List Allocator ─────────────────────────────

(deftest free-list-allocator-test
  (testing "free-list allocator emits free_list global"
    (let [wat (gen-wat "(module \"t\"
                          (func $f (effects mem)
                            (let %p (ptr i32) (alloc i32 1))
                            (free %p))
                          (export $f))")]
      (is (str/includes? wat "(global $__free_list (mut i32) (i32.const 0))")
          "free-list global should be emitted")
      (is (str/includes? wat "(func $__alloc")
          "alloc function should exist")
      ;; Free should set free list
      (is (str/includes? wat "global.set $__free_list")
          "free should update free list"))))

(deftest bump-allocator-option-test
  (testing "bump allocator can be selected via option"
    (let [module (parser/parse "(module \"t\"
                                  (func $f (effects mem)
                                    (let %p (ptr i32) (alloc i32 1)))
                                  (export $f))")
          wat (codegen-wat/generate module {:allocator :bump})]
      (is (not (str/includes? wat "$__free_list"))
          "bump allocator should not have free list")
      (is (str/includes? wat "(func $__alloc")))))

;; ── Phase 5: Float Edge Cases ────────────────────────────────

(deftest float-nan-infinity-test
  (testing "f64 print function handles NaN and Infinity"
    (let [wat (gen-wat-wasi "(module \"t\"
                               (func $main (effects io)
                                 (print \"%f\\n\" 3.14))
                               (export $main))")]
      ;; The $__print_f64 function should contain NaN/Infinity checks
      (is (str/includes? wat "NaN check") "should have NaN check comment")
      (is (str/includes? wat "f64.ne") "NaN uses f64.ne (val != val)")
      (is (str/includes? wat "f64.const inf") "should check for infinity")
      (is (str/includes? wat "f64.const -inf") "should check for -infinity")
      (is (str/includes? wat "Overflow guard") "should have overflow guard"))))

;; ── If as Expression ─────────────────────────────────────────

(deftest if-expression-test
  (testing "if in expression position emits block-typed if with result"
    (let [wat (gen-wat "(module \"t\"
                          (func $max (param $a i32) (param $b i32) (result i32)
                            (effects pure)
                            (return (if (gt.i32 $a $b)
                                     (then $a)
                                     (else $b))))
                          (export $max))")]
      (is (str/includes? wat "if (result i32)") "if-expr should have (result i32)")
      (is (str/includes? wat "local.get $a"))
      (is (str/includes? wat "local.get $b"))
      (is (str/includes? wat "end")))))

(deftest if-expression-with-let-test
  (testing "if as expression used in let binding"
    (let [wat (gen-wat "(module \"t\"
                          (func $f (param $x i32) (result i32)
                            (effects pure)
                            (let %y i32 (if (gt.i32 $x 0)
                                          (then $x)
                                          (else 0)))
                            (return %y))
                          (export $f))")]
      (is (str/includes? wat "if (result i32)") "let with if-expr should use typed if")
      (is (str/includes? wat "local.set $y")))))

;; ── Phase 6: Integer Overflow ────────────────────────────────

(deftest integer-overflow-wrapping-test
  (testing "i32 add emits plain i32.add (wrapping in WASM)"
    (let [wat (gen-wat "(module \"t\"
                          (func $f (result i32) (effects pure)
                            (return (add.i32 2147483647 1)))
                          (export $f))")]
      ;; WASM wraps: MAX_INT + 1 = MIN_INT
      (is (str/includes? wat "i32.const 2147483647"))
      (is (str/includes? wat "i32.add")))))

;; ── Print Type Auto-Detection ─────────────────────────────────

(deftest print-autodetect-i64-test
  (testing "%d with i64 variable auto-promotes to $__print_i64"
    (let [wat (gen-wat-wasi "(module \"t\"
                               (func $main (effects io)
                                 (let %x i64 42)
                                 (print \"%d\\n\" %x))
                               (export $main))")]
      (is (str/includes? wat "call $__print_i64")
          "%d + i64 should auto-promote to print_i64")
      (is (not (str/includes? wat "call $__print_i32"))
          "should NOT call print_i32 for i64 arg"))))

(deftest print-autodetect-i32-no-change-test
  (testing "%d with i32 variable stays as $__print_i32"
    (let [wat (gen-wat-wasi "(module \"t\"
                               (func $main (effects io)
                                 (let %x i32 42)
                                 (print \"%d\\n\" %x))
                               (export $main))")]
      (is (str/includes? wat "call $__print_i32")
          "%d + i32 should use print_i32"))))

(deftest print-autodetect-call-result-test
  (testing "%d with function call returning i64 auto-promotes"
    (let [wat (gen-wat-wasi "(module \"t\"
                               (func $get_big (result i64) (effects pure)
                                 (return (cast i32 i64 100)))
                               (func $main (effects io)
                                 (print \"%d\\n\" (call $get_big)))
                               (export $main))")]
      (is (str/includes? wat "call $__print_i64")
          "%d + call returning i64 should auto-promote"))))

(deftest print-explicit-ld-still-works-test
  (testing "%ld with i64 still works correctly"
    (let [wat (gen-wat-wasi "(module \"t\"
                               (func $main (effects io)
                                 (let %x i64 42)
                                 (print \"%ld\\n\" %x))
                               (export $main))")]
      (is (str/includes? wat "call $__print_i64")
          "%ld should still work with i64"))))

;; ── Full WASI Integration ────────────────────────────────────

(deftest wasi-fibonacci-with-print-test
  (testing "fibonacci.aria generates valid WASI WAT"
    (let [wat (codegen-wat/generate
                (parser/parse-file "examples/fibonacci.aria")
                {:wasi? true})]
      (is (str/includes? wat "wasi_snapshot_preview1"))
      (is (str/includes? wat "fd_write"))
      (is (str/includes? wat "(export \"_start\"")))))

(deftest all-examples-with-wasi-test
  (testing "all standard examples generate WASI WAT without error"
    (doseq [f ["examples/fibonacci.aria"
               "examples/bubble_sort.aria"
               "examples/math_demo.aria"]]
      (let [module (parser/parse-file f)
            wat (codegen-wat/generate module {:wasi? true})]
        (is (str/includes? wat "(module") (str f " should generate valid module"))))))
