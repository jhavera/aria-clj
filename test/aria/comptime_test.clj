(ns aria.comptime-test
  "Tests for comptime (compile-time evaluation) two-pass model."
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
  "Full pipeline: source → expand comptime → parse → check → codegen C → gcc → run.
   Returns stdout string."
  [source]
  (let [raw-form (reader/read-aria source)
        expanded (main/expand-comptime raw-form)
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

;; ── Comptime separation ──────────────────────────────────────

(deftest separate-comptime-test
  (testing "no comptime blocks detected"
    (let [raw (reader/read-aria
               "(module \"t\" (func $main (result i32) (effects io) (return 0)))")
          result (#'main/separate-comptime raw)]
      (is (not (:has-comptime? result)))
      (is (= 1 (count (:forms result))))))

  (testing "comptime block is detected and separated"
    (let [raw (reader/read-aria
               "(module \"t\"
                  (comptime (func $main (result i32) (effects io) (print \"hello\") (return 0)))
                  (func $main (result i32) (effects io) (return 0)))")
          result (#'main/separate-comptime raw)]
      (is (:has-comptime? result))
      ;; First form should be a comptime placeholder map
      (is (map? (first (:forms result))))
      (is (= 0 (:comptime-idx (first (:forms result)))))
      ;; Second form should be the normal func
      (is (seq? (second (:forms result)))))))

;; ── Full comptime evaluation ─────────────────────────────────

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

(deftest comptime-demo-file-test
  (testing "comptime_demo.aria compiles and runs correctly"
    (when @gcc?
      (let [source (slurp "examples/comptime_demo.aria")
            stdout (compile-and-run-aria source)]
        (is (str/includes? stdout "Square of 0: 0"))
        (is (str/includes? stdout "Square of 3: 9"))
        (is (str/includes? stdout "Unknown (5): -1"))))))

;; ── Fast path (no comptime) ─────────────────────────────────

(deftest no-comptime-fast-path-test
  (testing "modules without comptime pass through unchanged"
    (let [raw (reader/read-aria
               "(module \"t\" (func $main (result i32) (effects io) (return 0)))")
          expanded (main/expand-comptime raw)]
      ;; Should return the exact same form
      (is (= raw expanded)))))
