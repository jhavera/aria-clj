(ns aria.comptime-test
  "Tests for comptime: top-level, expression-level, comptime-val, generics."
  (:require [clojure.test :refer [deftest is testing]]
            [aria.reader :as reader]
            [aria.parser :as parser]
            [aria.checker :as checker]
            [aria.codegen-c :as codegen-c]
            [aria.main :as main]
            [clojure.java.shell :as shell]
            [clojure.string :as str]))

;; ── Tool Availability ────────────────────────────────────────

(defn- tool-available? [tool]
  (try
    (let [result (shell/sh "which" tool)]
      (zero? (:exit result)))
    (catch Exception _ false)))

(def ^:private gcc? (delay (tool-available? "gcc")))

;; ── Helpers ──────────────────────────────────────────────────

(defn- compile-and-run-aria
  "Full pipeline: source → expand-all → parse → check → codegen C → gcc → run.
   Returns stdout string."
  [source]
  (let [raw-form (reader/read-aria source)
        expanded (main/expand-all raw-form)
        module (parser/parse-module expanded)
        check-result (checker/check module)]
    (when-not (:ok? check-result)
      (throw (ex-info "Type-check failed" {:errors (:errors check-result)})))
    (let [c-source (codegen-c/generate module)
          tmp-c (java.io.File/createTempFile "aria_comptime_test" ".c")
          tmp-bin (str (.getAbsolutePath tmp-c) ".out")]
      (try
        (spit tmp-c c-source)
        (let [compile (shell/sh "gcc" "-fwrapv" "-o" tmp-bin
                                (.getAbsolutePath tmp-c) "-lm")]
          (when-not (zero? (:exit compile))
            (throw (ex-info "gcc failed" {:err (:err compile)})))
          (let [result (shell/sh tmp-bin)]
            (:out result)))
        (finally
          (.delete tmp-c)
          (let [bin (java.io.File. tmp-bin)]
            (when (.exists bin) (.delete bin))))))))

;; ── Reader tests ─────────────────────────────────────────────

(deftest read-all-aria-test
  (testing "reads multiple s-expressions from a string"
    (let [forms (reader/read-all-aria "(func $a (result i32) (return 0)) (func $b (result i32) (return 1))")]
      (is (= 2 (count forms)))
      (is (= 'func (first (first forms))))
      (is (= 'func (first (second forms))))))

  (testing "reads single s-expression"
    (let [forms (reader/read-all-aria "(type $Vec (struct \"Vec\" (f64) (f64)))")]
      (is (= 1 (count forms)))))

  (testing "empty string returns empty vector"
    (is (= [] (reader/read-all-aria "")))
    (is (= [] (reader/read-all-aria "   ")))))

;; ── Top-level comptime ───────────────────────────────────────

(deftest no-comptime-fast-path-test
  (testing "modules without comptime pass through unchanged"
    (let [raw (reader/read-aria
               "(module \"t\" (func $main (result i32) (effects io) (return 0)))")
          expanded (main/expand-comptime raw)]
      (is (= raw expanded)))))

(deftest comptime-detection-test
  (testing "comptime block is expanded in the module"
    (when @gcc?
      (let [raw (reader/read-aria
                 "(module \"t\"
                    (comptime (func $main (result i32) (effects io) (print \"(func $noop (result i32) (effects pure) (return 0))\\n\") (return 0)))
                    (func $main (result i32) (effects io) (return 0)))")
            expanded (main/expand-comptime raw)
            module (parser/parse-module expanded)]
        (is (= 2 (count (:functions module))))))))

(deftest comptime-constant-test
  (testing "comptime that prints a constant is substituted as literal"
    (when @gcc?
      (let [source "(module \"t\"
                      (comptime
                        (func $main (result i32) (effects io)
                          (print \"(func $get_answer (result i32) (effects pure) (return 42))\\n\")
                          (return 0)))
                      (func $main (result i32) (effects io)
                        (print \"%d\\n\" (call $get_answer))
                        (return 0)))"
            stdout (compile-and-run-aria source)]
        (is (= "42\n" stdout))))))

(deftest comptime-function-generation-test
  (testing "comptime generates function definitions spliced into module"
    (when @gcc?
      (let [source "(module \"t\"
                      (comptime
                        (func $main (result i32) (effects io)
                          (print \"(func $add_ten (param $x i32) (result i32) (effects pure)\\n\")
                          (print \"  (return (add.i32 $x 10)))\\n\")
                          (return 0)))
                      (func $main (result i32) (effects io)
                        (print \"%d\\n\" (call $add_ten 5))
                        (return 0)))"
            stdout (compile-and-run-aria source)]
        (is (= "15\n" stdout))))))

(deftest comptime-empty-output-test
  (testing "comptime with no output is removed cleanly"
    (when @gcc?
      (let [source "(module \"t\"
                      (comptime
                        (func $main (result i32) (effects pure)
                          (return 0)))
                      (func $main (result i32) (effects io)
                        (print \"ok\\n\")
                        (return 0)))"
            stdout (compile-and-run-aria source)]
        (is (= "ok\n" stdout))))))

(deftest comptime-multiple-blocks-test
  (testing "multiple comptime blocks are evaluated independently"
    (when @gcc?
      (let [source "(module \"t\"
                      (comptime
                        (func $main (result i32) (effects io)
                          (print \"(func $val_a (result i32) (effects pure) (return 10))\\n\")
                          (return 0)))
                      (comptime
                        (func $main (result i32) (effects io)
                          (print \"(func $val_b (result i32) (effects pure) (return 20))\\n\")
                          (return 0)))
                      (func $main (result i32) (effects io)
                        (print \"%d\\n\" (add.i32 (call $val_a) (call $val_b)))
                        (return 0)))"
            stdout (compile-and-run-aria source)]
        (is (= "30\n" stdout))))))

(deftest comptime-type-generation-test
  (testing "comptime generates type definitions"
    (when @gcc?
      (let [source "(module \"t\"
                      (comptime
                        (func $main (result i32) (effects io)
                          (print \"(type $Point (struct (x f64) (y f64)))\\n\")
                          (return 0)))
                      (func $main (result i32) (effects io mem)
                        (let %p (ptr $Point) (alloc $Point 1))
                        (store.field %p \"x\" 3.0)
                        (store.field %p \"y\" 4.0)
                        (print \"x=%.1f y=%.1f\\n\"
                          (load.field %p \"x\")
                          (load.field %p \"y\"))
                        (free %p)
                        (return 0)))"
            stdout (compile-and-run-aria source)]
        (is (= "x=3.0 y=4.0\n" stdout))))))

(deftest comptime-error-handling-test
  (testing "comptime type-check failure throws"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"failed type-check"
         (let [raw (reader/read-aria
                    "(module \"t\"
                       (comptime
                         (func $main (result i32) (effects pure)
                           (print \"hello\")
                           (return 0)))
                       (func $main (result i32) (effects io) (return 0)))")]
           (main/expand-comptime raw))))))

;; ── Expression-level comptime ────────────────────────────────

(deftest expr-comptime-literal-test
  (testing "expression comptime substitutes a literal in let binding"
    (when @gcc?
      (let [source "(module \"t\"
                      (func $main (result i32) (effects io)
                        (let %x i32 (comptime
                          (func $main (result i32) (effects io)
                            (print \"42\")
                            (return 0))))
                        (print \"%d\\n\" %x)
                        (return 0)))"
            stdout (compile-and-run-aria source)]
        (is (= "42\n" stdout))))))

(deftest expr-comptime-compound-test
  (testing "expression comptime substitutes a compound expression"
    (when @gcc?
      (let [source "(module \"t\"
                      (func $twice (param $n i32) (result i32) (effects pure)
                        (return (mul.i32 $n 2)))
                      (func $main (result i32) (effects io)
                        (print \"%d\\n\" (call $twice (comptime
                          (func $main (result i32) (effects io)
                            (print \"21\")
                            (return 0)))))
                        (return 0)))"
            stdout (compile-and-run-aria source)]
        (is (= "42\n" stdout))))))

(deftest expr-comptime-error-multiple-forms-test
  (testing "expression comptime with multiple forms throws"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"exactly one form"
         (let [raw (reader/read-aria
                    "(module \"t\"
                       (func $main (result i32) (effects io)
                         (let %x i32 (comptime
                           (func $main (result i32) (effects io)
                             (print \"1 2\")
                             (return 0))))
                         (return 0)))")]
           (main/expand-all raw))))))

(deftest expr-comptime-mixed-test
  (testing "top-level and expression comptime in same module"
    (when @gcc?
      (let [source "(module \"t\"
                      (comptime
                        (func $main (result i32) (effects io)
                          (print \"(func $get5 (result i32) (effects pure) (return 5))\\n\")
                          (return 0)))
                      (func $main (result i32) (effects io)
                        (let %x i32 (comptime
                          (func $main (result i32) (effects io)
                            (print \"10\")
                            (return 0))))
                        (print \"%d\\n\" (add.i32 (call $get5) %x))
                        (return 0)))"
            stdout (compile-and-run-aria source)]
        (is (= "15\n" stdout))))))

;; ── comptime-val sugar ───────────────────────────────────────

(deftest comptime-val-i32-test
  (testing "comptime-val computes i32 value at compile time"
    (when @gcc?
      (let [source "(module \"t\"
                      (func $main (result i32) (effects io)
                        (let %x i32 (comptime-val i32 (mul.i32 6 7)))
                        (print \"%d\\n\" %x)
                        (return 0)))"
            stdout (compile-and-run-aria source)]
        (is (= "42\n" stdout))))))

(deftest comptime-val-f64-test
  (testing "comptime-val computes f64 value at compile time"
    (when @gcc?
      (let [source "(module \"t\"
                      (func $main (result i32) (effects io)
                        (let %pi f64 (comptime-val f64 (div.f64 355.0 113.0)))
                        (print \"%.6f\\n\" %pi)
                        (return 0)))"
            stdout (compile-and-run-aria source)]
        ;; 355/113 ≈ 3.141593
        (is (str/starts-with? stdout "3.14159"))))))

(deftest comptime-val-in-call-test
  (testing "comptime-val used directly as function argument"
    (when @gcc?
      (let [source "(module \"t\"
                      (func $twice (param $n i32) (result i32) (effects pure)
                        (return (mul.i32 $n 2)))
                      (func $main (result i32) (effects io)
                        (print \"%d\\n\" (call $twice (comptime-val i32 (add.i32 10 11))))
                        (return 0)))"
            stdout (compile-and-run-aria source)]
        (is (= "42\n" stdout))))))

;; ── Generics via comptime ────────────────────────────────────

(deftest comptime-generics-test
  (testing "comptime generates type-parameterized functions (generics pattern)"
    (when @gcc?
      (let [source "(module \"t\"
                      (comptime
                        (func $gen_max (param $T (ptr i8)) (effects io)
                          (print \"(func $max_%s (param $a %s) (param $b %s) (result %s) (effects pure)\\n\" $T $T $T $T)
                          (print \"  (if (gt.%s $a $b) (then (return $a)) (else (return $b))))\\n\" $T))
                        (func $main (result i32) (effects io)
                          (call $gen_max \"i32\")
                          (call $gen_max \"f64\")
                          (return 0)))
                      (func $main (result i32) (effects io)
                        (print \"max_i32(3,7) = %d\\n\" (call $max_i32 3 7))
                        (print \"max_f64(2.5,1.5) = %.1f\\n\" (call $max_f64 2.5 1.5))
                        (return 0)))"
            stdout (compile-and-run-aria source)]
        (is (= "max_i32(3,7) = 7\nmax_f64(2.5,1.5) = 2.5\n" stdout))))))

(deftest comptime-generics-multiple-params-test
  (testing "comptime generator with multiple type parameters"
    (when @gcc?
      (let [source "(module \"t\"
                      (comptime
                        (func $gen_convert (param $From (ptr i8)) (param $To (ptr i8)) (effects io)
                          (print \"(func $convert_%s_to_%s (param $x %s) (result %s) (effects pure)\\n\" $From $To $From $To)
                          (print \"  (return (cast %s %s $x)))\\n\" $From $To))
                        (func $main (result i32) (effects io)
                          (call $gen_convert \"i32\" \"f64\")
                          (return 0)))
                      (func $main (result i32) (effects io)
                        (print \"%.1f\\n\" (call $convert_i32_to_f64 42))
                        (return 0)))"
            stdout (compile-and-run-aria source)]
        (is (= "42.0\n" stdout))))))

(deftest comptime-generics-file-test
  (testing "comptime_generics.aria example compiles and runs correctly"
    (when @gcc?
      (let [source (slurp "examples/comptime_generics.aria")
            stdout (compile-and-run-aria source)]
        (is (str/includes? stdout "identity_i32(42) = 42"))
        (is (str/includes? stdout "max_i32(10, 20) = 20"))
        (is (str/includes? stdout "clamp_i32(100, 0, 50) = 50"))
        (is (str/includes? stdout "add_i32(15, 27) = 42"))
        (is (str/includes? stdout "identity_f64(3.14) = 3.14"))
        (is (str/includes? stdout "add_f64(1.5, 2.5) = 4.0"))))))

;; ── comptime_demo.aria (existing example) ────────────────────

(deftest comptime-demo-file-test
  (testing "comptime_demo.aria compiles and runs correctly"
    (when @gcc?
      (let [source (slurp "examples/comptime_demo.aria")
            stdout (compile-and-run-aria source)]
        (is (str/includes? stdout "Square of 0: 0"))
        (is (str/includes? stdout "Square of 3: 9"))
        (is (str/includes? stdout "Unknown (5): -1"))))))
