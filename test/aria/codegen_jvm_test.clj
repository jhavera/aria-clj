(ns aria.codegen-jvm-test
  (:require [clojure.test :refer [deftest is are testing]]
            [clojure.string :as str]
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

(defn- intent-ast-fixture
  "A module with a function that has an intent annotation."
  []
  (ast/module "intenttest" [] []
    [(ast/function "$greet"
       []
       (ast/primitive "i32")
       #{:pure}
       "Greet a user"
       [(ast/return-node (ast/int-literal 42))])]
    [(ast/export-node "$greet" nil)]))

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

;; ── Test Group 4: @Intent annotation class is valid ─────────

(deftest intent-annotation-class-has-magic-bytes
  (let [bytes (#'aria.codegen-jvm/generate-intent-annotation-class)]
    (is (= (int 0xCA) (bit-and (aget bytes 0) 0xFF)))
    (is (= (int 0xFE) (bit-and (aget bytes 1) 0xFF)))
    (is (= (int 0xBA) (bit-and (aget bytes 2) 0xFF)))
    (is (= (int 0xBE) (bit-and (aget bytes 3) 0xFF)))))

(deftest intent-annotation-class-is-non-empty
  (let [bytes (#'aria.codegen-jvm/generate-intent-annotation-class)]
    (is (pos? (count bytes)))))

;; ── Test Group 5: Intent annotation is emitted on methods ───

(defn- write-class-file!
  "Write a byte array as a .class file in the given directory."
  [^java.io.File dir class-path ^bytes class-bytes]
  (let [f (java.io.File. dir class-path)]
    (.mkdirs (.getParentFile f))
    (with-open [os (java.io.FileOutputStream. f)]
      (.write os class-bytes))
    f))

(defn- load-with-intent-annotation
  "Load both the @Intent annotation class and an ARIA-compiled class into
  a URLClassLoader, returning the loaded ARIA class."
  [aria-class-bytes aria-class-name]
  (let [intent-bytes (#'aria.codegen-jvm/generate-intent-annotation-class)
        tmp-dir (java.io.File/createTempFile "aria-intent-test" "")
        _ (.delete tmp-dir)
        _ (.mkdirs tmp-dir)]
    (write-class-file! tmp-dir "com/ariacompiler/Intent.class" intent-bytes)
    (write-class-file! tmp-dir (str aria-class-name ".class") aria-class-bytes)
    (let [loader (java.net.URLClassLoader.
                  (into-array java.net.URL [(.toURL (.toURI tmp-dir))]))]
      (.loadClass loader aria-class-name))))

(deftest intent-annotation-present-on-method
  (let [ast   (intent-ast-fixture)
        bytes (codegen-jvm/generate-class ast "IntentTest")
        klass (load-with-intent-annotation bytes "IntentTest")
        method (.getDeclaredMethod klass "greet" (into-array Class []))
        anns  (.getDeclaredAnnotations method)]
    (is (pos? (count anns)))
    (is (some #(str/includes? (str %) "Greet a user") anns))))

;; ── Test Group 6: emit-jar! produces a valid JAR ────────────

(deftest emit-jar-produces-file
  (let [ast      (minimal-ast-fixture)
        tmp-dir  (str (System/getProperty "java.io.tmpdir") "/aria-jvm-test")
        jar-file (codegen-jvm/emit-jar! ast tmp-dir)]
    (is (.exists jar-file))
    (is (str/ends-with? (.getName jar-file) ".jar"))
    (is (pos? (.length jar-file)))))

(deftest emit-jar-contains-class-and-annotation
  (let [ast      (minimal-ast-fixture)
        tmp-dir  (str (System/getProperty "java.io.tmpdir") "/aria-jvm-test2")
        jar-file (codegen-jvm/emit-jar! ast tmp-dir)
        entries  (with-open [jf (java.util.zip.ZipFile. jar-file)]
                   (set (map #(.getName %) (enumeration-seq (.entries jf)))))]
    (is (contains? entries "com/ariacompiler/Intent.class"))
    (is (some #(str/ends-with? % ".class") entries))))

;; ── Phase 3 AST Fixtures ────────────────────────────────────

(defn- rem-i32-fixture
  "A function that computes a % b."
  [a b]
  (ast/module "remtest" [] []
    [(ast/function "$rem_fn"
       [(ast/param "$a" (ast/primitive "i32"))
        (ast/param "$b" (ast/primitive "i32"))]
       (ast/primitive "i32")
       #{:pure}
       nil
       [(ast/return-node
          (ast/bin-op "rem" "i32"
            (ast/var-ref "$a")
            (ast/var-ref "$b")))])]
    [(ast/export-node "$rem_fn" nil)]))

(defn- cast-i32-i64-fixture
  "A function that casts an i32 param to i64 and returns it."
  [_v]
  (ast/module "casttest" [] []
    [(ast/function "$cast_fn"
       [(ast/param "$x" (ast/primitive "i32"))]
       (ast/primitive "i64")
       #{:pure}
       nil
       [(ast/return-node
          (ast/cast-node (ast/primitive "i32")
                         (ast/primitive "i64")
                         (ast/var-ref "$x")))])]
    [(ast/export-node "$cast_fn" nil)]))

(defn- alloc-store-load-fixture
  "Alloc 1 int, store 99, load it back."
  []
  (ast/module "memtest" [] []
    [(ast/function "$mem_fn"
       []
       (ast/primitive "i32")
       #{:mem}
       nil
       [(ast/let-binding "%ptr" (ast/ptr-type (ast/primitive "i32"))
                         (ast/alloc-node (ast/primitive "i32") (ast/int-literal 1))
                         false)
        (ast/store-node "i32" (ast/var-ref "%ptr") (ast/int-literal 99))
        (ast/return-node (ast/load-node "i32" (ast/var-ref "%ptr")))])]
    [(ast/export-node "$mem_fn" nil)]))

;; ── Test Group 7: rem opcode ────────────────────────────────

(deftest rem-i32-round-trip
  (let [ast    (rem-i32-fixture 10 3)
        bytes  (codegen-jvm/generate-class ast "RemTest")
        result (load-and-invoke bytes "RemTest" "rem_fn"
                                (int 10) (int 3))]
    (is (= 1 result))))

;; ── Test Group 8: cast i32 to i64 ──────────────────────────

(deftest cast-i32-to-i64-round-trip
  (let [ast    (cast-i32-i64-fixture 42)
        bytes  (codegen-jvm/generate-class ast "CastTest")
        result (load-and-invoke bytes "CastTest" "cast_fn" (int 42))]
    (is (= 42 result))
    (is (instance? Long result))))

;; ── Test Group 9: AriaMemory class is valid ─────────────────

(deftest aria-memory-class-has-magic-bytes
  (let [bytes (#'aria.codegen-jvm/generate-aria-memory-class)]
    (is (= (int 0xCA) (bit-and (aget bytes 0) 0xFF)))
    (is (= (int 0xFE) (bit-and (aget bytes 1) 0xFF)))
    (is (= (int 0xBA) (bit-and (aget bytes 2) 0xFF)))
    (is (= (int 0xBE) (bit-and (aget bytes 3) 0xFF)))))

;; ── Test Group 10: alloc/store/load round-trip ──────────────

(defn- load-and-invoke-with-memory [class-bytes class-name method-name & args]
  (let [mem-bytes (#'aria.codegen-jvm/generate-aria-memory-class)
        tmp-dir (java.io.File/createTempFile "aria-mem-test" "")
        _ (.delete tmp-dir)
        _ (.mkdirs tmp-dir)]
    ;; Write AriaMemory class
    (write-class-file! tmp-dir "com/ariacompiler/AriaMemory.class" mem-bytes)
    ;; Write the test class
    (write-class-file! tmp-dir (str class-name ".class") class-bytes)
    (let [loader (java.net.URLClassLoader.
                  (into-array java.net.URL [(.toURL (.toURI tmp-dir))]))
          klass (.loadClass loader class-name)
          param-types (into-array Class (map (comp primitive-class type) args))
          method (.getMethod klass method-name param-types)
          result (.invoke method nil (into-array Object args))]
      (.close loader)
      result)))

(deftest alloc-store-load-round-trip
  (let [ast    (alloc-store-load-fixture)
        bytes  (codegen-jvm/generate-class ast "MemTest")
        result (load-and-invoke-with-memory bytes "MemTest" "mem_fn")]
    (is (= 99 result))))

;; ── Test Group 11: emit-jar bundles AriaMemory ──────────────

(deftest emit-jar-contains-aria-memory
  (let [ast      (minimal-ast-fixture)
        tmp-dir  (str (System/getProperty "java.io.tmpdir") "/aria-jvm-p3-test")
        jar-file (codegen-jvm/emit-jar! ast tmp-dir)
        entries  (with-open [jf (java.util.zip.ZipFile. jar-file)]
                   (set (map #(.getName %) (enumeration-seq (.entries jf)))))]
    (is (contains? entries "com/ariacompiler/AriaMemory.class"))))
