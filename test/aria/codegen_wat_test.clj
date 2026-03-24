(ns aria.codegen-wat-test
  (:require [clojure.test :refer [deftest is testing]]
            [aria.parser :as parser]
            [aria.checker :as checker]
            [aria.codegen-wat :as codegen-wat]
            [clojure.string :as str]))

;; ── Helper ───────────────────────────────────────────────────

(defn- gen-wat [source]
  (let [module (parser/parse source)]
    (codegen-wat/generate module)))

(defn- gen-wat-file [path]
  (let [module (parser/parse-file path)]
    (codegen-wat/generate module)))

;; ── Type Mapping Tests ───────────────────────────────────────

(deftest type-mapping-test
  (testing "primitive types map correctly in generated WAT"
    (let [wat (gen-wat "(module \"t\"
                          (func $f (param $a i32) (param $b i64) (param $c f64) (result i32)
                            (effects pure) (return $a))
                          (export $f))")]
      (is (str/includes? wat "(param $a i32)"))
      (is (str/includes? wat "(param $b i64)"))
      (is (str/includes? wat "(param $c f64)"))
      (is (str/includes? wat "(result i32)"))))

  (testing "pointer types map to i32"
    (let [wat (gen-wat "(module \"t\"
                          (func $f (param $p (ptr i32)) (result i32)
                            (effects mem) (return (load.i32 $p)))
                          (export $f))")]
      (is (str/includes? wat "(param $p i32)")))))

;; ── Expression Tests ─────────────────────────────────────────

(deftest int-literal-test
  (testing "integer literals emit i32.const"
    (let [wat (gen-wat "(module \"t\" (func $f (result i32) (effects pure) (return 42)) (export $f))")]
      (is (str/includes? wat "i32.const 42")))))

(deftest bool-literal-test
  (testing "boolean literals emit i32.const 0/1"
    (let [wat (gen-wat "(module \"t\" (func $f (result i32) (effects pure) (return true)) (export $f))")]
      (is (str/includes? wat "i32.const 1")))))

(deftest bin-op-test
  (testing "binary operations emit correct WAT instructions"
    (let [wat (gen-wat "(module \"t\"
                          (func $f (param $a i32) (param $b i32) (result i32)
                            (effects pure) (return (add.i32 $a $b)))
                          (export $f))")]
      (is (str/includes? wat "local.get $a"))
      (is (str/includes? wat "local.get $b"))
      (is (str/includes? wat "i32.add")))))

(deftest comparison-test
  (testing "signed comparisons use _s suffix"
    (let [wat (gen-wat "(module \"t\"
                          (func $f (param $n i32) (result i32)
                            (effects pure)
                            (if (le.i32 $n 1)
                              (then (return $n))
                              (else (return 0))))
                          (export $f))")]
      (is (str/includes? wat "i32.le_s")))))

(deftest cast-test
  (testing "i32 to i64 cast emits extend"
    (let [wat (gen-wat "(module \"t\"
                          (func $f (param $x i32) (result i64)
                            (effects pure) (return (cast i32 i64 $x)))
                          (export $f))")]
      (is (str/includes? wat "i64.extend_i32_s")))))

;; ── Statement Tests ──────────────────────────────────────────

(deftest let-binding-test
  (testing "let bindings emit local.set"
    (let [wat (gen-wat "(module \"t\"
                          (func $f (result i32) (effects pure)
                            (let %x i32 42)
                            (return %x))
                          (export $f))")]
      (is (str/includes? wat "(local $x i32)"))
      (is (str/includes? wat "i32.const 42"))
      (is (str/includes? wat "local.set $x"))
      (is (str/includes? wat "local.get $x")))))

(deftest loop-test
  (testing "loops emit block+loop pattern"
    (let [wat (gen-wat "(module \"t\"
                          (func $f (result i32) (effects pure div)
                            (local mut $i i32 0)
                            (loop $L
                              (if (ge.i32 $i 10)
                                (then (br $L))
                                (else (set $i (add.i32 $i 1)))))
                            (return $i))
                          (export $f))")]
      (is (str/includes? wat "block $break_L"))
      (is (str/includes? wat "loop $L"))
      ;; br inside if/then, not br_if (ARIA uses (if cond (then (br $L))) pattern)
      (is (str/includes? wat "br $break_L"))
      (is (str/includes? wat "br $L")))))

(deftest return-test
  (testing "return emits return instruction"
    (let [wat (gen-wat "(module \"t\" (func $f (result i32) (effects pure) (return 0)) (export $f))")]
      (is (str/includes? wat "i32.const 0"))
      (is (str/includes? wat "return")))))

;; ── Memory Tests ─────────────────────────────────────────────

(deftest alloc-test
  (testing "alloc emits __alloc call and built-in function"
    (let [wat (gen-wat "(module \"t\"
                          (func $f (result i32) (effects mem)
                            (let %p (ptr i32) (alloc i32 8))
                            (return 0))
                          (export $f))")]
      (is (str/includes? wat "call $__alloc"))
      (is (str/includes? wat "(func $__alloc"))
      (is (str/includes? wat "global.get $__stack_ptr"))
      (is (str/includes? wat "(global $__stack_ptr (mut i32)")))))

(deftest store-load-test
  (testing "store and load emit correct instructions"
    (let [wat (gen-wat "(module \"t\"
                          (func $f (param $p (ptr i32)) (effects mem)
                            (store.i32 $p 42))
                          (export $f))")]
      (is (str/includes? wat "i32.store")))))

;; ── Print Tests ──────────────────────────────────────────────

(deftest print-test
  (testing "print emits imports and calls"
    (let [wat (gen-wat "(module \"t\"
                          (func $f (effects io)
                            (print \"hello %d\\n\" 42))
                          (export $f))")]
      (is (str/includes? wat "(import \"env\" \"print\""))
      (is (str/includes? wat "(import \"env\" \"print_i32\""))
      (is (str/includes? wat "call $__print"))
      (is (str/includes? wat "call $__print_i32"))
      (is (str/includes? wat "(data (i32.const")))))

;; ── Extern Tests ─────────────────────────────────────────────

(deftest extern-test
  (testing "extern declarations emit WASM imports"
    (let [wat (gen-wat "(module \"t\"
                          (extern \"env\" $log (param $ptr i32) (param $len i32))
                          (func $f (effects io)
                            (call $log 0 5))
                          (export $f))")]
      (is (str/includes? wat "(import \"env\" \"log\""))
      (is (str/includes? wat "call $log")))))

(deftest extern-with-result-test
  (testing "extern with result type"
    (let [wat (gen-wat "(module \"t\"
                          (extern \"env\" $get_time (result i64))
                          (func $f (result i64) (effects io)
                            (return (call $get_time)))
                          (export $f))")]
      (is (str/includes? wat "(import \"env\" \"get_time\""))
      (is (str/includes? wat "(result i64)")))))

;; ── Full Example Tests ───────────────────────────────────────

(deftest fibonacci-wat-test
  (testing "fibonacci.aria generates valid WAT"
    (let [wat (gen-wat-file "examples/fibonacci.aria")]
      (is (str/includes? wat "(module"))
      (is (str/includes? wat "(func $fibonacci"))
      (is (str/includes? wat "(param $n i32)"))
      (is (str/includes? wat "(result i32)"))
      (is (str/includes? wat "i32.le_s"))
      (is (str/includes? wat "call $fibonacci"))
      (is (str/includes? wat "i32.sub"))
      (is (str/includes? wat "i32.add"))
      (is (str/includes? wat "(export \"fibonacci\""))
      (is (str/includes? wat "(export \"main\"")))))

(deftest bubble-sort-wat-test
  (testing "bubble_sort.aria generates valid WAT with memory ops"
    (let [wat (gen-wat-file "examples/bubble_sort.aria")]
      (is (str/includes? wat "(module"))
      (is (str/includes? wat "(func $bubble_sort"))
      (is (str/includes? wat "call $__alloc"))
      (is (str/includes? wat "i32.store"))
      (is (str/includes? wat "i32.load"))
      (is (str/includes? wat "block $break_"))
      (is (str/includes? wat "loop $"))
      (is (str/includes? wat "(global $__stack_ptr")))))

(deftest math-demo-wat-test
  (testing "math_demo.aria generates valid WAT with casts"
    (let [wat (gen-wat-file "examples/math_demo.aria")]
      (is (str/includes? wat "(module"))
      (is (str/includes? wat "(func $gcd"))
      (is (str/includes? wat "(func $factorial"))
      (is (str/includes? wat "(func $is_prime"))
      (is (str/includes? wat "(func $power"))
      (is (str/includes? wat "i64.extend_i32_s"))
      (is (str/includes? wat "i32.rem_s")))))

(deftest all-examples-typecheck-and-generate-test
  (testing "all examples parse, typecheck, and generate WAT"
    (doseq [f ["examples/fibonacci.aria"
               "examples/bubble_sort.aria"
               "examples/math_demo.aria"]]
      (let [module (parser/parse-file f)
            result (checker/check module)]
        (is (:ok? result) (str f " should type-check: " (:errors result)))
        (let [wat (codegen-wat/generate module)]
          (is (str/starts-with? wat ";; Generated from ARIA module:")
              (str f " should generate WAT header"))
          (is (str/includes? wat "(module")
              (str f " should contain (module")))))))

(deftest module-structure-test
  (testing "WAT module has correct structure"
    (let [wat (gen-wat "(module \"t\"
                          (func $f (result i32) (effects pure) (return 0))
                          (export $f))")]
      ;; Should have module wrapper
      (is (str/starts-with? wat ";; Generated from ARIA module: t"))
      (is (str/includes? wat "(module"))
      ;; Should have memory export
      (is (str/includes? wat "(memory (export \"memory\") 1)"))
      ;; Should close properly
      (is (str/ends-with? (str/trim wat) ")")))))
