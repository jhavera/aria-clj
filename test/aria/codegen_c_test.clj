(ns aria.codegen-c-test
  (:require [clojure.test :refer [deftest is testing]]
            [aria.parser :as parser]
            [aria.codegen-c :as codegen-c]
            [clojure.string :as str]))

;; ── Helper ───────────────────────────────────────────────────

(defn- gen-c [source]
  (let [module (parser/parse source)]
    (codegen-c/generate module)))

;; ── Effect Annotation Tests ────────────────────────────────

(deftest effects-const-attr-test
  (testing "Pure function without ptr params gets __attribute__((const))"
    (let [c (gen-c "(module \"t\"
                      (func $fibonacci (param $n i32) (result i32)
                        (effects pure)
                        (return $n)))")]
      (is (str/includes? c "__attribute__((const))"))
      (is (str/includes? c "/* effects: pure */")))))

(deftest effects-pure-attr-test
  (testing "Pure function with ptr param gets __attribute__((pure))"
    (let [c (gen-c "(module \"t\"
                      (func $sum_array (param $arr (ptr i32)) (param $len i32) (result i32)
                        (effects pure)
                        (return $len)))")]
      (is (str/includes? c "__attribute__((pure))"))
      (is (not (str/includes? c "__attribute__((const))")))
      (is (str/includes? c "/* effects: pure */")))))

(deftest effects-malloc-attr-test
  (testing "Mem function returning ptr gets __attribute__((malloc))"
    (let [c (gen-c "(module \"t\"
                      (func $alloc_buf (param $size i32) (result (ptr u8))
                        (effects mem)
                        (return (alloc (ptr u8) $size))))")]
      (is (str/includes? c "__attribute__((malloc))"))
      (is (str/includes? c "/* effects: mem */")))))

(deftest effects-comment-only-test
  (testing "IO effects get comment but no compiler attribute"
    (let [c (gen-c "(module \"t\"
                      (func $greet (effects io) (print \"hello\")))")]
      (is (str/includes? c "/* effects: io */"))
      (is (not (str/includes? c "__attribute__"))))))

(deftest effects-multiple-test
  (testing "Multiple effects are sorted in comment"
    (let [c (gen-c "(module \"t\"
                      (func $work (param $p (ptr i32)) (param $n i32)
                        (effects io mem div)
                        (print \"%d\" $n)))")]
      (is (str/includes? c "/* effects: div io mem */")))))

(deftest effects-forward-decl-test
  (testing "Forward declarations also carry the attribute"
    (let [c (gen-c "(module \"t\"
                      (func $f (param $n i32) (result i32)
                        (effects pure)
                        (return $n)))")
          lines (str/split-lines c)
          fwd-decl (first (filter #(and (str/includes? % "fibonacci\\|;")
                                        (str/ends-with? % ");"))
                                  lines))
          ;; Find forward declaration line (ends with ;)
          fwd-lines (filter #(and (str/includes? % "__attribute__((const))")
                                  (str/ends-with? (str/trim %) ");"))
                            lines)]
      (is (seq fwd-lines) "Forward declaration should have __attribute__((const))"))))
