(ns aria.parser-test
  (:require [clojure.test :refer [deftest is testing]]
            [aria.parser :as parser]
            [aria.reader :as reader]
            [aria.checker :as checker]
            [aria.codegen-c :as codegen]))

(deftest reader-test
  (testing "preprocesses $ and % variables"
    (let [form (reader/read-aria "(add.i32 $x %y)")]
      (is (= 'add.i32 (first form)))
      (is (reader/mutable-var? (second form)))
      (is (reader/ssa-var? (nth form 2)))))

  (testing "var-name extracts original name"
    (is (= "$foo" (reader/var-name 'aria$foo)))
    (is (= "%bar" (reader/var-name 'aria%bar))))

  (testing "raw-var-name strips all prefixes"
    (is (= "foo" (reader/raw-var-name 'aria$foo)))
    (is (= "bar" (reader/raw-var-name 'aria%bar)))))

(deftest parse-literals-test
  (testing "integer literal"
    (let [m (parser/parse "(module \"t\" (func $f (result i32) (effects pure) (return 42)))")]
      (is (= :int-literal (:node/type (-> m :functions first :body first :value))))))

  (testing "boolean literal"
    (let [m (parser/parse "(module \"t\" (func $f (effects pure) (return true)))")]
      (is (= :bool-literal (:node/type (-> m :functions first :body first :value)))))))

(deftest parse-module-test
  (testing "parses module name and structure"
    (let [m (parser/parse "(module \"test\" (func $main (result i32) (effects io) (return 0)) (export $main))")]
      (is (= "test" (:name m)))
      (is (= 1 (count (:functions m))))
      (is (= 1 (count (:exports m)))))))

(deftest parse-fibonacci-test
  (testing "fibonacci.aria parses correctly"
    (let [m (parser/parse-file "examples/fibonacci.aria")]
      (is (= "fibonacci" (:name m)))
      (is (= 3 (count (:functions m))))
      (is (= 3 (count (:exports m))))
      (is (= "$fibonacci" (-> m :functions first :name)))
      (is (= #{:pure} (-> m :functions first :effects))))))

(deftest parse-all-examples-test
  (testing "all example files parse without error"
    (doseq [f ["examples/fibonacci.aria"
               "examples/bubble_sort.aria"
               "examples/math_demo.aria"]]
      (let [m (parser/parse-file f)]
        (is (= :module (:node/type m)) (str f " should parse to a module"))
        (is (pos? (count (:functions m))) (str f " should have functions"))))))

(deftest checker-test
  (testing "all examples type-check"
    (doseq [f ["examples/fibonacci.aria"
               "examples/bubble_sort.aria"
               "examples/math_demo.aria"]]
      (let [m (parser/parse-file f)
            result (checker/check m)]
        (is (:ok? result) (str f " should type-check: " (:errors result)))))))

(deftest checker-undefined-var-test
  (testing "catches undefined variables"
    (let [m (parser/parse "(module \"t\" (func $f (result i32) (effects pure) (return $undefined)))")
          result (checker/check m)]
      (is (not (:ok? result)))
      (is (some #(.contains ^String % "Undefined variable") (:errors result))))))

(deftest codegen-test
  (testing "generates valid C for fibonacci"
    (let [m (parser/parse-file "examples/fibonacci.aria")
          c (codegen/generate m)]
      (is (clojure.string/includes? c "#include <stdio.h>"))
      (is (clojure.string/includes? c "int32_t fibonacci(int32_t n)"))
      (is (clojure.string/includes? c "int32_t main(void)")))))
