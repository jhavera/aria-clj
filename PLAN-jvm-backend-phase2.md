# PLAN: ARIA-JVM bytecode backend (Phase 2)

This document is the implementation spec for Phase 2 of the JVM backend.
It is written for Claude Code and serves as the authoritative reference.
Read it fully before writing any code.

Phase 1 (merged in PR #10) is the baseline. This plan builds directly on it.

---

## Acceptance tests

Both must pass when Phase 2 is complete.

**Test 1: Runnable JAR**

```bash
clojure -M:run examples/fibonacci.aria --backend jvm --emit-jar
java -jar Fibonacci.jar
```

Output must match the Phase 1 `java Fibonacci` output exactly.

**Test 2: Intent annotations queryable at runtime**

```bash
clojure -M:run examples/fibonacci.aria --backend jvm --emit-class
java -cp . AriaInspect Fibonacci
```

Where `AriaInspect` is a small utility class (see Step 4 below) that reads
`@Intent` annotations via reflection and prints them. Each annotated method
should print its intent string.

---

## Constraints (from CONTRIBUTING.md)

- Idiomatic Clojure. Pure functions, immutable data, threading macros.
- Function-level docstrings on all public functions.
- New features require tests. Run `clojure -M:test` before marking done.
- deps.edn only. No Leiningen.

---

## Files to create or modify

```
src/aria/codegen_jvm.clj           modify  -- add emit-jar! and @Intent annotation pass
src/aria/main.clj                  modify  -- add --emit-jar flag routing
test/aria/codegen_jvm_test.clj     modify  -- add Phase 2 tests
examples/AriaInspect.java          create  -- reflection utility for manual testing
```

deps.edn does not change. No new dependencies are required -- JAR creation
uses `java.util.jar` from the standard library, and annotations use ASM's
existing `visitAnnotation` API already on the classpath.

---

## Step 1: @Intent annotation definition

The JVM annotation system requires a `.class` file defining the annotation
type before any class can use it. The annotation is:

```java
package com.ariacompiler;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Intent {
    String value();
}
```

Do NOT create this as a `.java` file and compile it separately. Generate it
with ASM in Clojure, exactly as the main backend generates `.class` files.

Add a private function to `codegen_jvm.clj`:

```clojure
(defn- generate-intent-annotation-class
  "Generates the bytecode for the @com.ariacompiler.Intent annotation type.
  Returns a byte array containing a valid .class file for the annotation.
  This class must be present on the classpath or in the JAR for @Intent
  to be queryable via reflection at runtime."
  []
  (let [cw (ClassWriter. 0)]   ; No COMPUTE_FRAMES needed for annotation types
    (.visit cw
            Opcodes/V11
            (+ Opcodes/ACC_PUBLIC Opcodes/ACC_ABSTRACT Opcodes/ACC_INTERFACE
               Opcodes/ACC_ANNOTATION)
            "com/ariacompiler/Intent"
            nil
            "java/lang/Object"
            (into-array String ["java/lang/annotation/Annotation"]))
    ;; @Retention(RUNTIME)
    (let [av (.visitAnnotation cw
                               "Ljava/lang/annotation/Retention;"
                               true)]
      (.visitEnum av "value"
                  "Ljava/lang/annotation/RetentionPolicy;"
                  "RUNTIME")
      (.visitEnd av))
    ;; @Target(METHOD)
    (let [av (.visitAnnotation cw
                               "Ljava/lang/annotation/Target;"
                               true)
          arr (.visitArray av "value")]
      (.visitEnum arr nil
                  "Ljava/lang/annotation/ElementType;"
                  "METHOD")
      (.visitEnd arr)
      (.visitEnd av))
    ;; value() element
    (let [mv (.visitMethod cw Opcodes/ACC_PUBLIC_ABSTRACT
                           "value" "()Ljava/lang/String;" nil nil)]
      (.visitEnd mv))
    (.visitEnd cw)
    (.toByteArray cw)))
```

The internal JVM name for the annotation is `com/ariacompiler/Intent`
(slashes, not dots). The descriptor used when visiting annotations is
`Lcom/ariacompiler/Intent;` (L prefix, semicolon suffix, dots replaced
with slashes).

---

## Step 2: Emit @Intent on methods

In the existing `emit-method!` function, the function body is already
iterated for statements. Add a pass before emitting body statements that
scans for `:intent` nodes and extracts the intent string.

Currently in `emit-stmt!`, `:intent` is a no-op:

```clojure
:intent nil
```

Leave that no-op in place -- it handles intent nodes that appear mid-body.
The annotation must be attached to the method visitor, not emitted inline.

Add a private helper:

```clojure
(defn- extract-intent
  "Returns the intent string from a function's body, or nil if none declared.
  Scans the top level of the body for an :intent node."
  [func]
  (->> (:body func)
       (filter #(= :intent (:node/type %)))
       first
       :value))
```

Then in `emit-method!`, after `.visitCode` and before emitting body statements,
add the annotation if an intent string is present:

```clojure
(when-let [intent-str (extract-intent func)]
  (let [av (.visitAnnotation mv "Lcom/ariacompiler/Intent;" true)]
    (.visitString av "value" intent-str)
    (.visitEnd av)))
```

The `true` argument means the annotation is visible at runtime (matches
`@Retention(RUNTIME)`).

---

## Step 3: emit-jar!

Add a new public function to `codegen_jvm.clj`:

```clojure
(defn emit-jar!
  "Compiles an ARIA-IR AST to a runnable JAR file in output-dir.
  The JAR includes:
    - The compiled ARIA module as a .class file
    - The com.ariacompiler.Intent annotation class
    - A manifest with Main-Class set to the compiled class name
  Returns the java.io.File written."
  [ast output-dir]
  ...)
```

Implementation outline:

```clojure
(defn emit-jar!
  [ast output-dir]
  (let [module-name (:name ast)
        class-name  (str (Character/toUpperCase (first module-name))
                         (subs module-name 1))
        class-bytes  (generate-class ast class-name)
        intent-bytes (generate-intent-annotation-class)
        manifest     (doto (java.util.jar.Manifest.)
                       (-> .getMainAttributes
                           (doto
                             (.put java.util.jar.Attributes$Name/MANIFEST_VERSION "1.0")
                             (.put java.util.jar.Attributes$Name/MAIN_CLASS class-name))))
        dir          (java.io.File. output-dir)
        jar-file     (java.io.File. dir (str class-name ".jar"))]
    (.mkdirs dir)
    (with-open [jos (java.util.jar.JarOutputStream.
                     (java.io.FileOutputStream. jar-file) manifest)]
      ;; Write the annotation class
      (.putNextEntry jos (java.util.jar.JarEntry.
                          "com/ariacompiler/Intent.class"))
      (.write jos ^bytes intent-bytes)
      (.closeEntry jos)
      ;; Write the compiled ARIA module class
      (.putNextEntry jos (java.util.jar.JarEntry.
                          (str class-name ".class")))
      (.write jos ^bytes class-bytes)
      (.closeEntry jos))
    (println (str "Wrote " (.getPath jar-file)
                  " (" (.length jar-file) " bytes)"))
    jar-file))
```

---

## Step 4: src/aria/main.clj changes

Read main.clj before writing. Match existing patterns exactly.

Add routing for `--emit-jar`. In Phase 1, `--backend jvm` routes to
`emit-class-file!`. Add a parallel path:

```clojure
"--emit-jar" (codegen-jvm/emit-jar! ast ".")
```

The exact placement depends on how main.clj currently parses flags. If it
dispatches on `--backend`, add `--emit-jar` as a top-level flag checked
before backend dispatch, similar to how `--emit-c` may be handled. Read
the existing code first.

---

## Step 5: examples/AriaInspect.java

This is a manual testing utility, not part of the compiler. Create it in
`examples/` so contributors can use it to verify intent annotations:

```java
import java.lang.reflect.Method;
import java.lang.annotation.Annotation;

public class AriaInspect {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("Usage: java -cp . AriaInspect <ClassName>");
            return;
        }
        Class<?> cls = Class.forName(args[0]);
        System.out.println("Inspecting: " + cls.getName());
        for (Method m : cls.getDeclaredMethods()) {
            Annotation[] anns = m.getDeclaredAnnotations();
            if (anns.length == 0) continue;
            System.out.println("  " + m.getName() + "()");
            for (Annotation a : anns) {
                System.out.println("    " + a);
            }
        }
    }
}
```

This is a plain Java file. Compile it once with:

```bash
javac examples/AriaInspect.java -d .
```

Then use it as described in the acceptance test.

Note: `AriaInspect` uses `Class.forName` which requires the annotation class
to be on the classpath. When testing with `--emit-class`, you need both the
ARIA `.class` file AND the `com/ariacompiler/Intent.class` file present.
When testing with `--emit-jar`, use `java -cp Fibonacci.jar AriaInspect Fibonacci`
since the annotation class is bundled in the JAR.

---

## Step 6: test/aria/codegen_jvm_test.clj additions

Add three new test groups to the existing file. Do not modify existing tests.
Read the existing test file before writing to match naming and structure.

### Test group 4: @Intent annotation class is valid

```clojure
(deftest intent-annotation-class-has-magic-bytes
  (let [bytes (#'aria.codegen-jvm/generate-intent-annotation-class)]
    (is (= (int 0xCA) (bit-and (aget bytes 0) 0xFF)))
    (is (= (int 0xFE) (bit-and (aget bytes 1) 0xFF)))
    (is (= (int 0xBA) (bit-and (aget bytes 2) 0xFF)))
    (is (= (int 0xBE) (bit-and (aget bytes 3) 0xFF)))))

(deftest intent-annotation-class-is-non-empty
  (let [bytes (#'aria.codegen-jvm/generate-intent-annotation-class)]
    (is (pos? (count bytes)))))
```

### Test group 5: Intent annotation is emitted on methods

Load both the annotation class and the compiled ARIA class into a custom
classloader, then verify `@Intent` is present on the annotated method.

```clojure
(defn- load-with-intent-annotation [aria-class-bytes aria-class-name]
  (let [intent-bytes (#'aria.codegen-jvm/generate-intent-annotation-class)
        loader (proxy [ClassLoader] []
                 (findClass [name]
                   (cond
                     (= name aria-class-name)
                     (.defineClass this name aria-class-bytes
                                   0 (count aria-class-bytes))
                     (= name "com.ariacompiler.Intent")
                     (.defineClass this name intent-bytes
                                   0 (count intent-bytes))
                     :else
                     (throw (ClassNotFoundException. name)))))]
    (.loadClass loader aria-class-name)))

(deftest intent-annotation-present-on-method
  (let [ast   (intent-ast-fixture)   ; see fixture note below
        bytes (codegen-jvm/generate-class ast "IntentTest")
        klass (load-with-intent-annotation bytes "IntentTest")
        method (.getDeclaredMethod klass "greet" (into-array Class []))
        anns  (.getDeclaredAnnotations method)]
    (is (pos? (count anns)))
    (is (some #(str/includes? (str %) "Greet a user") anns))))
```

The `intent-ast-fixture` is a minimal AST for:

```lisp
(module "intenttest"
  (func $greet
    (result i32)
    (effects pure)
    (intent "Greet a user")
    (return 42)))
```

Construct it as a plain Clojure map matching the parser's AST shape.
Read `parser.clj` to confirm the exact structure before writing the fixture.

### Test group 6: emit-jar! produces a valid JAR

```clojure
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
```

---

## What Phase 2 does NOT include

- Pointer type support (bubble_sort.aria): Phase 3
- Modulo and other missing binary ops: Phase 3
- Java standard library interop: Phase 3
- Any changes to the reader, parser, or checker

---

## Before opening a PR

- `clojure -M:test` passes with no failures
- Both acceptance tests pass (JAR runs, intent annotations are queryable)
- All new public functions have docstrings
- No changes outside the four files listed above
- `examples/AriaInspect.java` is committed but its compiled `.class` is in
  `.gitignore` (already should be)
