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
  "Convert AST type map {:type/kind :primitive :type/name \"i32\"} to keyword.
  Pointer types map to :i32 since pointers are int indices into AriaMemory."
  [t]
  (when t
    (case (:type/kind t)
      :primitive (keyword (:type/name t))
      :ptr       :i32
      (throw (ex-info "Unknown type kind in JVM backend" {:type t})))))

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
    [:rem :i32] (.visitInsn mv Opcodes/IREM)
    [:add :i64] (.visitInsn mv Opcodes/LADD)
    [:sub :i64] (.visitInsn mv Opcodes/LSUB)
    [:mul :i64] (.visitInsn mv Opcodes/LMUL)
    [:div :i64] (.visitInsn mv Opcodes/LDIV)
    [:rem :i64] (.visitInsn mv Opcodes/LREM)
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
(declare infer-expr-type)

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
        (:i32 :bool) (emit-i32-comparison mv (:op node))))

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

    :cast
    (let [from-kw (keyword (:type/name (:from-type node)))
          to-kw   (keyword (:type/name (:to-type node)))]
      (emit-expr! mv ctx (:value node))
      (case [from-kw to-kw]
        [:i32 :i64] (.visitInsn mv Opcodes/I2L)
        [:i64 :i32] (.visitInsn mv Opcodes/L2I)
        [:i32 :f64] (.visitInsn mv Opcodes/I2D)
        [:f64 :i32] (.visitInsn mv Opcodes/D2I)
        [:i64 :f64] (.visitInsn mv Opcodes/L2D)
        [:f64 :i64] (.visitInsn mv Opcodes/D2L)
        (throw (ex-info "Unknown cast" {:from from-kw :to to-kw}))))

    :alloc
    (do (if (:count node)
          (emit-expr! mv ctx (:count node))
          (.visitInsn mv Opcodes/ICONST_1))
        (.visitMethodInsn mv Opcodes/INVOKESTATIC
                          "com/ariacompiler/AriaMemory"
                          "alloc" "(I)I" false))

    :load
    (do (emit-expr! mv ctx (:ptr node))
        (.visitMethodInsn mv Opcodes/INVOKESTATIC
                          "com/ariacompiler/AriaMemory"
                          "load" "(I)I" false))

    (throw (ex-info "Unknown expression node in JVM codegen" {:node/type (:node/type node)}))))

;; ── Statement Codegen ───────────────────────────────────────

(defn- emit-stmt!
  "Emit bytecode for a statement node."
  [^MethodVisitor mv ctx node]
  (case (:node/type node)
    :intent nil ;; Intent annotations are no-ops in Phase 1

    :let
    (let [type-kw (aria-type-map->kw (:aria/type node))
          slot (alloc-local! ctx (:name node) type-kw)
          val-type (infer-expr-type ctx (:value node))]
      (emit-expr! mv ctx (:value node))
      ;; Widen i32 to i64 if the declared type is i64
      (when (and (= type-kw :i64) (= val-type :i32))
        (.visitInsn mv Opcodes/I2L))
      (.visitVarInsn mv (aria-type->store-op type-kw) slot))

    :set-var
    (let [var-name (:name node)
          local (get-local ctx var-name)]
      (when-not local
        (throw (ex-info "Undefined variable in set-var" {:name var-name})))
      (let [val-type (infer-expr-type ctx (:value node))]
        (emit-expr! mv ctx (:value node))
        ;; Widen i32 to i64 if the local is i64
        (when (and (= (:type local) :i64) (= val-type :i32))
          (.visitInsn mv Opcodes/I2L))
        (.visitVarInsn mv (aria-type->store-op (:type local)) (:slot local))))

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

    :store
    (do (emit-expr! mv ctx (:ptr node))
        (emit-expr! mv ctx (:value node))
        (.visitMethodInsn mv Opcodes/INVOKESTATIC
                          "com/ariacompiler/AriaMemory"
                          "store" "(II)V" false))

    :free
    (do (emit-expr! mv ctx (:ptr node))
        (.visitMethodInsn mv Opcodes/INVOKESTATIC
                          "com/ariacompiler/AriaMemory"
                          "free" "(I)V" false))

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

(defn- infer-expr-type
  "Best-effort type inference for an expression.
  Returns the keyword type (:i32, :i64, :f64, :bool, :str)."
  [ctx node]
  (case (:node/type node)
    :var-ref (let [local (get-local ctx (:name node))]
               (if local (:type local) :i32))
    :int-literal :i32
    :float-literal :f64
    :bool-literal :bool
    :string-literal :str
    :bin-op (type-suffix->kw (:type-suffix node))
    :comparison :i32
    :call (let [func (get (:functions ctx) (:target node))]
            (if (and func (:result func))
              (aria-type-map->kw (:result func))
              :i32))
    :cast (keyword (:type/name (:to-type node)))
    :alloc :i32
    :load :i32
    :i32))

(defn- emit-box!
  "Emit boxing bytecode for a primitive value on the stack."
  [^MethodVisitor mv type-kw]
  (case type-kw
    :i64 (.visitMethodInsn mv Opcodes/INVOKESTATIC
                           "java/lang/Long" "valueOf"
                           "(J)Ljava/lang/Long;" false)
    :f64 (.visitMethodInsn mv Opcodes/INVOKESTATIC
                           "java/lang/Double" "valueOf"
                           "(D)Ljava/lang/Double;" false)
    ;; default: box as Integer (covers i32, bool)
    (.visitMethodInsn mv Opcodes/INVOKESTATIC
                      "java/lang/Integer" "valueOf"
                      "(I)Ljava/lang/Integer;" false)))

(defn- c-format->java-format
  "Convert C-style format specifiers to Java-compatible ones.
  Java's printf uses %d for both int and long, not %ld."
  [fmt]
  (-> fmt
      (str/replace "%ld" "%d")
      (str/replace "%lu" "%d")
      (str/replace "%lf" "%f")))

(defn- emit-print!
  "Emit bytecode for a print statement using System.out.printf."
  [^MethodVisitor mv ctx node]
  (let [fmt (c-format->java-format (:format-str node))
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
      ;; Box based on inferred type
      (emit-box! mv (infer-expr-type ctx arg))
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

;; ── AriaMemory Runtime ──────────────────────────────────────

(defn- generate-aria-memory-class
  "Generates bytecode for the AriaMemory runtime support class.
  This class implements the ARIA heap as a flat int array.
  Methods: alloc(int count) -> int, load(int addr) -> int,
           store(int addr, int val) -> void, free(int addr) -> void.
  Returns a byte array containing a valid .class file."
  []
  (let [cw (ClassWriter. ClassWriter/COMPUTE_FRAMES)]
    (.visit cw Opcodes/V11
            (+ Opcodes/ACC_PUBLIC Opcodes/ACC_SUPER)
            "com/ariacompiler/AriaMemory" nil "java/lang/Object" nil)

    ;; Static field: int[] heap
    (.visitField cw (+ Opcodes/ACC_PRIVATE Opcodes/ACC_STATIC)
                 "heap" "[I" nil nil)

    ;; Static field: int next (next free index)
    (.visitField cw (+ Opcodes/ACC_PRIVATE Opcodes/ACC_STATIC)
                 "next" "I" nil nil)

    ;; Static initializer: heap = new int[65536]; next = 0;
    (let [mv (.visitMethod cw Opcodes/ACC_STATIC "<clinit>" "()V" nil nil)]
      (.visitCode mv)
      (.visitLdcInsn mv (int 65536))
      (.visitIntInsn mv Opcodes/NEWARRAY Opcodes/T_INT)
      (.visitFieldInsn mv Opcodes/PUTSTATIC "com/ariacompiler/AriaMemory" "heap" "[I")
      (.visitInsn mv Opcodes/ICONST_0)
      (.visitFieldInsn mv Opcodes/PUTSTATIC "com/ariacompiler/AriaMemory" "next" "I")
      (.visitInsn mv Opcodes/RETURN)
      (.visitMaxs mv 0 0)
      (.visitEnd mv))

    ;; public static int alloc(int count)
    (let [mv (.visitMethod cw (+ Opcodes/ACC_PUBLIC Opcodes/ACC_STATIC)
                           "alloc" "(I)I" nil nil)]
      (.visitCode mv)
      ;; int addr = next;
      (.visitFieldInsn mv Opcodes/GETSTATIC "com/ariacompiler/AriaMemory" "next" "I")
      (.visitVarInsn mv Opcodes/ISTORE 1)
      ;; next = next + count;
      (.visitFieldInsn mv Opcodes/GETSTATIC "com/ariacompiler/AriaMemory" "next" "I")
      (.visitVarInsn mv Opcodes/ILOAD 0)
      (.visitInsn mv Opcodes/IADD)
      (.visitFieldInsn mv Opcodes/PUTSTATIC "com/ariacompiler/AriaMemory" "next" "I")
      ;; return addr;
      (.visitVarInsn mv Opcodes/ILOAD 1)
      (.visitInsn mv Opcodes/IRETURN)
      (.visitMaxs mv 0 0)
      (.visitEnd mv))

    ;; public static int load(int addr)
    (let [mv (.visitMethod cw (+ Opcodes/ACC_PUBLIC Opcodes/ACC_STATIC)
                           "load" "(I)I" nil nil)]
      (.visitCode mv)
      (.visitFieldInsn mv Opcodes/GETSTATIC "com/ariacompiler/AriaMemory" "heap" "[I")
      (.visitVarInsn mv Opcodes/ILOAD 0)
      (.visitInsn mv Opcodes/IALOAD)
      (.visitInsn mv Opcodes/IRETURN)
      (.visitMaxs mv 0 0)
      (.visitEnd mv))

    ;; public static void store(int addr, int val)
    (let [mv (.visitMethod cw (+ Opcodes/ACC_PUBLIC Opcodes/ACC_STATIC)
                           "store" "(II)V" nil nil)]
      (.visitCode mv)
      (.visitFieldInsn mv Opcodes/GETSTATIC "com/ariacompiler/AriaMemory" "heap" "[I")
      (.visitVarInsn mv Opcodes/ILOAD 0)
      (.visitVarInsn mv Opcodes/ILOAD 1)
      (.visitInsn mv Opcodes/IASTORE)
      (.visitInsn mv Opcodes/RETURN)
      (.visitMaxs mv 0 0)
      (.visitEnd mv))

    ;; public static void free(int addr) -- no-op
    (let [mv (.visitMethod cw (+ Opcodes/ACC_PUBLIC Opcodes/ACC_STATIC)
                           "free" "(I)V" nil nil)]
      (.visitCode mv)
      (.visitInsn mv Opcodes/RETURN)
      (.visitMaxs mv 0 0)
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
  Also writes AriaMemory and Intent support classes.
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
    ;; Write AriaMemory support class
    (let [mem-dir (java.io.File. dir "com/ariacompiler")
          mem-file (java.io.File. mem-dir "AriaMemory.class")
          mem-bytes (generate-aria-memory-class)]
      (.mkdirs mem-dir)
      (with-open [os (java.io.FileOutputStream. mem-file)]
        (.write os ^bytes mem-bytes)))
    ;; Write Intent annotation class
    (let [intent-dir (java.io.File. dir "com/ariacompiler")
          intent-file (java.io.File. intent-dir "Intent.class")
          intent-bytes (generate-intent-annotation-class)]
      (.mkdirs intent-dir)
      (with-open [os (java.io.FileOutputStream. intent-file)]
        (.write os ^bytes intent-bytes)))
    (println (str "Wrote " (.getPath out-file) " (" (count bytes) " bytes)"))
    out-file))

(defn emit-jar!
  "Compiles an ARIA-IR AST to a runnable JAR file in output-dir.
  The JAR includes:
    - The compiled ARIA module as a .class file
    - The com.ariacompiler.Intent annotation class
    - The com.ariacompiler.AriaMemory runtime class
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
      ;; Write the AriaMemory runtime class
      (let [mem-bytes (generate-aria-memory-class)]
        (.putNextEntry jos (java.util.jar.JarEntry.
                            "com/ariacompiler/AriaMemory.class"))
        (.write jos ^bytes mem-bytes)
        (.closeEntry jos))
      ;; Write the compiled ARIA module class
      (.putNextEntry jos (java.util.jar.JarEntry.
                          (str class-name ".class")))
      (.write jos ^bytes class-bytes)
      (.closeEntry jos))
    (println (str "Wrote " (.getPath jar-file)
                  " (" (.length jar-file) " bytes)"))
    jar-file))
