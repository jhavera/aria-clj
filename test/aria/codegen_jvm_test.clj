(ns aria.codegen-jvm-test
  (:require [clojure.test :refer [deftest is are testing]]
            [aria.ast :as ast]
            [aria.codegen-jvm :as codegen-jvm]))

;; ── Test Group 1: Type descriptor mapping ───────────────────

(deftest type-descriptor-mapping
  (are [type expected] (= expected (#'aria.codegen-jvm/aria-type->descriptor type))
    :i32  "I"
    :i64  "J"
    :f64  "D"
    :bool "Z"
    :str  "Ljava/lang/String;"
    :void "V"))

(deftest unknown-type-throws
  (is (thrown? clojure.lang.ExceptionInfo
               (#'aria.codegen-jvm/aria-type->descriptor :unknown))))

;; ── AST Fixtures ────────────────────────────────────────────

(defn- minimal-ast-fixture
  "Simplest valid ARIA program: a module with $main returning 0."
  []
  (ast/module "test" [] []
    [(ast/function "$main"
       []
       (ast/primitive "i32")
       #{:io}
       nil
       [(ast/return-node (ast/int-literal 0))])]
    [(ast/export-node "$main" nil)]))

(defn- add-i32-fixture
  "A function that adds two i32 arguments."
  [a b]
  (ast/module "addtest" [] []
    [(ast/function "$add"
       [(ast/param "$a" (ast/primitive "i32"))
        (ast/param "$b" (ast/primitive "i32"))]
       (ast/primitive "i32")
       #{:pure}
       nil
       [(ast/return-node
          (ast/bin-op "add" "i32"
            (ast/var-ref "$a")
            (ast/var-ref "$b")))])]
    [(ast/export-node "$add" nil)]))

;; ── Test Group 2: Valid class file ──────────────────────────

(deftest generates-valid-class-magic-bytes
  (let [bytes (codegen-jvm/generate-class (minimal-ast-fixture) "TestClass")]
    (is (= (int 0xCA) (bit-and (aget bytes 0) 0xFF)))
    (is (= (int 0xFE) (bit-and (aget bytes 1) 0xFF)))
    (is (= (int 0xBA) (bit-and (aget bytes 2) 0xFF)))
    (is (= (int 0xBE) (bit-and (aget bytes 3) 0xFF)))))

(deftest generates-non-empty-class
  (let [bytes (codegen-jvm/generate-class (minimal-ast-fixture) "TestClass")]
    (is (pos? (count bytes)))))

;; ── Test Group 3: Round-trip execution ──────────────────────

(defn- primitive-class
  "Map boxed Class to primitive Class for reflection."
  [^Class c]
  (condp = c
    Integer   Integer/TYPE
    Long      Long/TYPE
    Double    Double/TYPE
    Boolean   Boolean/TYPE
    c))

(defn- load-and-invoke [class-bytes class-name method-name & args]
  (let [tmp-dir (java.io.File/createTempFile "aria-jvm-test" "")
        _ (.delete tmp-dir)
        _ (.mkdirs tmp-dir)
        class-file (java.io.File. tmp-dir (str class-name ".class"))
        _ (with-open [os (java.io.FileOutputStream. class-file)]
            (.write os ^bytes class-bytes))
        loader (java.net.URLClassLoader. (into-array java.net.URL [(.toURL (.toURI tmp-dir))]))
        klass  (.loadClass loader class-name)
        param-types (into-array Class (map (comp primitive-class type) args))
        method (.getMethod klass method-name param-types)
        result (.invoke method nil (into-array Object args))]
    (.close loader)
    (.delete class-file)
    (.delete tmp-dir)
    result))

(deftest add-i32-round-trip
  (let [ast    (add-i32-fixture 3 4)
        bytes  (codegen-jvm/generate-class ast "AddTest")
        result (load-and-invoke bytes "AddTest" "add" (int 3) (int 4))]
    (is (= 7 result))))

(deftest main-returns-zero
  (testing "minimal main function returns 0"
    (let [bytes  (codegen-jvm/generate-class (minimal-ast-fixture) "MainTest")
          result (load-and-invoke bytes "MainTest" "main")]
      (is (= 0 result)))))
