(ns aria.codegen-jvm
  "JVM bytecode backend for ARIA-IR.
  Compiles a validated AST (post-checker) to .class files and runnable JARs
  using ASM. Entry points: generate-class, emit-class-file!, and emit-jar!"
  (:require [clojure.string :as str])
  (:import [org.objectweb.asm ClassWriter MethodVisitor Opcodes Label]))

;; ── Type Helpers ────────────────────────────────────────────

(defn- aria-type->descriptor [t]
  (case t
    :i32  "I"
    :i64  "J"
    :f64  "D"
    :bool "Z"
    :str  "Ljava/lang/String;"
    :void "V"
    (throw (ex-info "Unknown ARIA type" {:type t}))))

(defn- aria-type->load-op [t]
  (case t
    (:i32 :bool) Opcodes/ILOAD
    :i64         Opcodes/LLOAD
    :f64         Opcodes/DLOAD
    :str         Opcodes/ALOAD
    (throw (ex-info "No load opcode for type" {:type t}))))

(defn- aria-type->store-op [t]
  (case t
    (:i32 :bool) Opcodes/ISTORE
    :i64         Opcodes/LSTORE
    :f64         Opcodes/DSTORE
    :str         Opcodes/ASTORE
    (throw (ex-info "No store opcode for type" {:type t}))))

(defn- aria-type->return-op [t]
  (case t
    (:i32 :bool) Opcodes/IRETURN
    :i64         Opcodes/LRETURN
    :f64         Opcodes/DRETURN
    :str         Opcodes/ARETURN
    :void        Opcodes/RETURN
    (throw (ex-info "No return opcode for type" {:type t}))))

(defn- type-suffix->kw
  "Convert ARIA type suffix string to keyword."
  [s]
  (keyword s))

(defn- aria-type-map->kw
  "Convert AST type map {:type/kind :primitive :type/name \"i32\"} to keyword."
  [t]
  (when t
    (if (= :primitive (:type/kind t))
      (keyword (:type/name t))
      (throw (ex-info "Non-primitive type in JVM backend" {:type t})))))

(defn- wide-type?
  "Returns true for types that occupy two JVM slots (long, double)."
  [t]
  (contains? #{:i64 :f64} t))

;; ── Arithmetic ──────────────────────────────────────────────

(defn- emit-binop [^MethodVisitor mv {:keys [op type]}]
  (case [op type]
    [:add :i32] (.visitInsn mv Opcodes/IADD)
    [:sub :i32] (.visitInsn mv Opcodes/ISUB)
    [:mul :i32] (.visitInsn mv Opcodes/IMUL)
    [:div :i32] (.visitInsn mv Opcodes/IDIV)
    [:add :i64] (.visitInsn mv Opcodes/LADD)
    [:sub :i64] (.visitInsn mv Opcodes/LSUB)
    [:mul :i64] (.visitInsn mv Opcodes/LMUL)
    [:div :i64] (.visitInsn mv Opcodes/LDIV)
    [:add :f64] (.visitInsn mv Opcodes/DADD)
    [:sub :f64] (.visitInsn mv Opcodes/DSUB)
    [:mul :f64] (.visitInsn mv Opcodes/DMUL)
    [:div :f64] (.visitInsn mv Opcodes/DDIV)
    (throw (ex-info "Unknown binary op" {:op op :type type}))))

;; ── Comparison ──────────────────────────────────────────────

(defn- emit-i32-comparison
  "Emit an i32 comparison that leaves 0 or 1 on the stack."
  [^MethodVisitor mv op]
  (let [true-label (Label.)
        end-label (Label.)
        if-op (case op
                "eq" Opcodes/IF_ICMPEQ
                "ne" Opcodes/IF_ICMPNE
                "lt" Opcodes/IF_ICMPLT
                "le" Opcodes/IF_ICMPLE
                "gt" Opcodes/IF_ICMPGT
                "ge" Opcodes/IF_ICMPGE)]
    (.visitJumpInsn mv if-op true-label)
    (.visitInsn mv Opcodes/ICONST_0)
    (.visitJumpInsn mv Opcodes/GOTO end-label)
    (.visitLabel mv true-label)
    (.visitInsn mv Opcodes/ICONST_1)
    (.visitLabel mv end-label)))

;; ── Var name helpers ────────────────────────────────────────

(defn- clean-name
  "Strip $ or % prefix from ARIA var name."
  [name]
  (cond
    (str/starts-with? name "$") (subs name 1)
    (str/starts-with? name "%") (subs name 1)
    :else name))

;; ── Code generation context ─────────────────────────────────

(defn- make-ctx
  "Create a code generation context for a method."
  [class-name functions]
  {:class-name class-name
   :functions functions        ; map of func-name -> func AST
   :locals (atom {})           ; var-name -> {:slot int :type keyword}
   :next-slot (atom 0)
   :loop-labels (atom {})})    ; loop-label -> Label (start of loop)

(defn- alloc-local!
  "Allocate a local variable slot. Wide types (i64/f64) take 2 slots."
  [ctx var-name type-kw]
  (let [slot @(:next-slot ctx)]
    (swap! (:locals ctx) assoc var-name {:slot slot :type type-kw})
    (swap! (:next-slot ctx) + (if (wide-type? type-kw) 2 1))
    slot))

(defn- get-local [ctx var-name]
  (get @(:locals ctx) var-name))

;; ── Expression Codegen ──────────────────────────────────────

(declare emit-expr!)
(declare emit-stmt!)
(declare emit-print!)

(defn- emit-expr!
  "Emit bytecode that pushes the expression's value onto the stack."
  [^MethodVisitor mv ctx node]
  (case (:node/type node)
    :int-literal
    (let [v (long (:value node))]
      (cond
        (<= -1 v 5)    (.visitInsn mv (+ Opcodes/ICONST_0 (int v)))
        (<= -128 v 127) (.visitIntInsn mv Opcodes/BIPUSH (int v))
        (<= -32768 v 32767) (.visitIntInsn mv Opcodes/SIPUSH (int v))
        :else           (.visitLdcInsn mv (int v))))

    :float-literal
    (.visitLdcInsn mv (double (:value node)))

    :bool-literal
    (if (:value node)
      (.visitInsn mv Opcodes/ICONST_1)
      (.visitInsn mv Opcodes/ICONST_0))

    :string-literal
    (.visitLdcInsn mv (:value node))

    :var-ref
    (let [var-name (:name node)
          local (get-local ctx var-name)]
      (when-not local
        (throw (ex-info "Undefined variable in JVM codegen" {:name var-name})))
      (.visitVarInsn mv (aria-type->load-op (:type local)) (:slot local)))

    :bin-op
    (let [type-kw (type-suffix->kw (:type-suffix node))]
      (emit-expr! mv ctx (:left node))
      (emit-expr! mv ctx (:right node))
      (emit-binop mv {:op (keyword (:op node)) :type type-kw}))

    :comparison
    (let [type-kw (type-suffix->kw (:type-suffix node))]
      (emit-expr! mv ctx (:left node))
      (emit-expr! mv ctx (:right node))
      (case type-kw
        :i32 (emit-i32-comparison mv (:op node))))

    :call
    (let [target (:target node)
          func (get (:functions ctx) target)]
      (when-not func
        (throw (ex-info "Undefined function in JVM codegen" {:target target})))
      ;; Push arguments
      (doseq [arg (:args node)]
        (emit-expr! mv ctx arg))
      ;; Build descriptor
      (let [param-descs (str/join ""
                          (map (fn [p] (aria-type->descriptor
                                        (aria-type-map->kw (:param/type p))))
                               (:params func)))
            ret-desc (if (:result func)
                       (aria-type->descriptor (aria-type-map->kw (:result func)))
                       "V")
            desc (str "(" param-descs ")" ret-desc)]
        (.visitMethodInsn mv Opcodes/INVOKESTATIC
                          (:class-name ctx)
                          (clean-name target)
                          desc
                          false)))

    ;; If used as expression (single then/else producing a value)
    :if
    (let [else-label (Label.)
          end-label (Label.)]
      ;; Emit condition
      (emit-expr! mv ctx (:cond node))
      ;; Branch if false (0)
      (.visitJumpInsn mv Opcodes/IFEQ else-label)
      ;; Then body
      (doseq [n (butlast (:then-body node))]
        (emit-stmt! mv ctx n))
      (when (seq (:then-body node))
        (let [last-node (last (:then-body node))]
          (if (= :return (:node/type last-node))
            (emit-stmt! mv ctx last-node)
            (emit-expr! mv ctx last-node))))
      (.visitJumpInsn mv Opcodes/GOTO end-label)
      ;; Else body
      (.visitLabel mv else-label)
      (when (seq (:else-body node))
        (doseq [n (butlast (:else-body node))]
          (emit-stmt! mv ctx n))
        (let [last-node (last (:else-body node))]
          (if (= :return (:node/type last-node))
            (emit-stmt! mv ctx last-node)
            (emit-expr! mv ctx last-node))))
      (.visitLabel mv end-label))

    (throw (ex-info "Unknown expression node in JVM codegen" {:node/type (:node/type node)}))))

;; ── Statement Codegen ───────────────────────────────────────

(defn- emit-stmt!
  "Emit bytecode for a statement node."
  [^MethodVisitor mv ctx node]
  (case (:node/type node)
    :intent nil ;; Intent annotations are no-ops in Phase 1

    :let
    (let [type-kw (aria-type-map->kw (:aria/type node))
          slot (alloc-local! ctx (:name node) type-kw)]
      (emit-expr! mv ctx (:value node))
      (.visitVarInsn mv (aria-type->store-op type-kw) slot))

    :set-var
    (let [var-name (:name node)
          local (get-local ctx var-name)]
      (when-not local
        (throw (ex-info "Undefined variable in set-var" {:name var-name})))
      (emit-expr! mv ctx (:value node))
      (.visitVarInsn mv (aria-type->store-op (:type local)) (:slot local)))

    :return
    (if (:value node)
      (do (emit-expr! mv ctx (:value node))
          (let [ret-type (or (:return-type (meta ctx)) :i32)]
            (.visitInsn mv (aria-type->return-op ret-type))))
      (.visitInsn mv Opcodes/RETURN))

    :if
    (let [else-label (Label.)
          end-label (Label.)]
      (emit-expr! mv ctx (:cond node))
      (.visitJumpInsn mv Opcodes/IFEQ else-label)
      (doseq [n (:then-body node)]
        (emit-stmt! mv ctx n))
      (.visitJumpInsn mv Opcodes/GOTO end-label)
      (.visitLabel mv else-label)
      (doseq [n (:else-body node)]
        (emit-stmt! mv ctx n))
      (.visitLabel mv end-label))

    :loop
    (let [start-label (Label.)
          end-label (Label.)]
      (when (seq (:label node))
        (swap! (:loop-labels ctx) assoc (:label node) {:start start-label :end end-label}))
      (.visitLabel mv start-label)
      (doseq [n (:body node)]
        (emit-stmt! mv ctx n))
      (.visitJumpInsn mv Opcodes/GOTO start-label)
      (.visitLabel mv end-label))

    :branch
    (if (nil? (:cond node))
      ;; Unconditional break
      (let [labels (get @(:loop-labels ctx) (:label node))]
        (.visitJumpInsn mv Opcodes/GOTO (:end labels)))
      ;; Conditional break
      (let [labels (get @(:loop-labels ctx) (:label node))]
        (emit-expr! mv ctx (:cond node))
        (.visitJumpInsn mv Opcodes/IFNE (:end labels))))

    :print
    (emit-print! mv ctx node)

    :call
    (do (emit-expr! mv ctx node)
        ;; Pop return value if the call is used as a statement
        (let [func (get (:functions ctx) (:target node))]
          (when (and func (:result func))
            (let [type-kw (aria-type-map->kw (:result func))]
              (if (wide-type? type-kw)
                (.visitInsn mv Opcodes/POP2)
                (.visitInsn mv Opcodes/POP))))))

    :seq
    (doseq [n (:body node)]
      (emit-stmt! mv ctx n))

    ;; Default: try as expression statement
    (emit-expr! mv ctx node)))

;; ── Print codegen ───────────────────────────────────────────

(defn- emit-print!
  "Emit bytecode for a print statement using System.out.printf."
  [^MethodVisitor mv ctx node]
  (let [fmt (:format-str node)
        args (:args node)]
    ;; Get System.out
    (.visitFieldInsn mv Opcodes/GETSTATIC "java/lang/System" "out" "Ljava/io/PrintStream;")
    ;; Push format string
    (.visitLdcInsn mv fmt)
    ;; Create Object[] for varargs
    (.visitLdcInsn mv (int (count args)))
    (.visitTypeInsn mv Opcodes/ANEWARRAY "java/lang/Object")
    ;; Fill array
    (doseq [[i arg] (map-indexed vector args)]
      (.visitInsn mv Opcodes/DUP)
      (.visitLdcInsn mv (int i))
      (emit-expr! mv ctx arg)
      ;; Box primitive
      (.visitMethodInsn mv Opcodes/INVOKESTATIC
                        "java/lang/Integer" "valueOf"
                        "(I)Ljava/lang/Integer;" false)
      (.visitInsn mv Opcodes/AASTORE))
    ;; Call printf
    (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL
                      "java/io/PrintStream" "printf"
                      "(Ljava/lang/String;[Ljava/lang/Object;)Ljava/io/PrintStream;"
                      false)
    ;; Pop the returned PrintStream
    (.visitInsn mv Opcodes/POP)))

;; ── @Intent Annotation ──────────────────────────────────────

(defn- generate-intent-annotation-class
  "Generates the bytecode for the @com.ariacompiler.Intent annotation type.
  Returns a byte array containing a valid .class file for the annotation.
  This class must be present on the classpath or in the JAR for @Intent
  to be queryable via reflection at runtime."
  []
  (let [cw (ClassWriter. 0)]
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
    (let [mv (.visitMethod cw (+ Opcodes/ACC_PUBLIC Opcodes/ACC_ABSTRACT)
                           "value" "()Ljava/lang/String;" nil nil)]
      (.visitEnd mv))
    (.visitEnd cw)
    (.toByteArray cw)))

(defn- extract-intent
  "Returns the intent string from a function, or nil if none declared.
  The parser stores intent in the function's :intent key directly."
  [func]
  (:intent func))

;; ── Method Codegen ──────────────────────────────────────────

(defn- emit-method!
  "Emit a single ARIA function as a static JVM method."
  [^ClassWriter cw class-name functions func]
  (let [fname (clean-name (:name func))
        params (:params func)
        result (:result func)
        param-descs (str/join ""
                      (map (fn [p] (aria-type->descriptor
                                    (aria-type-map->kw (:param/type p))))
                           params))
        ret-kw (if result (aria-type-map->kw result) :void)
        ret-desc (aria-type->descriptor ret-kw)
        desc (str "(" param-descs ")" ret-desc)
        access (+ Opcodes/ACC_PUBLIC Opcodes/ACC_STATIC)
        mv (.visitMethod cw access fname desc nil nil)
        ctx (make-ctx class-name functions)]
    (.visitCode mv)
    ;; Emit @Intent annotation if present
    (when-let [intent-str (extract-intent func)]
      (let [av (.visitAnnotation mv "Lcom/ariacompiler/Intent;" true)]
        (.visit av "value" intent-str)
        (.visitEnd av)))
    ;; Allocate parameter slots
    (doseq [p params]
      (let [type-kw (aria-type-map->kw (:param/type p))
            pname (:param/name p)]
        (alloc-local! ctx pname type-kw)))
    ;; Emit body statements
    (doseq [node (:body func)]
      (emit-stmt! mv (with-meta ctx {:return-type ret-kw}) node))
    ;; Add a safety return in case body doesn't end with return
    (when (= ret-kw :void)
      (.visitInsn mv Opcodes/RETURN))
    ;; Let ASM compute the max stack/locals
    (.visitMaxs mv 0 0)
    (.visitEnd mv)))

;; ── Main method (entry point) ───────────────────────────────

(defn- emit-main-wrapper!
  "Emit a JVM main(String[]) that calls the ARIA $main function."
  [^ClassWriter cw class-name functions main-func]
  (let [mv (.visitMethod cw
                         (+ Opcodes/ACC_PUBLIC Opcodes/ACC_STATIC)
                         "main"
                         "([Ljava/lang/String;)V"
                         nil nil)]
    (.visitCode mv)
    ;; Call the ARIA main function
    (let [result (:result main-func)
          ret-kw (if result (aria-type-map->kw result) :void)
          ret-desc (aria-type->descriptor ret-kw)]
      (.visitMethodInsn mv Opcodes/INVOKESTATIC
                        class-name "main"
                        (str "()" ret-desc) false)
      ;; Discard return value if any
      (when (not= ret-kw :void)
        (if (wide-type? ret-kw)
          (.visitInsn mv Opcodes/POP2)
          (.visitInsn mv Opcodes/POP))))
    (.visitInsn mv Opcodes/RETURN)
    (.visitMaxs mv 0 0)
    (.visitEnd mv)))

;; ── Public API ──────────────────────────────────────────────

(defn generate-class
  "Compiles a validated ARIA-IR AST to JVM bytecode.
  Returns a byte array containing a valid .class file.
  class-name must be a valid JVM binary name (e.g. \"Fibonacci\").
  The AST must have passed aria.checker/check before calling this."
  [ast class-name]
  (let [cw (ClassWriter. ClassWriter/COMPUTE_FRAMES)
        functions (into {} (map (fn [f] [(:name f) f]) (:functions ast)))]
    (.visit cw
            Opcodes/V11
            (+ Opcodes/ACC_PUBLIC Opcodes/ACC_SUPER)
            class-name nil "java/lang/Object" nil)
    ;; Emit all functions as static methods
    (doseq [func (:functions ast)]
      (emit-method! cw class-name functions func))
    ;; If there's a $main function, emit a JVM main(String[]) wrapper
    (when-let [main-func (get functions "$main")]
      (emit-main-wrapper! cw class-name functions main-func))
    (.visitEnd cw)
    (.toByteArray cw)))

(defn emit-class-file!
  "Writes a compiled .class file to output-dir.
  Creates output-dir if it does not exist.
  Returns the java.io.File written."
  [ast output-dir]
  (let [module-name (:name ast)
        class-name (str (Character/toUpperCase (first module-name))
                        (subs module-name 1))
        bytes (generate-class ast class-name)
        dir (java.io.File. output-dir)
        out-file (java.io.File. dir (str class-name ".class"))]
    (.mkdirs dir)
    (with-open [os (java.io.FileOutputStream. out-file)]
      (.write os ^bytes bytes))
    (println (str "Wrote " (.getPath out-file) " (" (count bytes) " bytes)"))
    out-file))

(defn emit-jar!
  "Compiles an ARIA-IR AST to a runnable JAR file in output-dir.
  The JAR includes:
    - The compiled ARIA module as a .class file
    - The com.ariacompiler.Intent annotation class
    - A manifest with Main-Class set to the compiled class name
  Returns the java.io.File written."
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
