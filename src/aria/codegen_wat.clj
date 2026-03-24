(ns aria.codegen-wat
  "ARIA-WAT Transpiler — compiles ARIA AST to WebAssembly Text format.

   The generated WAT can be assembled with wat2wasm (WABT) and optimized
   with wasm-opt (Binaryen).
   Pipeline: AI -> ARIA-IR -> WAT -> wat2wasm -> .wasm -> wasm-opt -> .wasm"
  (:require [aria.ast :as ast]
            [clojure.string :as str]))

;; ── State ─────────────────────────────────────────────────────

(defn- make-codegen []
  {:indent        (atom 0)
   :output        (atom [])
   :locals        (atom {})           ;; var-name -> wat-type (deduped)
   :local-types   (atom {})           ;; aria-name -> aria-type-map (for ptr arithmetic)
   :data-segments (atom [])           ;; [{:offset N :bytes "escaped"}]
   :data-offset   (atom 1024)         ;; next available data offset
   :needs-alloc   (atom false)        ;; emit $__alloc function?
   :needs-print   (atom false)        ;; emit print imports?
   :needs-fmt-buf (atom false)        ;; emit format buffer globals + flush?
   :needs-f64-prec (atom false)       ;; emit $__print_f64_p (precision variant)?
   :print-types   (atom #{})          ;; #{:i32 :i64 :f64}
   :func-sigs     (atom {})           ;; func-name -> {:result type-map}
   :struct-types  (atom {})           ;; struct-name -> struct type definition
   :global-names  (atom {})           ;; global-name -> {:wat-type str :aria-type map :mutable? bool}
   })         ;; func-name -> {:result type-map}

(defn- indent-str [cg]
  (apply str (repeat @(:indent cg) "  ")))

(defn- emit! [cg line]
  (swap! (:output cg) conj (str (indent-str cg) line)))

(defn- emit-raw! [cg line]
  (swap! (:output cg) conj line))

;; ── Name Mapping ────────────────────────────────────────────

(defn- var->wat
  "Convert ARIA variable name ($foo, %bar) to valid WAT identifier ($foo, $bar)."
  [name]
  (str "$" (-> name
               (str/replace #"^[$%]" "")
               (str/replace "." "_")
               (str/replace "-" "_"))))

;; ── Type Mapping ────────────────────────────────────────────

(defn- type->wat
  "Map ARIA type to WAT value type. Returns nil for void."
  [t]
  (when t
    (case (:type/kind t)
      :primitive (case (:type/name t)
                   ("i64" "u64") "i64"
                   ("f32") "f32"
                   ("f64") "f64"
                   ("void") nil
                   "i32")
      :ptr "i32"
      :array "i32"
      :slice "i32"
      nil)))

(defn- suffix->wat
  "Map ARIA type suffix to WAT type."
  [suffix]
  (case suffix
    ("i64" "u64") "i64"
    ("f32") "f32"
    ("f64") "f64"
    "i32"))

(defn- suffix->signed?
  "Is this type suffix signed?"
  [suffix]
  (not (contains? #{"u8" "u16" "u32" "u64" "bool"} suffix)))

(defn- align-to
  "Round `offset` up to the nearest multiple of `alignment`."
  [offset alignment]
  (let [rem (mod offset alignment)]
    (if (zero? rem) offset (+ offset (- alignment rem)))))

(defn- type-alignment
  "Natural alignment of an ARIA type, capped at 4 for WASM32."
  [t]
  (min 4
       (case (:type/kind t)
         :primitive (case (:type/name t)
                      ("i64" "u64" "f64") 8
                      ("i16" "u16") 2
                      ("i8" "u8" "bool" "i1") 1
                      4)
         :ptr 4
         4)))

(defn- type-size
  "Size in bytes of an ARIA type."
  [t]
  (case (:type/kind t)
    :primitive (case (:type/name t)
                 ("i64" "u64" "f64") 8
                 ("i16" "u16") 2
                 ("i8" "u8" "bool" "i1") 1
                 4)
    :ptr 4
    :array (* (type-size (:type/elem t)) (or (:type/size t) 1))
    :struct (reduce (fn [offset [_ field-type]]
                      (let [aligned (align-to offset (type-alignment field-type))]
                        (+ aligned (type-size field-type))))
                    0 (:type/fields t))
    4))

(defn- compute-field-offset
  "Compute the byte offset and WAT type for a named field in a struct type.
   Returns {:offset N :wat-type \"i32\"} or nil if field not found."
  [struct-type field-name]
  (loop [fields (:type/fields struct-type)
         offset 0]
    (when (seq fields)
      (let [[fname ftype] (first fields)
            aligned (align-to offset (type-alignment ftype))]
        (if (= fname field-name)
          {:offset aligned :wat-type (or (type->wat ftype) "i32")}
          (recur (rest fields)
                 (+ aligned (type-size ftype))))))))

;; ── Global Helpers ──────────────────────────────────────────

(defn- default-init-value
  "Return the WAT const expression for the zero value of a WAT type."
  [wat-type]
  (case wat-type
    "i32" "(i32.const 0)"
    "i64" "(i64.const 0)"
    "f32" "(f32.const 0)"
    "f64" "(f64.const 0)"
    "(i32.const 0)"))

(defn- global-init-expr
  "Emit the init expression for a global. Uses literal init if available, else zero."
  [global wat-type]
  (if-let [init (:init global)]
    (case (:node/type init)
      :int-literal  (str "(" wat-type ".const " (:value init) ")")
      :float-literal (str "(" wat-type ".const " (:value init) ")")
      :bool-literal (str "(" wat-type ".const " (if (:value init) 1 0) ")")
      (default-init-value wat-type))
    (default-init-value wat-type)))

;; ── String Interning ─────────────────────────────────────────

(defn- escape-wat-string
  "Escape a string for WAT data segment."
  [s]
  (let [sb (StringBuilder.)]
    (doseq [c s]
      (case c
        \newline (.append sb "\\0a")
        \tab     (.append sb "\\09")
        \return  (.append sb "\\0d")
        \"       (.append sb "\\22")
        \\       (.append sb "\\5c")
        (.append sb c)))
    (str sb)))

(defn- intern-string!
  "Intern a string literal in the data section. Returns {:ptr offset :len bytes}."
  [cg s]
  (let [bytes (.getBytes ^String s "UTF-8")
        len (alength bytes)
        offset @(:data-offset cg)]
    (swap! (:data-segments cg) conj {:offset offset :bytes (escape-wat-string s)})
    (swap! (:data-offset cg) + len)
    {:ptr offset :len len}))

;; ── Format String Parsing ────────────────────────────────────

(defn- split-format-string
  "Split a C-style format string into segments.
   Parses flags (-+0# ), width, .precision, length modifier (l), and conversion.
   Returns [{:type :literal :text \"...\"} {:type :placeholder :spec \"d\"} ...]"
  [fmt-str]
  (let [segments (atom [])
        buf (StringBuilder.)
        len (count fmt-str)
        flush-buf! (fn []
                     (when (pos? (.length buf))
                       (swap! segments conj {:type :literal :text (str buf)})
                       (.setLength buf 0)))]
    (loop [i 0]
      (if (>= i len)
        (do (flush-buf!)
            @segments)
        (let [c (.charAt ^String fmt-str i)]
          (if (and (= c \%) (< (inc i) len))
            (let [next-c (.charAt ^String fmt-str (inc i))]
              (if (= next-c \%)
                ;; Escaped %%
                (do (.append buf \%)
                    (recur (+ i 2)))
                ;; Parse full format spec: %[flags][width][.prec][length]conversion
                (do (flush-buf!)
                    (let [end-i (loop [j (inc i)
                                       spec-buf (StringBuilder.)]
                                  (if (>= j len)
                                    ;; Unterminated format spec, treat as literal
                                    (do (swap! segments conj {:type :literal :text (str "%" spec-buf)})
                                        j)
                                    (let [ch (.charAt ^String fmt-str j)
                                          conversion-char? (contains? #{\d \u \x \X \o \f \s \c \p \i \n \e \E \g \G} ch)]
                                      (if conversion-char?
                                        ;; Found conversion character
                                        (let [full-spec (str spec-buf ch)
                                              conversion (cond
                                                           (and (>= (.length spec-buf) 1)
                                                                (= \l (.charAt spec-buf (dec (.length spec-buf)))))
                                                           (str "l" ch)
                                                           :else (str ch))]
                                          (swap! segments conj {:type :placeholder :spec conversion :full-spec full-spec})
                                          (inc j))
                                        ;; flags, width, precision, length modifier (l, h, etc.) — accumulate
                                        (recur (inc j) (.append spec-buf ch))))))]
                      (recur end-i)))))
            (do (.append buf c)
                (recur (inc i)))))))))

(defn- parse-format-modifiers
  "Parse width, precision, and flags from a format full-spec string.
   E.g., \"05d\" -> {:width 5 :zero-pad? true :left-align? false :precision nil :conversion \"d\"}
         \"10.2f\" -> {:width 10 :zero-pad? false :left-align? false :precision 2 :conversion \"f\"}
         \"-20s\" -> {:width 20 :zero-pad? false :left-align? true :precision nil :conversion \"s\"}"
  [full-spec]
  (let [len (count full-spec)
        ;; The last char (or last 2 for 'ld'/'lu'/'lf') is the conversion
        conversion (cond
                     (and (>= len 2) (= \l (.charAt ^String full-spec (- len 2))))
                     (subs full-spec (- len 2))
                     :else (subs full-spec (dec len)))
        ;; Everything before conversion is flags+width+precision
        prefix (subs full-spec 0 (- len (count conversion)))]
    (if (empty? prefix)
      nil ;; no modifiers
      (let [;; Parse flags
            left-align? (str/starts-with? prefix "-")
            prefix (if left-align? (subs prefix 1) prefix)
            zero-pad? (and (not left-align?) (str/starts-with? prefix "0"))
            prefix (if (and zero-pad? (not (empty? prefix)) (= \0 (.charAt ^String prefix 0)))
                     (subs prefix 1) prefix)
            ;; Split on '.' for width.precision
            [width-str prec-str] (if (str/includes? prefix ".")
                                   (str/split prefix #"\." 2)
                                   [prefix nil])
            width (when (and width-str (not (empty? width-str)))
                    (parse-long width-str))
            precision (when (and prec-str (not (empty? prec-str)))
                        (parse-long prec-str))]
        (when (or width precision)
          {:width width
           :precision precision
           :zero-pad? zero-pad?
           :left-align? left-align?
           :conversion conversion})))))

(defn- placeholder->print-func
  "Map a format placeholder spec to the WAT print function name and type keyword."
  [spec]
  (case spec
    "d"  {:func "$__print_i32" :type-kw :i32}
    "u"  {:func "$__print_u32" :type-kw :u32}
    "x"  {:func "$__print_hex" :type-kw :hex}
    "X"  {:func "$__print_HEX" :type-kw :HEX}
    "o"  {:func "$__print_oct" :type-kw :oct}
    "ld" {:func "$__print_i64" :type-kw :i64}
    "lu" {:func "$__print_u64" :type-kw :u64}
    "f"  {:func "$__print_f64" :type-kw :f64}
    "lf" {:func "$__print_f64" :type-kw :f64}
    "s"  {:func "$__print"     :type-kw :str}
    "c"  {:func "$__print_char" :type-kw :char}
    "p"  {:func "$__print_ptr"  :type-kw :ptr}
    "e"  {:func "$__print_f64e" :type-kw :f64e}
    "E"  {:func "$__print_f64E" :type-kw :f64E}
    "g"  {:func "$__print_f64g" :type-kw :f64g}
    "G"  {:func "$__print_f64G" :type-kw :f64G}
    ;; default to i32
    {:func "$__print_i32" :type-kw :i32}))

(declare infer-type)

(defn- resolve-print-call
  "Adjust print function based on inferred argument type.
   Format spec determines presentation (signed/unsigned/hex/etc),
   inferred type determines width (i32 vs i64, f32 vs f64).
   Falls back to spec-only behavior when inference returns nil."
  [cg spec arg-node]
  (let [default (placeholder->print-func spec)
        inferred (infer-type cg arg-node)
        wat-type (when inferred (type->wat inferred))]
    (if (or (nil? wat-type) (= wat-type "i32"))
      default
      (case wat-type
        "i64" (case (:type-kw default)
                (:i32 :hex :HEX :oct) {:func "$__print_i64" :type-kw :i64}
                :u32 {:func "$__print_u64" :type-kw :u64}
                default)
        "f64" (case (:type-kw default)
                :i32 {:func "$__print_f64" :type-kw :f64}
                default)
        "f32" (case (:type-kw default)
                :i32 {:func "$__print_f64" :type-kw :f64}
                default)
        default))))

;; ── Local Collection ─────────────────────────────────────────

(declare collect-locals!)

(defn- collect-locals!
  "Pre-pass to collect all local variable declarations from the body.
   WASM requires all locals declared at the top of the function.
   Also tracks ARIA types in :local-types for pointer arithmetic scaling."
  [cg nodes]
  (doseq [node nodes]
    (when node
      (case (:node/type node)
        :let (let [name (var->wat (:name node))
                   wat-type (if (:aria/type node)
                              (or (type->wat (:aria/type node)) "i32")
                              "i32")]
               (swap! (:locals cg) assoc name wat-type)
               (when (:aria/type node)
                 (swap! (:local-types cg) assoc (:name node) (:aria/type node))))
        :free (swap! (:locals cg) assoc "$__free_tmp" "i32")
        :if (do (collect-locals! cg (:then-body node))
                (collect-locals! cg (:else-body node)))
        :loop (collect-locals! cg (:body node))
        :block (collect-locals! cg (:body node))
        :seq (collect-locals! cg (:body node))
        nil))))

;; ── Type Inference (for pointer arithmetic) ──────────────────

(declare infer-type resolve-struct-type)

(defn- infer-type
  "Infer the ARIA type of an expression node from local-types and func-sigs.
   Used to detect pointer arithmetic in add/sub operations."
  [cg node]
  (case (:node/type node)
    :var-ref   (get @(:local-types cg) (:name node))
    :alloc     (ast/ptr-type (:alloc-type node))
    :load      (when (:type-suffix node) (ast/primitive (:type-suffix node)))
    :load-field (when-let [struct-t (resolve-struct-type cg (:ptr node))]
                  (let [field-name (:field-name node)]
                    (some (fn [[fname ftype]] (when (= fname field-name) ftype))
                          (:type/fields struct-t))))
    :cast      (:to-type node)
    :call      (when-let [sig (get @(:func-sigs cg) (:target node))]
                 (:result sig))
    :bin-op    (let [left-t  (infer-type cg (:left node))
                     right-t (infer-type cg (:right node))]
                 ;; Pointer + int → pointer (propagate pointer type)
                 (cond
                   (and left-t (= :ptr (:type/kind left-t)))   left-t
                   (and right-t (= :ptr (:type/kind right-t))) right-t
                   (:type-suffix node) (ast/primitive (:type-suffix node))
                   :else nil))
    :int-literal   (ast/primitive "i32")
    :float-literal (ast/primitive "f64")
    :bool-literal  (ast/primitive "bool")
    nil))

(defn- resolve-struct-type
  "Given a codegen state and a pointer expression, resolve the struct type
   it points to by inferring the pointer's type and looking up the struct."
  [cg ptr-node]
  (let [ptr-type (infer-type cg ptr-node)]
    (when (and ptr-type (= :ptr (:type/kind ptr-type)))
      (let [pointee (:type/pointee ptr-type)]
        (if (= :struct (:type/kind pointee))
          pointee
          ;; Look up by name in struct-types registry
          (when (:type/name pointee)
            (get @(:struct-types cg) (:type/name pointee))))))))

(defn- ptr-element-size
  "If type is a pointer, return the element size in bytes. Otherwise nil."
  [t]
  (when (and t (= :ptr (:type/kind t)))
    (type-size (:type/pointee t))))

;; ── Cast Codegen ─────────────────────────────────────────────

(defn- emit-cast!
  "Emit WAT cast instruction between two types."
  [cg from-type to-type]
  (let [from-wat (or (type->wat from-type) "i32")
        to-wat (or (type->wat to-type) "i32")
        from-name (or (:type/name from-type) "i32")
        to-name (or (:type/name to-type) "i32")]
    (when (not= from-wat to-wat)
      (cond
        (and (= from-wat "i32") (= to-wat "i64"))
        (emit! cg (if (suffix->signed? from-name) "i64.extend_i32_s" "i64.extend_i32_u"))

        (and (= from-wat "i64") (= to-wat "i32"))
        (emit! cg "i32.wrap_i64")

        (and (= from-wat "i32") (= to-wat "f32"))
        (emit! cg (if (suffix->signed? from-name) "f32.convert_i32_s" "f32.convert_i32_u"))

        (and (= from-wat "i32") (= to-wat "f64"))
        (emit! cg (if (suffix->signed? from-name) "f64.convert_i32_s" "f64.convert_i32_u"))

        (and (= from-wat "i64") (= to-wat "f64"))
        (emit! cg (if (suffix->signed? from-name) "f64.convert_i64_s" "f64.convert_i64_u"))

        (and (= from-wat "i64") (= to-wat "f32"))
        (emit! cg (if (suffix->signed? from-name) "f32.convert_i64_s" "f32.convert_i64_u"))

        (and (= from-wat "f64") (= to-wat "i32"))
        (emit! cg (if (suffix->signed? to-name) "i32.trunc_f64_s" "i32.trunc_f64_u"))

        (and (= from-wat "f64") (= to-wat "i64"))
        (emit! cg (if (suffix->signed? to-name) "i64.trunc_f64_s" "i64.trunc_f64_u"))

        (and (= from-wat "f32") (= to-wat "f64"))
        (emit! cg "f64.promote_f32")

        (and (= from-wat "f64") (= to-wat "f32"))
        (emit! cg "f32.demote_f64")

        (and (= from-wat "f32") (= to-wat "i32"))
        (emit! cg (if (suffix->signed? to-name) "i32.trunc_f32_s" "i32.trunc_f32_u"))

        :else
        (emit! cg (str ";; unsupported cast: " from-wat " -> " to-wat))))))

;; ── Signed Integer Overflow ──────────────────────────────────
;; WASM integer arithmetic always wraps (modular 2^32 / 2^64).
;; C signed integer overflow is undefined behavior (UB) — compilers
;; may optimize assuming no overflow, producing different results at
;; -O2 and above. When cross-backend reproducibility is required,
;; compile C code with -fwrapv to match WASM wrapping semantics.
;; For intentional wrapping, prefer unsigned types (u32, u64).

;; ── Expression Codegen ───────────────────────────────────────

(declare emit-expr! emit-stmt!)

(defn- emit-expr!
  "Emit WAT instructions for an expression (pushes value onto stack)."
  [cg node]
  (case (:node/type node)
    :int-literal
    (emit! cg (str "i32.const " (:value node)))

    :float-literal
    (emit! cg (str "f64.const " (:value node)))

    :bool-literal
    (emit! cg (str "i32.const " (if (:value node) 1 0)))

    :string-literal
    (let [{:keys [ptr]} (intern-string! cg (:value node))]
      (emit! cg (str "i32.const " ptr)))

    :var-ref
    (let [name (:name node)
          wat-name (var->wat name)]
      (if (contains? @(:global-names cg) name)
        (emit! cg (str "global.get " wat-name))
        (emit! cg (str "local.get " wat-name))))

    :bin-op
    (let [suffix (:type-suffix node)
          wat-type (suffix->wat suffix)
          signed? (suffix->signed? suffix)
          float? (str/starts-with? wat-type "f")
          op (:op node)
          ;; Pointer arithmetic detection for add/sub
          left-t  (when (#{"add" "sub"} op) (infer-type cg (:left node)))
          right-t (when (#{"add" "sub"} op) (infer-type cg (:right node)))
          left-ptr-size  (ptr-element-size left-t)
          right-ptr-size (ptr-element-size right-t)]
      (cond
        ;; ptr + index → emit ptr, emit index * sizeof(elem), add
        (and (= op "add") left-ptr-size (not right-ptr-size))
        (do (emit-expr! cg (:left node))
            (emit-expr! cg (:right node))
            (when (> left-ptr-size 1)
              (emit! cg (str "i32.const " left-ptr-size))
              (emit! cg "i32.mul"))
            (emit! cg "i32.add"))

        ;; index + ptr → emit index * sizeof(elem), emit ptr, add
        (and (= op "add") right-ptr-size (not left-ptr-size))
        (do (emit-expr! cg (:left node))
            (when (> right-ptr-size 1)
              (emit! cg (str "i32.const " right-ptr-size))
              (emit! cg "i32.mul"))
            (emit-expr! cg (:right node))
            (emit! cg "i32.add"))

        ;; ptr - index → emit ptr, emit index * sizeof(elem), sub
        (and (= op "sub") left-ptr-size (not right-ptr-size))
        (do (emit-expr! cg (:left node))
            (emit-expr! cg (:right node))
            (when (> left-ptr-size 1)
              (emit! cg (str "i32.const " left-ptr-size))
              (emit! cg "i32.mul"))
            (emit! cg "i32.sub"))

        ;; Normal bin-op (no pointer arithmetic)
        :else
        (do (emit-expr! cg (:left node))
            (emit-expr! cg (:right node))
            (emit! cg (str wat-type "."
                            (case op
                              "add" "add"
                              "sub" "sub"
                              "mul" "mul"
                              "div" (if float? "div" (if signed? "div_s" "div_u"))
                              "rem" (if signed? "rem_s" "rem_u")
                              "and" "and"
                              "or"  "or"
                              "xor" "xor"
                              "shl" "shl"
                              "shr" (if signed? "shr_s" "shr_u")
                              op))))))

    :unary-op
    (let [suffix (:type-suffix node)
          wat-type (suffix->wat suffix)
          op (:op node)]
      (case op
        "neg" (if (str/starts-with? wat-type "f")
                (do (emit-expr! cg (:operand node))
                    (emit! cg (str wat-type ".neg")))
                (do (emit! cg (str wat-type ".const 0"))
                    (emit-expr! cg (:operand node))
                    (emit! cg (str wat-type ".sub"))))
        "not" (do (emit-expr! cg (:operand node))
                  (emit! cg (str wat-type ".const -1"))
                  (emit! cg (str wat-type ".xor")))
        ("sqrt" "abs" "ceil" "floor")
        (do (emit-expr! cg (:operand node))
            (emit! cg (str wat-type "." op)))
        ;; fallback
        (emit! cg (str ";; unknown unary op: " op))))

    :comparison
    (let [suffix (:type-suffix node)
          wat-type (suffix->wat suffix)
          signed? (suffix->signed? suffix)
          float? (str/starts-with? wat-type "f")
          op (:op node)]
      (emit-expr! cg (:left node))
      (emit-expr! cg (:right node))
      (emit! cg (str wat-type "."
                      (case op
                        "eq" "eq"
                        "ne" "ne"
                        "lt" (if float? "lt" (if signed? "lt_s" "lt_u"))
                        "le" (if float? "le" (if signed? "le_s" "le_u"))
                        "gt" (if float? "gt" (if signed? "gt_s" "gt_u"))
                        "ge" (if float? "ge" (if signed? "ge_s" "ge_u"))
                        op))))

    :call
    (do (doseq [arg (:args node)]
          (emit-expr! cg arg))
        (emit! cg (str "call " (var->wat (:target node)))))

    :alloc
    (do (reset! (:needs-alloc cg) true)
        (let [elem-size (type-size (:alloc-type node))]
          (if (:count node)
            (do (emit-expr! cg (:count node))
                (emit! cg (str "i32.const " elem-size))
                (emit! cg "i32.mul"))
            (emit! cg (str "i32.const " elem-size)))
          (emit! cg "call $__alloc")))

    :load
    (do (emit-expr! cg (:ptr node))
        (emit! cg (str (suffix->wat (:type-suffix node)) ".load")))

    :load-field
    (let [struct-t (resolve-struct-type cg (:ptr node))
          {:keys [offset wat-type]} (if struct-t
                                      (compute-field-offset struct-t (:field-name node))
                                      {:offset 0 :wat-type "i32"})]
      (emit-expr! cg (:ptr node))
      (emit! cg (str wat-type ".load offset=" offset)))

    :cast
    (do (emit-expr! cg (:value node))
        (emit-cast! cg (:from-type node) (:to-type node)))

    :if
    (let [;; Infer result type from the last expression in the then branch
          then-last (last (:then-body node))
          result-type (when then-last
                        (or (type->wat (infer-type cg then-last)) "i32"))]
      (emit-expr! cg (:cond node))
      (emit! cg (str "if (result " result-type ")"))
      (swap! (:indent cg) inc)
      ;; Emit all but last as statements, last as expression (leaves value on stack)
      (let [then-body (:then-body node)
            then-stmts (butlast then-body)
            then-expr (last then-body)]
        (doseq [n then-stmts] (emit-stmt! cg n))
        (when then-expr (emit-expr! cg then-expr)))
      (swap! (:indent cg) dec)
      (emit! cg "else")
      (swap! (:indent cg) inc)
      (let [else-body (:else-body node)
            else-stmts (butlast else-body)
            else-expr (last else-body)]
        (doseq [n else-stmts] (emit-stmt! cg n))
        (if else-expr
          (emit-expr! cg else-expr)
          ;; No else body — push a default zero value
          (emit! cg (str result-type ".const 0"))))
      (swap! (:indent cg) dec)
      (emit! cg "end"))

    ;; Fallback
    (emit! cg (str ";; unknown expr: " (:node/type node)))))

;; ── Statement Codegen ────────────────────────────────────────

(defn- maybe-widen!
  "Emit implicit widening if an int/bool literal is assigned to a wider type."
  [cg value-node target-wat-type]
  (when target-wat-type
    (let [is-int? (#{:int-literal :bool-literal} (:node/type value-node))]
      (when is-int?
        (case target-wat-type
          "i64" (emit! cg "i64.extend_i32_s")
          "f32" (emit! cg "f32.convert_i32_s")
          "f64" (emit! cg "f64.convert_i32_s")
          nil)))))

(declare emit-stmt!)

(defn- emit-stmt!
  "Emit WAT instructions for a statement (consumes/balances the stack)."
  [cg node]
  (case (:node/type node)
    :intent
    (emit! cg (str ";; INTENT: " (:description node)))

    :let
    (let [target-wat (when (:aria/type node) (type->wat (:aria/type node)))]
      (emit-expr! cg (:value node))
      (maybe-widen! cg (:value node) target-wat)
      (emit! cg (str "local.set " (var->wat (:name node)))))

    :set-var
    (let [name (:name node)
          wat-name (var->wat name)
          is-global? (contains? @(:global-names cg) name)
          target-wat (if is-global?
                       (:wat-type (get @(:global-names cg) name))
                       (get @(:locals cg) wat-name))]
      (emit-expr! cg (:value node))
      (maybe-widen! cg (:value node) target-wat)
      (emit! cg (str (if is-global? "global.set " "local.set ") wat-name)))

    :store
    (do (emit-expr! cg (:ptr node))
        (emit-expr! cg (:value node))
        (emit! cg (str (suffix->wat (:type-suffix node)) ".store")))

    :store-field
    (let [struct-t (resolve-struct-type cg (:ptr node))
          {:keys [offset wat-type]} (if struct-t
                                      (compute-field-offset struct-t (:field-name node))
                                      {:offset 0 :wat-type "i32"})]
      (emit-expr! cg (:ptr node))
      (emit-expr! cg (:value node))
      (emit! cg (str wat-type ".store offset=" offset)))

    :free
    (do ;; Prepend block to free list head
        ;; Header is at ptr - 8, next-free at ptr - 4
        (emit-expr! cg (:ptr node))
        (emit! cg "local.set $__free_tmp")
        ;; Store current free list head at ptr - 4
        (emit! cg "local.get $__free_tmp")
        (emit! cg "i32.const 4")
        (emit! cg "i32.sub")
        (emit! cg "global.get $__free_list")
        (emit! cg "i32.store")
        ;; Set free list head to this block
        (emit! cg "local.get $__free_tmp")
        (emit! cg "global.set $__free_list"))

    :return
    (do (when (:value node)
          (emit-expr! cg (:value node)))
        (emit! cg "return"))

    :if
    (do (emit-expr! cg (:cond node))
        (emit! cg "if")
        (swap! (:indent cg) inc)
        (doseq [n (:then-body node)]
          (emit-stmt! cg n))
        (swap! (:indent cg) dec)
        (when (seq (:else-body node))
          (emit! cg "else")
          (swap! (:indent cg) inc)
          (doseq [n (:else-body node)]
            (emit-stmt! cg n))
          (swap! (:indent cg) dec))
        (emit! cg "end"))

    :loop
    (let [label (var->wat (:label node))
          break-label (str "$break_" (subs label 1))]
      (emit! cg (str "block " break-label))
      (swap! (:indent cg) inc)
      (emit! cg (str "loop " label))
      (swap! (:indent cg) inc)
      (doseq [n (:body node)]
        (emit-stmt! cg n))
      ;; Implicit continue: branch back to loop head
      (emit! cg (str "br " label))
      (swap! (:indent cg) dec)
      (emit! cg "end")
      (swap! (:indent cg) dec)
      (emit! cg "end"))

    :branch
    (let [label (var->wat (:label node))
          break-label (str "$break_" (subs label 1))]
      (if (:cond node)
        (do (emit-expr! cg (:cond node))
            (emit! cg (str "br_if " break-label)))
        (emit! cg (str "br " break-label))))

    :block
    (let [label (var->wat (:label node))]
      (emit! cg (str "block " label))
      (swap! (:indent cg) inc)
      (doseq [n (:body node)]
        (emit-stmt! cg n))
      (swap! (:indent cg) dec)
      (emit! cg "end"))

    :print
    (do (reset! (:needs-print cg) true)
        (let [segments (split-format-string (:format-str node))
              args (:args node)
              arg-idx (atom 0)]
          (doseq [seg segments]
            (case (:type seg)
              :literal
              (let [{:keys [ptr len]} (intern-string! cg (:text seg))]
                (emit! cg (str "i32.const " ptr))
                (emit! cg (str "i32.const " len))
                (emit! cg "call $__print"))
              :placeholder
              (let [idx @arg-idx]
                (when (< idx (count args))
                  (let [arg-node (nth args idx)
                        modifiers (parse-format-modifiers (:full-spec seg))
                        {:keys [func type-kw]} (resolve-print-call cg (:spec seg) arg-node)
                        has-width? (and modifiers (:width modifiers))
                        has-precision? (and modifiers (:precision modifiers)
                                           (contains? #{:f64 :f64e :f64E :f64g :f64G} type-kw))
                        use-buf? has-width?]
                    (swap! (:print-types cg) conj type-kw)
                    ;; Activate buffer mode for width formatting
                    (when use-buf?
                      (reset! (:needs-fmt-buf cg) true)
                      (emit! cg ";; Enable format buffer for width padding")
                      (emit! cg "(global.set $__buf_mode (i32.const 1))")
                      (emit! cg "(global.set $__buf_len (i32.const 0))"))
                    ;; Emit the value and call the appropriate print function
                    (if has-precision?
                      ;; Use precision-aware f64 print
                      (do (reset! (:needs-f64-prec cg) true)
                          (emit-expr! cg arg-node)
                          (emit! cg (str "(i32.const " (:precision modifiers) ")"))
                          (emit! cg "call $__print_f64_p"))
                      ;; Standard print call
                      (do (emit-expr! cg arg-node)
                          (emit! cg (str "call " func))))
                    ;; Flush buffer with padding if width was specified
                    (when use-buf?
                      (let [{:keys [width zero-pad? left-align?]} modifiers]
                        (emit! cg (str "(call $__flush_padded"
                                       " (i32.const " (or width 0) ")"
                                       " (i32.const " (if zero-pad? 48 32) ")"
                                       " (i32.const " (if left-align? 1 0) "))"))))
                    (swap! arg-idx inc))))))))

    :call
    (let [target (:target node)
          has-result? (when-let [sig (get @(:func-sigs cg) target)]
                        (some? (:result sig)))]
      (doseq [arg (:args node)]
        (emit-expr! cg arg))
      (emit! cg (str "call " (var->wat target)))
      (when has-result?
        (emit! cg "drop")))

    :seq
    (doseq [n (:body node)]
      (emit-stmt! cg n))

    ;; Expression used as statement — emit and drop
    (emit-expr! cg node)))

;; ── Function Codegen ─────────────────────────────────────────

(defn- emit-function!
  "Emit a complete WAT function definition."
  [cg func export-names]
  (reset! (:locals cg) {})
  (reset! (:local-types cg) {})
  ;; Register param types for pointer arithmetic inference
  (doseq [p (:params func)]
    (swap! (:local-types cg) assoc (:param/name p) (:param/type p)))
  (collect-locals! cg (:body func))

  (let [fname (var->wat (:name func))
        export? (contains? export-names (:name func))
        params (:params func)
        result-type (when (:result func) (type->wat (:result func)))
        locals @(:locals cg)
        ;; Remove params from locals (params are declared in signature)
        param-names (set (map #(var->wat (:param/name %)) params))
        func-locals (into (sorted-map) (remove (fn [[k _]] (param-names k)) locals))]

    ;; Intent as comment
    (when (:intent func)
      (emit! cg (str ";; " (:intent func))))

    ;; Export declaration
    (when export?
      (emit! cg (str "(export \"" (subs fname 1) "\" (func " fname "))")))

    ;; Function signature
    (let [param-strs (map (fn [p]
                            (str "(param " (var->wat (:param/name p))
                                 " " (or (type->wat (:param/type p)) "i32") ")"))
                          params)
          sig-parts (concat [fname] param-strs
                            (when result-type
                              [(str "(result " result-type ")")]))]
      (emit! cg (str "(func " (str/join " " sig-parts))))
    (swap! (:indent cg) inc)

    ;; Local declarations
    (doseq [[lname ltype] func-locals]
      (emit! cg (str "(local " lname " " ltype ")")))
    (when (seq func-locals)
      (emit! cg ""))

    ;; Body
    (doseq [node (:body func)]
      (emit-stmt! cg node))

    ;; WASM requires the stack to match the result type at the end of the function.
    ;; If the function has a result type and all code paths use explicit `return`,
    ;; the validator still needs a value or `unreachable` at the implicit end.
    (when result-type
      (emit! cg "unreachable"))

    (swap! (:indent cg) dec)
    (emit! cg ")")
    (emit! cg "")))

;; ── Built-in Functions ───────────────────────────────────────

(defn- emit-bump-alloc-function!
  "Emit the built-in bump allocator function (no free support)."
  [cg]
  (emit! cg ";; Built-in bump allocator")
  (emit! cg "(func $__alloc (param $size i32) (result i32)")
  (swap! (:indent cg) inc)
  (emit! cg "(local $ptr i32)")
  (emit! cg "global.get $__stack_ptr")
  (emit! cg "local.set $ptr")
  (emit! cg "global.get $__stack_ptr")
  (emit! cg "local.get $size")
  (emit! cg "i32.add")
  (emit! cg "global.set $__stack_ptr")
  (emit! cg "local.get $ptr")
  (swap! (:indent cg) dec)
  (emit! cg ")")
  (emit! cg ""))

(defn- emit-freelist-alloc-function!
  "Emit free-list allocator.
   Block header layout (8 bytes before returned pointer):
     offset -8: block size (i32)
     offset -4: next-free pointer (i32, 0 = end of list)"
  [cg]
  (emit! cg ";; Free-list allocator")
  (emit! cg "(func $__alloc (param $size i32) (result i32)")
  (swap! (:indent cg) inc)
  (emit! cg "(local $prev i32)")
  (emit! cg "(local $curr i32)")
  (emit! cg "(local $block_size i32)")
  (emit! cg "(local $ptr i32)")
  (emit! cg "(local $new_top i32)")
  (emit! cg "")
  ;; Walk free list looking for a block >= requested size
  (emit! cg ";; Walk free list")
  (emit! cg "(local.set $prev (i32.const 0))")
  (emit! cg "(local.set $curr (global.get $__free_list))")
  (emit! cg "(block $alloc_bump")
  (swap! (:indent cg) inc)
  (emit! cg "(block $found")
  (swap! (:indent cg) inc)
  (emit! cg "(loop $search")
  (swap! (:indent cg) inc)
  (emit! cg "(br_if $alloc_bump (i32.eqz (local.get $curr)))")
  (emit! cg ";; Header is at curr - 8")
  (emit! cg "(local.set $block_size (i32.load (i32.sub (local.get $curr) (i32.const 8))))")
  (emit! cg "(br_if $found (i32.ge_u (local.get $block_size) (local.get $size)))")
  (emit! cg ";; Advance: prev = curr, curr = next pointer at curr - 4")
  (emit! cg "(local.set $prev (local.get $curr))")
  (emit! cg "(local.set $curr (i32.load (i32.sub (local.get $curr) (i32.const 4))))")
  (emit! cg "(br $search)")
  (swap! (:indent cg) dec)
  (emit! cg ")") ;; loop
  (swap! (:indent cg) dec)
  (emit! cg ")") ;; block $found
  (emit! cg ";; Found a free block — unlink it")
  (emit! cg "(if (i32.eqz (local.get $prev))")
  (swap! (:indent cg) inc)
  (emit! cg ";; Removing head of free list")
  (emit! cg "(then (global.set $__free_list (i32.load (i32.sub (local.get $curr) (i32.const 4)))))")
  (emit! cg "(else (i32.store (i32.sub (local.get $prev) (i32.const 4))")
  (emit! cg "  (i32.load (i32.sub (local.get $curr) (i32.const 4))))))")
  (swap! (:indent cg) dec)
  (emit! cg "(return (local.get $curr))")
  (swap! (:indent cg) dec)
  (emit! cg ")") ;; block $alloc_bump
  (emit! cg "")
  ;; Bump allocate: advance stack_ptr by size + 8 (header)
  (emit! cg ";; Bump allocate with 8-byte header")
  (emit! cg "(local.set $ptr (i32.add (global.get $__stack_ptr) (i32.const 8)))")
  (emit! cg "(local.set $new_top (i32.add (local.get $ptr) (local.get $size)))")
  (emit! cg "")
  ;; Check if we need to grow memory
  (emit! cg ";; Grow memory if needed")
  (emit! cg "(if (i32.gt_u (local.get $new_top) (i32.mul (memory.size) (i32.const 65536)))")
  (swap! (:indent cg) inc)
  (emit! cg "(then")
  (swap! (:indent cg) inc)
  (emit! cg "(if (i32.eq (memory.grow (i32.const 1)) (i32.const -1))")
  (swap! (:indent cg) inc)
  (emit! cg "(then (unreachable)))")
  (swap! (:indent cg) dec)
  (emit! cg ")")
  (swap! (:indent cg) dec)
  (emit! cg ")")
  (swap! (:indent cg) dec)
  (emit! cg "")
  ;; Store block size in header
  (emit! cg "(i32.store (global.get $__stack_ptr) (local.get $size))")
  (emit! cg "(global.set $__stack_ptr (local.get $new_top))")
  (emit! cg "(local.get $ptr)")
  (swap! (:indent cg) dec)
  (emit! cg ")")
  (emit! cg ""))

(defn- emit-alloc-function!
  "Emit the allocator function based on the allocator type."
  [cg allocator]
  (case allocator
    :bump (emit-bump-alloc-function! cg)
    (emit-freelist-alloc-function! cg)))

;; ── WASI Runtime ─────────────────────────────────────────────
;; Scratch memory layout (offsets 0-63, reserved):
;;   0-3:   iovec.buf  (i32)
;;   4-7:   iovec.len  (i32)
;;   8-11:  nwritten   (i32)
;;  16-47:  itoa buffer (32 bytes, right-to-left)
;;  48-63:  reserved
;;  64-319: format buffer (256 bytes, used when $__buf_mode=1)

(defn- emit-wasi-print!
  "Emit $__print using WASI fd_write. When format buffer mode is active
   ($__buf_mode=1), appends to the buffer at offset 64 instead of writing
   to stdout."
  [cg needs-fmt-buf?]
  (emit! cg ";; Print string at (ptr, len) to stdout via WASI fd_write")
  (emit! cg (str "(func $__print (param $ptr i32) (param $len i32)"
                  (when needs-fmt-buf? " (local $i i32)")))
  (swap! (:indent cg) inc)
  (when needs-fmt-buf?
    (emit! cg ";; Check buffer mode: if active, append to format buffer")
    (emit! cg "(if (global.get $__buf_mode)")
    (swap! (:indent cg) inc)
    (emit! cg "(then")
    (swap! (:indent cg) inc)
    ;; Copy bytes from ptr to buffer at 64 + buf_len, byte by byte
    ;; (memory.copy requires bulk-memory proposal, use loop for portability)
    (emit! cg "(local.set $i (i32.const 0))")
    (emit! cg "(block $copy_done")
    (swap! (:indent cg) inc)
    (emit! cg "(loop $copy_loop")
    (swap! (:indent cg) inc)
    (emit! cg "(br_if $copy_done (i32.ge_u (local.get $i) (local.get $len)))")
    (emit! cg "(i32.store8")
    (swap! (:indent cg) inc)
    (emit! cg "(i32.add (i32.const 64) (i32.add (global.get $__buf_len) (local.get $i)))")
    (emit! cg "(i32.load8_u (i32.add (local.get $ptr) (local.get $i))))")
    (swap! (:indent cg) dec)
    (emit! cg "(local.set $i (i32.add (local.get $i) (i32.const 1)))")
    (emit! cg "(br $copy_loop)")
    (swap! (:indent cg) dec)
    (emit! cg "))")
    (swap! (:indent cg) dec)
    (emit! cg "(global.set $__buf_len (i32.add (global.get $__buf_len) (local.get $len)))")
    (emit! cg "(return)")
    (swap! (:indent cg) dec)
    (emit! cg "))")
    (swap! (:indent cg) dec))
  (emit! cg ";; iovec at offset 0: {buf, len}")
  (emit! cg "(i32.store (i32.const 0) (local.get $ptr))")
  (emit! cg "(i32.store (i32.const 4) (local.get $len))")
  (emit! cg ";; fd_write(fd=1, iovs=0, iovs_len=1, nwritten=8)")
  (emit! cg "(drop (call $fd_write (i32.const 1) (i32.const 0) (i32.const 1) (i32.const 8)))")
  (swap! (:indent cg) dec)
  (emit! cg ")")
  (emit! cg ""))

(defn- emit-wasi-flush-padded!
  "Emit $__flush_padded: flush the format buffer with width padding.
   Params: $width (min output width), $pad_char (32=space, 48='0'),
           $left_align (0=right-align, 1=left-align).
   Reads buffered content from offset 64, length from $__buf_len."
  [cg]
  (emit! cg ";; Flush format buffer with width/padding support")
  (emit! cg "(func $__flush_padded (param $width i32) (param $pad_char i32) (param $left_align i32)")
  (swap! (:indent cg) inc)
  (emit! cg "(local $pad_count i32)")
  (emit! cg "(local $i i32)")
  (emit! cg "")
  ;; Calculate padding needed
  (emit! cg ";; Calculate padding: max(0, width - buf_len)")
  (emit! cg "(local.set $pad_count (i32.const 0))")
  (emit! cg "(if (i32.gt_s (local.get $width) (global.get $__buf_len))")
  (swap! (:indent cg) inc)
  (emit! cg "(then (local.set $pad_count (i32.sub (local.get $width) (global.get $__buf_len)))))")
  (swap! (:indent cg) dec)
  (emit! cg "")
  ;; Right-align: pad first, then content
  (emit! cg ";; If right-aligned (default), emit padding first")
  (emit! cg "(if (i32.eqz (local.get $left_align))")
  (swap! (:indent cg) inc)
  (emit! cg "(then")
  (swap! (:indent cg) inc)
  ;; For zero-padding with negative numbers, we need to print the '-' first
  ;; then pad with zeros. Check if pad_char is '0' and first byte is '-'.
  (emit! cg ";; Special case: zero-pad with negative number — print '-' before zeros")
  (emit! cg "(if (i32.and")
  (swap! (:indent cg) inc)
  (emit! cg "(i32.eq (local.get $pad_char) (i32.const 48))")
  (emit! cg "(i32.and (i32.gt_s (global.get $__buf_len) (i32.const 0))")
  (emit! cg "  (i32.eq (i32.load8_u (i32.const 64)) (i32.const 45))))")  ;; '-' = 45
  (swap! (:indent cg) dec)
  (swap! (:indent cg) inc)
  (emit! cg "(then")
  (swap! (:indent cg) inc)
  ;; Print '-' directly
  (emit! cg "(i32.store (i32.const 0) (i32.const 64))")  ;; iovec.buf = 64
  (emit! cg "(i32.store (i32.const 4) (i32.const 1))")   ;; iovec.len = 1
  (emit! cg "(drop (call $fd_write (i32.const 1) (i32.const 0) (i32.const 1) (i32.const 8)))")
  ;; Emit padding
  (emit! cg "(i32.store8 (i32.const 48) (local.get $pad_char))")
  (emit! cg "(local.set $i (i32.const 0))")
  (emit! cg "(block $pad_done1")
  (swap! (:indent cg) inc)
  (emit! cg "(loop $pad_loop1")
  (swap! (:indent cg) inc)
  (emit! cg "(br_if $pad_done1 (i32.ge_u (local.get $i) (local.get $pad_count)))")
  (emit! cg "(i32.store (i32.const 0) (i32.const 48))")
  (emit! cg "(i32.store (i32.const 4) (i32.const 1))")
  (emit! cg "(drop (call $fd_write (i32.const 1) (i32.const 0) (i32.const 1) (i32.const 8)))")
  (emit! cg "(local.set $i (i32.add (local.get $i) (i32.const 1)))")
  (emit! cg "(br $pad_loop1)")
  (swap! (:indent cg) dec)
  (emit! cg "))")
  (swap! (:indent cg) dec)
  ;; Print remaining content (skip '-')
  (emit! cg "(if (i32.gt_s (global.get $__buf_len) (i32.const 1))")
  (swap! (:indent cg) inc)
  (emit! cg "(then")
  (swap! (:indent cg) inc)
  (emit! cg "(i32.store (i32.const 0) (i32.const 65))")  ;; skip '-' at 64
  (emit! cg "(i32.store (i32.const 4) (i32.sub (global.get $__buf_len) (i32.const 1)))")
  (emit! cg "(drop (call $fd_write (i32.const 1) (i32.const 0) (i32.const 1) (i32.const 8)))))")
  (swap! (:indent cg) dec)
  (swap! (:indent cg) dec)
  ;; Reset buffer
  (emit! cg "(global.set $__buf_mode (i32.const 0))")
  (emit! cg "(global.set $__buf_len (i32.const 0))")
  (emit! cg "(return)")
  (swap! (:indent cg) dec)
  (emit! cg "))")
  (swap! (:indent cg) dec)
  (emit! cg "")
  ;; Normal right-pad: emit padding, then content
  (emit! cg ";; Normal right-align: padding then content")
  (emit! cg "(i32.store8 (i32.const 48) (local.get $pad_char))")
  (emit! cg "(local.set $i (i32.const 0))")
  (emit! cg "(block $pad_done2")
  (swap! (:indent cg) inc)
  (emit! cg "(loop $pad_loop2")
  (swap! (:indent cg) inc)
  (emit! cg "(br_if $pad_done2 (i32.ge_u (local.get $i) (local.get $pad_count)))")
  (emit! cg "(i32.store (i32.const 0) (i32.const 48))")
  (emit! cg "(i32.store (i32.const 4) (i32.const 1))")
  (emit! cg "(drop (call $fd_write (i32.const 1) (i32.const 0) (i32.const 1) (i32.const 8)))")
  (emit! cg "(local.set $i (i32.add (local.get $i) (i32.const 1)))")
  (emit! cg "(br $pad_loop2)")
  (swap! (:indent cg) dec)
  (emit! cg "))")
  (swap! (:indent cg) dec)
  (emit! cg "))")
  (swap! (:indent cg) dec)
  (emit! cg "")
  ;; Print buffered content
  (emit! cg ";; Print buffered content")
  (emit! cg "(if (i32.gt_s (global.get $__buf_len) (i32.const 0))")
  (swap! (:indent cg) inc)
  (emit! cg "(then")
  (swap! (:indent cg) inc)
  (emit! cg "(i32.store (i32.const 0) (i32.const 64))")
  (emit! cg "(i32.store (i32.const 4) (global.get $__buf_len))")
  (emit! cg "(drop (call $fd_write (i32.const 1) (i32.const 0) (i32.const 1) (i32.const 8)))))")
  (swap! (:indent cg) dec)
  (swap! (:indent cg) dec)
  (emit! cg "")
  ;; Left-align: content already printed above, now pad after
  (emit! cg ";; If left-aligned, emit padding after content")
  (emit! cg "(if (local.get $left_align)")
  (swap! (:indent cg) inc)
  (emit! cg "(then")
  (swap! (:indent cg) inc)
  (emit! cg "(i32.store8 (i32.const 48) (i32.const 32))")  ;; always space-pad for left-align
  (emit! cg "(local.set $i (i32.const 0))")
  (emit! cg "(block $pad_done3")
  (swap! (:indent cg) inc)
  (emit! cg "(loop $pad_loop3")
  (swap! (:indent cg) inc)
  (emit! cg "(br_if $pad_done3 (i32.ge_u (local.get $i) (local.get $pad_count)))")
  (emit! cg "(i32.store (i32.const 0) (i32.const 48))")
  (emit! cg "(i32.store (i32.const 4) (i32.const 1))")
  (emit! cg "(drop (call $fd_write (i32.const 1) (i32.const 0) (i32.const 1) (i32.const 8)))")
  (emit! cg "(local.set $i (i32.add (local.get $i) (i32.const 1)))")
  (emit! cg "(br $pad_loop3)")
  (swap! (:indent cg) dec)
  (emit! cg "))")
  (swap! (:indent cg) dec)
  (emit! cg "))")
  (swap! (:indent cg) dec)
  (emit! cg "")
  ;; Reset buffer mode
  (emit! cg "(global.set $__buf_mode (i32.const 0))")
  (emit! cg "(global.set $__buf_len (i32.const 0))")
  (swap! (:indent cg) dec)
  (emit! cg ")")
  (emit! cg ""))

(defn- emit-wasi-print-f64-prec!
  "Emit $__print_f64_p: like $__print_f64 but with a precision parameter.
   Prints integer part + '.' + exactly N fractional digits."
  [cg]
  (emit! cg ";; Print f64 with variable precision (param $prec = number of decimal places)")
  (emit! cg "(func $__print_f64_p (param $val f64) (param $prec i32)")
  (swap! (:indent cg) inc)
  (emit! cg "(local $int_part i64)")
  (emit! cg "(local $frac_val i64)")
  (emit! cg "(local $negative i32)")
  (emit! cg "(local $multiplier f64)")
  (emit! cg "(local $i i32)")
  (emit! cg "(local $digit_count i32)")
  (emit! cg "")
  ;; NaN check
  (emit! cg ";; NaN check")
  (emit! cg "(if (f64.ne (local.get $val) (local.get $val))")
  (swap! (:indent cg) inc)
  (emit! cg "(then")
  (swap! (:indent cg) inc)
  (emit! cg "(i32.store8 (i32.const 48) (i32.const 78))")
  (emit! cg "(i32.store8 (i32.const 49) (i32.const 97))")
  (emit! cg "(i32.store8 (i32.const 50) (i32.const 78))")
  (emit! cg "(call $__print (i32.const 48) (i32.const 3))")
  (emit! cg "(return)")
  (swap! (:indent cg) dec)
  (emit! cg "))")
  (swap! (:indent cg) dec)
  (emit! cg "")
  ;; Infinity checks
  (emit! cg "(if (f64.eq (local.get $val) (f64.const inf))")
  (swap! (:indent cg) inc)
  (emit! cg "(then")
  (swap! (:indent cg) inc)
  (emit! cg "(i32.store8 (i32.const 48) (i32.const 105))")
  (emit! cg "(i32.store8 (i32.const 49) (i32.const 110))")
  (emit! cg "(i32.store8 (i32.const 50) (i32.const 102))")
  (emit! cg "(call $__print (i32.const 48) (i32.const 3))")
  (emit! cg "(return)")
  (swap! (:indent cg) dec)
  (emit! cg "))")
  (swap! (:indent cg) dec)
  (emit! cg "(if (f64.eq (local.get $val) (f64.const -inf))")
  (swap! (:indent cg) inc)
  (emit! cg "(then")
  (swap! (:indent cg) inc)
  (emit! cg "(i32.store8 (i32.const 48) (i32.const 45))")
  (emit! cg "(i32.store8 (i32.const 49) (i32.const 105))")
  (emit! cg "(i32.store8 (i32.const 50) (i32.const 110))")
  (emit! cg "(i32.store8 (i32.const 51) (i32.const 102))")
  (emit! cg "(call $__print (i32.const 48) (i32.const 4))")
  (emit! cg "(return)")
  (swap! (:indent cg) dec)
  (emit! cg "))")
  (swap! (:indent cg) dec)
  (emit! cg "")
  ;; Handle negative
  (emit! cg "(local.set $negative (i32.const 0))")
  (emit! cg "(if (f64.lt (local.get $val) (f64.const 0))")
  (swap! (:indent cg) inc)
  (emit! cg "(then")
  (swap! (:indent cg) inc)
  (emit! cg "(local.set $negative (i32.const 1))")
  (emit! cg "(local.set $val (f64.neg (local.get $val)))))")
  (swap! (:indent cg) dec)
  (swap! (:indent cg) dec)
  (emit! cg "(if (local.get $negative)")
  (swap! (:indent cg) inc)
  (emit! cg "(then (i32.store8 (i32.const 48) (i32.const 45))")
  (emit! cg " (call $__print (i32.const 48) (i32.const 1))))")
  (swap! (:indent cg) dec)
  (emit! cg "")
  ;; Integer part
  (emit! cg "(local.set $int_part (i64.trunc_f64_s (local.get $val)))")
  (emit! cg "(call $__print_i64 (local.get $int_part))")
  (emit! cg "")
  ;; If precision is 0, skip decimal point and fraction
  (emit! cg ";; If precision is 0, skip decimal part")
  (emit! cg "(if (i32.eqz (local.get $prec))")
  (swap! (:indent cg) inc)
  (emit! cg "(then (return)))")
  (swap! (:indent cg) dec)
  (emit! cg "")
  ;; Decimal point
  (emit! cg "(i32.store8 (i32.const 48) (i32.const 46))")
  (emit! cg "(call $__print (i32.const 48) (i32.const 1))")
  (emit! cg "")
  ;; Compute multiplier = 10^prec
  (emit! cg ";; Compute 10^prec")
  (emit! cg "(local.set $multiplier (f64.const 1.0))")
  (emit! cg "(local.set $i (i32.const 0))")
  (emit! cg "(block $mul_done")
  (swap! (:indent cg) inc)
  (emit! cg "(loop $mul_loop")
  (swap! (:indent cg) inc)
  (emit! cg "(br_if $mul_done (i32.ge_u (local.get $i) (local.get $prec)))")
  (emit! cg "(local.set $multiplier (f64.mul (local.get $multiplier) (f64.const 10.0)))")
  (emit! cg "(local.set $i (i32.add (local.get $i) (i32.const 1)))")
  (emit! cg "(br $mul_loop)")
  (swap! (:indent cg) dec)
  (emit! cg "))")
  (swap! (:indent cg) dec)
  (emit! cg "")
  ;; Fractional value
  (emit! cg ";; Fractional digits")
  (emit! cg "(local.set $frac_val (i64.trunc_f64_s (f64.mul")
  (swap! (:indent cg) inc)
  (emit! cg "(f64.sub (local.get $val) (f64.convert_i64_s (local.get $int_part)))")
  (emit! cg "(local.get $multiplier))))")
  (swap! (:indent cg) dec)
  (emit! cg "(if (i64.lt_s (local.get $frac_val) (i64.const 0))")
  (swap! (:indent cg) inc)
  (emit! cg "(then (local.set $frac_val (i64.sub (i64.const 0) (local.get $frac_val)))))")
  (swap! (:indent cg) dec)
  (emit! cg "")
  ;; Count digits in frac_val to determine leading zeros needed
  (emit! cg ";; Count digits in frac_val")
  (emit! cg "(local.set $digit_count (i32.const 1))")
  (emit! cg "(local.set $multiplier (f64.const 10.0))")
  (emit! cg "(block $count_done")
  (swap! (:indent cg) inc)
  (emit! cg "(loop $count_loop")
  (swap! (:indent cg) inc)
  (emit! cg "(br_if $count_done (f64.gt (local.get $multiplier) (f64.convert_i64_u (local.get $frac_val))))")
  (emit! cg "(local.set $digit_count (i32.add (local.get $digit_count) (i32.const 1)))")
  (emit! cg "(local.set $multiplier (f64.mul (local.get $multiplier) (f64.const 10.0)))")
  (emit! cg "(br $count_loop)")
  (swap! (:indent cg) dec)
  (emit! cg "))")
  (swap! (:indent cg) dec)
  (emit! cg "")
  ;; Print leading zeros
  (emit! cg ";; Print leading zeros")
  (emit! cg "(local.set $i (local.get $digit_count))")
  (emit! cg "(block $zero_done")
  (swap! (:indent cg) inc)
  (emit! cg "(loop $zero_loop")
  (swap! (:indent cg) inc)
  (emit! cg "(br_if $zero_done (i32.ge_u (local.get $i) (local.get $prec)))")
  (emit! cg "(i32.store8 (i32.const 48) (i32.const 48))")
  (emit! cg "(call $__print (i32.const 48) (i32.const 1))")
  (emit! cg "(local.set $i (i32.add (local.get $i) (i32.const 1)))")
  (emit! cg "(br $zero_loop)")
  (swap! (:indent cg) dec)
  (emit! cg "))")
  (swap! (:indent cg) dec)
  ;; Print fractional digits
  (emit! cg "(call $__print_i64 (local.get $frac_val))")
  (swap! (:indent cg) dec)
  (emit! cg ")")
  (emit! cg ""))

(defn- emit-wasi-print-i32!
  "Emit $__print_i32: convert i32 to decimal string and print."
  [cg]
  (emit! cg ";; Print i32 as decimal string")
  (emit! cg "(func $__print_i32 (param $val i32)")
  (swap! (:indent cg) inc)
  (emit! cg "(local $buf_pos i32)")
  (emit! cg "(local $negative i32)")
  (emit! cg "(local $digit i32)")
  (emit! cg "(local $tmp i32)")
  (emit! cg "")
  (emit! cg ";; Start from end of scratch buffer (offset 47)")
  (emit! cg "(local.set $buf_pos (i32.const 47))")
  (emit! cg "")
  (emit! cg ";; Handle negative")
  (emit! cg "(local.set $negative (i32.const 0))")
  (emit! cg "(if (i32.lt_s (local.get $val) (i32.const 0))")
  (swap! (:indent cg) inc)
  (emit! cg "(then")
  (swap! (:indent cg) inc)
  (emit! cg "(local.set $negative (i32.const 1))")
  (emit! cg "(local.set $val (i32.sub (i32.const 0) (local.get $val)))))")
  (swap! (:indent cg) dec)
  (swap! (:indent cg) dec)
  (emit! cg "")
  (emit! cg ";; Handle zero")
  (emit! cg "(if (i32.eqz (local.get $val))")
  (swap! (:indent cg) inc)
  (emit! cg "(then")
  (swap! (:indent cg) inc)
  (emit! cg "(i32.store8 (local.get $buf_pos) (i32.const 48))") ;; '0'
  (emit! cg "(local.set $buf_pos (i32.sub (local.get $buf_pos) (i32.const 1))))")
  (swap! (:indent cg) dec)
  (swap! (:indent cg) dec)
  (emit! cg "(else")
  (swap! (:indent cg) inc)
  (emit! cg ";; Convert digits right-to-left")
  (emit! cg "(block $done")
  (swap! (:indent cg) inc)
  (emit! cg "(loop $digit_loop")
  (swap! (:indent cg) inc)
  (emit! cg "(br_if $done (i32.eqz (local.get $val)))")
  (emit! cg "(local.set $digit (i32.rem_u (local.get $val) (i32.const 10)))")
  (emit! cg "(i32.store8 (local.get $buf_pos) (i32.add (i32.const 48) (local.get $digit)))")
  (emit! cg "(local.set $val (i32.div_u (local.get $val) (i32.const 10)))")
  (emit! cg "(local.set $buf_pos (i32.sub (local.get $buf_pos) (i32.const 1)))")
  (emit! cg "(br $digit_loop)")
  (swap! (:indent cg) dec)
  (emit! cg ")")
  (swap! (:indent cg) dec)
  (emit! cg ")))")
  (swap! (:indent cg) dec)
  (emit! cg "")
  (emit! cg ";; Add negative sign")
  (emit! cg "(if (local.get $negative)")
  (swap! (:indent cg) inc)
  (emit! cg "(then")
  (swap! (:indent cg) inc)
  (emit! cg "(i32.store8 (local.get $buf_pos) (i32.const 45))") ;; '-'
  (emit! cg "(local.set $buf_pos (i32.sub (local.get $buf_pos) (i32.const 1))))")
  (swap! (:indent cg) dec)
  (swap! (:indent cg) dec)
  (emit! cg ")")
  (emit! cg "")
  (emit! cg ";; buf_pos+1 is the start, 47 is the last char")
  (emit! cg "(call $__print")
  (swap! (:indent cg) inc)
  (emit! cg "(i32.add (local.get $buf_pos) (i32.const 1))")
  (emit! cg "(i32.sub (i32.const 47) (local.get $buf_pos)))")
  (swap! (:indent cg) dec)
  (swap! (:indent cg) dec)
  (emit! cg ")")
  (emit! cg ""))

(defn- emit-wasi-print-i64!
  "Emit $__print_i64: convert i64 to decimal string and print."
  [cg]
  (emit! cg ";; Print i64 as decimal string")
  (emit! cg "(func $__print_i64 (param $val i64)")
  (swap! (:indent cg) inc)
  (emit! cg "(local $buf_pos i32)")
  (emit! cg "(local $negative i32)")
  (emit! cg "(local $digit i32)")
  (emit! cg "")
  (emit! cg "(local.set $buf_pos (i32.const 47))")
  (emit! cg "(local.set $negative (i32.const 0))")
  (emit! cg "(if (i64.lt_s (local.get $val) (i64.const 0))")
  (swap! (:indent cg) inc)
  (emit! cg "(then")
  (swap! (:indent cg) inc)
  (emit! cg "(local.set $negative (i32.const 1))")
  (emit! cg "(local.set $val (i64.sub (i64.const 0) (local.get $val)))))")
  (swap! (:indent cg) dec)
  (swap! (:indent cg) dec)
  (emit! cg "")
  (emit! cg "(if (i64.eqz (local.get $val))")
  (swap! (:indent cg) inc)
  (emit! cg "(then")
  (swap! (:indent cg) inc)
  (emit! cg "(i32.store8 (local.get $buf_pos) (i32.const 48))")
  (emit! cg "(local.set $buf_pos (i32.sub (local.get $buf_pos) (i32.const 1))))")
  (swap! (:indent cg) dec)
  (swap! (:indent cg) dec)
  (emit! cg "(else")
  (swap! (:indent cg) inc)
  (emit! cg "(block $done")
  (swap! (:indent cg) inc)
  (emit! cg "(loop $digit_loop")
  (swap! (:indent cg) inc)
  (emit! cg "(br_if $done (i64.eqz (local.get $val)))")
  (emit! cg "(local.set $digit (i32.wrap_i64 (i64.rem_u (local.get $val) (i64.const 10))))")
  (emit! cg "(i32.store8 (local.get $buf_pos) (i32.add (i32.const 48) (local.get $digit)))")
  (emit! cg "(local.set $val (i64.div_u (local.get $val) (i64.const 10)))")
  (emit! cg "(local.set $buf_pos (i32.sub (local.get $buf_pos) (i32.const 1)))")
  (emit! cg "(br $digit_loop)")
  (swap! (:indent cg) dec)
  (emit! cg ")")
  (swap! (:indent cg) dec)
  (emit! cg ")))")
  (swap! (:indent cg) dec)
  (emit! cg "")
  (emit! cg "(if (local.get $negative)")
  (swap! (:indent cg) inc)
  (emit! cg "(then")
  (swap! (:indent cg) inc)
  (emit! cg "(i32.store8 (local.get $buf_pos) (i32.const 45))")
  (emit! cg "(local.set $buf_pos (i32.sub (local.get $buf_pos) (i32.const 1))))")
  (swap! (:indent cg) dec)
  (swap! (:indent cg) dec)
  (emit! cg ")")
  (emit! cg "")
  (emit! cg "(call $__print")
  (swap! (:indent cg) inc)
  (emit! cg "(i32.add (local.get $buf_pos) (i32.const 1))")
  (emit! cg "(i32.sub (i32.const 47) (local.get $buf_pos)))")
  (swap! (:indent cg) dec)
  (swap! (:indent cg) dec)
  (emit! cg ")")
  (emit! cg ""))

(defn- emit-wasi-print-u64!
  "Emit $__print_u64: unsigned i64 to decimal string and print."
  [cg]
  (emit! cg ";; Print unsigned i64 as decimal string")
  (emit! cg "(func $__print_u64 (param $val i64)")
  (swap! (:indent cg) inc)
  (emit! cg "(local $buf_pos i32)")
  (emit! cg "(local $digit i32)")
  (emit! cg "")
  (emit! cg "(local.set $buf_pos (i32.const 47))")
  (emit! cg "(if (i64.eqz (local.get $val))")
  (swap! (:indent cg) inc)
  (emit! cg "(then")
  (swap! (:indent cg) inc)
  (emit! cg "(i32.store8 (local.get $buf_pos) (i32.const 48))")
  (emit! cg "(local.set $buf_pos (i32.sub (local.get $buf_pos) (i32.const 1))))")
  (swap! (:indent cg) dec)
  (swap! (:indent cg) dec)
  (emit! cg "(else")
  (swap! (:indent cg) inc)
  (emit! cg "(block $done")
  (swap! (:indent cg) inc)
  (emit! cg "(loop $digit_loop")
  (swap! (:indent cg) inc)
  (emit! cg "(br_if $done (i64.eqz (local.get $val)))")
  (emit! cg "(local.set $digit (i32.wrap_i64 (i64.rem_u (local.get $val) (i64.const 10))))")
  (emit! cg "(i32.store8 (local.get $buf_pos) (i32.add (i32.const 48) (local.get $digit)))")
  (emit! cg "(local.set $val (i64.div_u (local.get $val) (i64.const 10)))")
  (emit! cg "(local.set $buf_pos (i32.sub (local.get $buf_pos) (i32.const 1)))")
  (emit! cg "(br $digit_loop)")
  (swap! (:indent cg) dec)
  (emit! cg ")")
  (swap! (:indent cg) dec)
  (emit! cg ")))")
  (swap! (:indent cg) dec)
  (emit! cg "")
  (emit! cg "(call $__print")
  (swap! (:indent cg) inc)
  (emit! cg "(i32.add (local.get $buf_pos) (i32.const 1))")
  (emit! cg "(i32.sub (i32.const 47) (local.get $buf_pos)))")
  (swap! (:indent cg) dec)
  (swap! (:indent cg) dec)
  (emit! cg ")")
  (emit! cg ""))

(defn- emit-wasi-print-f64!
  "Emit $__print_f64: print f64 as integer.fractional (6 decimal places).
   Handles NaN, +/-Infinity, and values outside i64 range.
   Note: WASM uses strict IEEE 754 (no extended precision), while C on x86
   may use 80-bit extended precision internally. Results may differ in the
   last ULP between backends — this is expected, not a bug."
  [cg]
  (emit! cg ";; Print f64 (integer part + 6 decimal places)")
  (emit! cg ";; Handles NaN, Infinity, and overflow (|val| >= 2^53)")
  (emit! cg "(func $__print_f64 (param $val f64)")
  (swap! (:indent cg) inc)
  (emit! cg "(local $int_part i64)")
  (emit! cg "(local $frac_val i64)")
  (emit! cg "(local $negative i32)")
  (emit! cg "")
  ;; NaN check: val != val
  (emit! cg ";; NaN check: NaN != NaN")
  (emit! cg "(if (f64.ne (local.get $val) (local.get $val))")
  (swap! (:indent cg) inc)
  (emit! cg "(then")
  (swap! (:indent cg) inc)
  (emit! cg "(i32.store8 (i32.const 48) (i32.const 78))")   ;; 'N'
  (emit! cg "(i32.store8 (i32.const 49) (i32.const 97))")   ;; 'a'
  (emit! cg "(i32.store8 (i32.const 50) (i32.const 78))")   ;; 'N'
  (emit! cg "(call $__print (i32.const 48) (i32.const 3))")
  (emit! cg "(return)")
  (swap! (:indent cg) dec)
  (emit! cg "))")
  (swap! (:indent cg) dec)
  (emit! cg "")
  ;; +Infinity check
  (emit! cg ";; Infinity checks")
  (emit! cg "(if (f64.eq (local.get $val) (f64.const inf))")
  (swap! (:indent cg) inc)
  (emit! cg "(then")
  (swap! (:indent cg) inc)
  (emit! cg "(i32.store8 (i32.const 48) (i32.const 105))")  ;; 'i'
  (emit! cg "(i32.store8 (i32.const 49) (i32.const 110))")  ;; 'n'
  (emit! cg "(i32.store8 (i32.const 50) (i32.const 102))")  ;; 'f'
  (emit! cg "(call $__print (i32.const 48) (i32.const 3))")
  (emit! cg "(return)")
  (swap! (:indent cg) dec)
  (emit! cg "))")
  (swap! (:indent cg) dec)
  ;; -Infinity check
  (emit! cg "(if (f64.eq (local.get $val) (f64.const -inf))")
  (swap! (:indent cg) inc)
  (emit! cg "(then")
  (swap! (:indent cg) inc)
  (emit! cg "(i32.store8 (i32.const 48) (i32.const 45))")   ;; '-'
  (emit! cg "(i32.store8 (i32.const 49) (i32.const 105))")  ;; 'i'
  (emit! cg "(i32.store8 (i32.const 50) (i32.const 110))")  ;; 'n'
  (emit! cg "(i32.store8 (i32.const 51) (i32.const 102))")  ;; 'f'
  (emit! cg "(call $__print (i32.const 48) (i32.const 4))")
  (emit! cg "(return)")
  (swap! (:indent cg) dec)
  (emit! cg "))")
  (swap! (:indent cg) dec)
  (emit! cg "")
  (emit! cg ";; Handle negative")
  (emit! cg "(local.set $negative (i32.const 0))")
  (emit! cg "(if (f64.lt (local.get $val) (f64.const 0))")
  (swap! (:indent cg) inc)
  (emit! cg "(then")
  (swap! (:indent cg) inc)
  (emit! cg "(local.set $negative (i32.const 1))")
  (emit! cg "(local.set $val (f64.neg (local.get $val)))))")
  (swap! (:indent cg) dec)
  (swap! (:indent cg) dec)
  (emit! cg "")
  (emit! cg ";; Print sign")
  (emit! cg "(if (local.get $negative)")
  (swap! (:indent cg) inc)
  (emit! cg "(then (i32.store8 (i32.const 48) (i32.const 45))")
  (emit! cg " (call $__print (i32.const 48) (i32.const 1))))")
  (swap! (:indent cg) dec)
  (emit! cg "")
  ;; Overflow guard: for |val| >= 2^53, skip fractional (no meaningful precision)
  (emit! cg ";; Overflow guard: |val| >= 2^53 has no fractional precision")
  (emit! cg "(if (f64.ge (local.get $val) (f64.const 9007199254740992))")
  (swap! (:indent cg) inc)
  (emit! cg "(then")
  (swap! (:indent cg) inc)
  (emit! cg "(call $__print_i64 (i64.trunc_f64_s (local.get $val)))")
  (emit! cg "(return)")
  (swap! (:indent cg) dec)
  (emit! cg "))")
  (swap! (:indent cg) dec)
  (emit! cg "")
  (emit! cg ";; Integer part")
  (emit! cg "(local.set $int_part (i64.trunc_f64_s (local.get $val)))")
  (emit! cg "(call $__print_i64 (local.get $int_part))")
  (emit! cg "")
  (emit! cg ";; Decimal point")
  (emit! cg "(i32.store8 (i32.const 48) (i32.const 46))") ;; '.'
  (emit! cg "(call $__print (i32.const 48) (i32.const 1))")
  (emit! cg "")
  (emit! cg ";; Fractional part (6 digits)")
  (emit! cg "(local.set $frac_val (i64.trunc_f64_s (f64.mul")
  (swap! (:indent cg) inc)
  (emit! cg "(f64.sub (local.get $val) (f64.convert_i64_s (local.get $int_part)))")
  (emit! cg "(f64.const 1000000))))")
  (swap! (:indent cg) dec)
  (emit! cg ";; Ensure positive")
  (emit! cg "(if (i64.lt_s (local.get $frac_val) (i64.const 0))")
  (swap! (:indent cg) inc)
  (emit! cg "(then (local.set $frac_val (i64.sub (i64.const 0) (local.get $frac_val)))))")
  (swap! (:indent cg) dec)
  (emit! cg ";; Print with leading zeros")
  (emit! cg "(if (i64.lt_u (local.get $frac_val) (i64.const 100000))")
  (swap! (:indent cg) inc)
  (emit! cg "(then (i32.store8 (i32.const 48) (i32.const 48)) (call $__print (i32.const 48) (i32.const 1))))")
  (swap! (:indent cg) dec)
  (emit! cg "(if (i64.lt_u (local.get $frac_val) (i64.const 10000))")
  (swap! (:indent cg) inc)
  (emit! cg "(then (i32.store8 (i32.const 48) (i32.const 48)) (call $__print (i32.const 48) (i32.const 1))))")
  (swap! (:indent cg) dec)
  (emit! cg "(if (i64.lt_u (local.get $frac_val) (i64.const 1000))")
  (swap! (:indent cg) inc)
  (emit! cg "(then (i32.store8 (i32.const 48) (i32.const 48)) (call $__print (i32.const 48) (i32.const 1))))")
  (swap! (:indent cg) dec)
  (emit! cg "(if (i64.lt_u (local.get $frac_val) (i64.const 100))")
  (swap! (:indent cg) inc)
  (emit! cg "(then (i32.store8 (i32.const 48) (i32.const 48)) (call $__print (i32.const 48) (i32.const 1))))")
  (swap! (:indent cg) dec)
  (emit! cg "(if (i64.lt_u (local.get $frac_val) (i64.const 10))")
  (swap! (:indent cg) inc)
  (emit! cg "(then (i32.store8 (i32.const 48) (i32.const 48)) (call $__print (i32.const 48) (i32.const 1))))")
  (swap! (:indent cg) dec)
  (emit! cg "(call $__print_i64 (local.get $frac_val))")
  (swap! (:indent cg) dec)
  (emit! cg ")")
  (emit! cg ""))

(defn- emit-wasi-print-f64e-impl!
  "Emit scientific notation print: d.ddddddeFdd.
   func-name is the WAT function name, e-char is the ASCII code for 'e' or 'E'."
  [cg func-name e-char]
  (emit! cg (str ";; Print f64 in scientific notation (" (if (= e-char 101) "e" "E") ")"))
  (emit! cg (str "(func " func-name " (param $val f64)"))
  (swap! (:indent cg) inc)
  (emit! cg "(local $exponent i32)")
  (emit! cg "(local $int_part i64)")
  (emit! cg "(local $frac_val i64)")
  (emit! cg "(local $negative i32)")
  (emit! cg "(local $abs_exp i32)")
  (emit! cg "")
  ;; NaN check
  (emit! cg ";; NaN check")
  (emit! cg "(if (f64.ne (local.get $val) (local.get $val))")
  (swap! (:indent cg) inc)
  (emit! cg "(then")
  (swap! (:indent cg) inc)
  (emit! cg "(i32.store8 (i32.const 48) (i32.const 78))")   ;; 'N'
  (emit! cg "(i32.store8 (i32.const 49) (i32.const 97))")   ;; 'a'
  (emit! cg "(i32.store8 (i32.const 50) (i32.const 78))")   ;; 'N'
  (emit! cg "(call $__print (i32.const 48) (i32.const 3))")
  (emit! cg "(return)")
  (swap! (:indent cg) dec)
  (emit! cg "))")
  (swap! (:indent cg) dec)
  (emit! cg "")
  ;; +Infinity check
  (emit! cg ";; Infinity checks")
  (emit! cg "(if (f64.eq (local.get $val) (f64.const inf))")
  (swap! (:indent cg) inc)
  (emit! cg "(then")
  (swap! (:indent cg) inc)
  (emit! cg "(i32.store8 (i32.const 48) (i32.const 105))")  ;; 'i'
  (emit! cg "(i32.store8 (i32.const 49) (i32.const 110))")  ;; 'n'
  (emit! cg "(i32.store8 (i32.const 50) (i32.const 102))")  ;; 'f'
  (emit! cg "(call $__print (i32.const 48) (i32.const 3))")
  (emit! cg "(return)")
  (swap! (:indent cg) dec)
  (emit! cg "))")
  (swap! (:indent cg) dec)
  ;; -Infinity check
  (emit! cg "(if (f64.eq (local.get $val) (f64.const -inf))")
  (swap! (:indent cg) inc)
  (emit! cg "(then")
  (swap! (:indent cg) inc)
  (emit! cg "(i32.store8 (i32.const 48) (i32.const 45))")   ;; '-'
  (emit! cg "(i32.store8 (i32.const 49) (i32.const 105))")  ;; 'i'
  (emit! cg "(i32.store8 (i32.const 50) (i32.const 110))")  ;; 'n'
  (emit! cg "(i32.store8 (i32.const 51) (i32.const 102))")  ;; 'f'
  (emit! cg "(call $__print (i32.const 48) (i32.const 4))")
  (emit! cg "(return)")
  (swap! (:indent cg) dec)
  (emit! cg "))")
  (swap! (:indent cg) dec)
  (emit! cg "")
  ;; Handle sign
  (emit! cg ";; Handle negative")
  (emit! cg "(local.set $negative (i32.const 0))")
  (emit! cg "(if (f64.lt (local.get $val) (f64.const 0))")
  (swap! (:indent cg) inc)
  (emit! cg "(then")
  (swap! (:indent cg) inc)
  (emit! cg "(local.set $negative (i32.const 1))")
  (emit! cg "(local.set $val (f64.neg (local.get $val)))))")
  (swap! (:indent cg) dec)
  (swap! (:indent cg) dec)
  (emit! cg "")
  (emit! cg ";; Print sign")
  (emit! cg "(if (local.get $negative)")
  (swap! (:indent cg) inc)
  (emit! cg "(then (i32.store8 (i32.const 48) (i32.const 45))")
  (emit! cg " (call $__print (i32.const 48) (i32.const 1))))")
  (swap! (:indent cg) dec)
  (emit! cg "")
  ;; Handle zero specially
  (emit! cg ";; Handle zero: 0.000000e+00")
  (emit! cg "(if (f64.eq (local.get $val) (f64.const 0))")
  (swap! (:indent cg) inc)
  (emit! cg "(then")
  (swap! (:indent cg) inc)
  (emit! cg "(i32.store8 (i32.const 48) (i32.const 48))")   ;; '0'
  (emit! cg "(i32.store8 (i32.const 49) (i32.const 46))")   ;; '.'
  (emit! cg "(i32.store8 (i32.const 50) (i32.const 48))")   ;; '0' x6
  (emit! cg "(i32.store8 (i32.const 51) (i32.const 48))")
  (emit! cg "(i32.store8 (i32.const 52) (i32.const 48))")
  (emit! cg "(i32.store8 (i32.const 53) (i32.const 48))")
  (emit! cg "(i32.store8 (i32.const 54) (i32.const 48))")
  (emit! cg "(i32.store8 (i32.const 55) (i32.const 48))")
  (emit! cg (str "(i32.store8 (i32.const 56) (i32.const " e-char "))"))  ;; 'e'/'E'
  (emit! cg "(i32.store8 (i32.const 57) (i32.const 43))")   ;; '+'
  (emit! cg "(i32.store8 (i32.const 58) (i32.const 48))")   ;; '0'
  (emit! cg "(i32.store8 (i32.const 59) (i32.const 48))")   ;; '0'
  (emit! cg "(call $__print (i32.const 48) (i32.const 12))")
  (emit! cg "(return)")
  (swap! (:indent cg) dec)
  (emit! cg "))")
  (swap! (:indent cg) dec)
  (emit! cg "")
  ;; Normalize: bring val into [1.0, 10.0)
  (emit! cg ";; Normalize to [1.0, 10.0) — count exponent")
  (emit! cg "(local.set $exponent (i32.const 0))")
  (emit! cg "")
  ;; Scale up: val < 1.0 → multiply by 10, decrement exponent
  (emit! cg ";; Scale up if val < 1.0")
  (emit! cg "(block $scale_up_done")
  (swap! (:indent cg) inc)
  (emit! cg "(loop $scale_up")
  (swap! (:indent cg) inc)
  (emit! cg "(br_if $scale_up_done (f64.ge (local.get $val) (f64.const 1.0)))")
  (emit! cg "(local.set $val (f64.mul (local.get $val) (f64.const 10.0)))")
  (emit! cg "(local.set $exponent (i32.sub (local.get $exponent) (i32.const 1)))")
  (emit! cg "(br $scale_up)")
  (swap! (:indent cg) dec)
  (emit! cg "))")
  (swap! (:indent cg) dec)
  (emit! cg "")
  ;; Scale down: val >= 10.0 → divide by 10, increment exponent
  (emit! cg ";; Scale down if val >= 10.0")
  (emit! cg "(block $scale_down_done")
  (swap! (:indent cg) inc)
  (emit! cg "(loop $scale_down")
  (swap! (:indent cg) inc)
  (emit! cg "(br_if $scale_down_done (f64.lt (local.get $val) (f64.const 10.0)))")
  (emit! cg "(local.set $val (f64.div (local.get $val) (f64.const 10.0)))")
  (emit! cg "(local.set $exponent (i32.add (local.get $exponent) (i32.const 1)))")
  (emit! cg "(br $scale_down)")
  (swap! (:indent cg) dec)
  (emit! cg "))")
  (swap! (:indent cg) dec)
  (emit! cg "")
  ;; Now val is in [1.0, 10.0), print mantissa as d.dddddd
  (emit! cg ";; Print mantissa: integer digit")
  (emit! cg "(local.set $int_part (i64.trunc_f64_s (local.get $val)))")
  (emit! cg "(call $__print_i64 (local.get $int_part))")
  (emit! cg "")
  (emit! cg ";; Decimal point")
  (emit! cg "(i32.store8 (i32.const 48) (i32.const 46))") ;; '.'
  (emit! cg "(call $__print (i32.const 48) (i32.const 1))")
  (emit! cg "")
  ;; Fractional part: 6 digits
  (emit! cg ";; Fractional part (6 digits)")
  (emit! cg "(local.set $frac_val (i64.trunc_f64_s (f64.mul")
  (swap! (:indent cg) inc)
  (emit! cg "(f64.sub (local.get $val) (f64.convert_i64_s (local.get $int_part)))")
  (emit! cg "(f64.const 1000000))))")
  (swap! (:indent cg) dec)
  (emit! cg ";; Ensure positive")
  (emit! cg "(if (i64.lt_s (local.get $frac_val) (i64.const 0))")
  (swap! (:indent cg) inc)
  (emit! cg "(then (local.set $frac_val (i64.sub (i64.const 0) (local.get $frac_val)))))")
  (swap! (:indent cg) dec)
  ;; Leading zeros for fractional part
  (emit! cg ";; Print with leading zeros")
  (emit! cg "(if (i64.lt_u (local.get $frac_val) (i64.const 100000))")
  (swap! (:indent cg) inc)
  (emit! cg "(then (i32.store8 (i32.const 48) (i32.const 48)) (call $__print (i32.const 48) (i32.const 1))))")
  (swap! (:indent cg) dec)
  (emit! cg "(if (i64.lt_u (local.get $frac_val) (i64.const 10000))")
  (swap! (:indent cg) inc)
  (emit! cg "(then (i32.store8 (i32.const 48) (i32.const 48)) (call $__print (i32.const 48) (i32.const 1))))")
  (swap! (:indent cg) dec)
  (emit! cg "(if (i64.lt_u (local.get $frac_val) (i64.const 1000))")
  (swap! (:indent cg) inc)
  (emit! cg "(then (i32.store8 (i32.const 48) (i32.const 48)) (call $__print (i32.const 48) (i32.const 1))))")
  (swap! (:indent cg) dec)
  (emit! cg "(if (i64.lt_u (local.get $frac_val) (i64.const 100))")
  (swap! (:indent cg) inc)
  (emit! cg "(then (i32.store8 (i32.const 48) (i32.const 48)) (call $__print (i32.const 48) (i32.const 1))))")
  (swap! (:indent cg) dec)
  (emit! cg "(if (i64.lt_u (local.get $frac_val) (i64.const 10))")
  (swap! (:indent cg) inc)
  (emit! cg "(then (i32.store8 (i32.const 48) (i32.const 48)) (call $__print (i32.const 48) (i32.const 1))))")
  (swap! (:indent cg) dec)
  (emit! cg "(call $__print_i64 (local.get $frac_val))")
  (emit! cg "")
  ;; Print 'e'/'E' and sign
  (emit! cg ";; Print exponent separator and sign")
  (emit! cg (str "(i32.store8 (i32.const 48) (i32.const " e-char "))"))
  (emit! cg "(if (i32.lt_s (local.get $exponent) (i32.const 0))")
  (swap! (:indent cg) inc)
  (emit! cg "(then")
  (swap! (:indent cg) inc)
  (emit! cg "(i32.store8 (i32.const 49) (i32.const 45))")   ;; '-'
  (emit! cg "(local.set $abs_exp (i32.sub (i32.const 0) (local.get $exponent))))")
  (swap! (:indent cg) dec)
  (emit! cg "(else")
  (swap! (:indent cg) inc)
  (emit! cg "(i32.store8 (i32.const 49) (i32.const 43))")   ;; '+'
  (emit! cg "(local.set $abs_exp (local.get $exponent))))")
  (swap! (:indent cg) dec)
  (swap! (:indent cg) dec)
  (emit! cg "(call $__print (i32.const 48) (i32.const 2))")
  (emit! cg "")
  ;; Print exponent with at least 2 digits (e.g., e+02)
  (emit! cg ";; Print exponent (at least 2 digits)")
  (emit! cg "(if (i32.lt_u (local.get $abs_exp) (i32.const 10))")
  (swap! (:indent cg) inc)
  (emit! cg "(then (i32.store8 (i32.const 48) (i32.const 48)) (call $__print (i32.const 48) (i32.const 1))))")
  (swap! (:indent cg) dec)
  (emit! cg "(call $__print_i32 (local.get $abs_exp))")
  (swap! (:indent cg) dec)
  (emit! cg ")")
  (emit! cg ""))

(defn- emit-wasi-print-f64g-impl!
  "Emit %g/%G: use fixed-point if exponent in [-4, 6), else scientific notation.
   func-name is the WAT function name, f64e-func is the matching scientific func."
  [cg func-name f64e-func]
  (emit! cg (str ";; Print f64 with auto-format (g/G): fixed or scientific"))
  (emit! cg (str "(func " func-name " (param $val f64)"))
  (swap! (:indent cg) inc)
  (emit! cg "(local $abs_val f64)")
  (emit! cg "")
  ;; NaN → delegate
  (emit! cg ";; NaN/Inf/zero: delegate to scientific (handles all edge cases)")
  (emit! cg "(if (f64.ne (local.get $val) (local.get $val))")
  (swap! (:indent cg) inc)
  (emit! cg (str "(then (call " f64e-func " (local.get $val)) (return)))"))
  (swap! (:indent cg) dec)
  (emit! cg "(if (f64.eq (local.get $val) (f64.const inf))")
  (swap! (:indent cg) inc)
  (emit! cg (str "(then (call " f64e-func " (local.get $val)) (return)))"))
  (swap! (:indent cg) dec)
  (emit! cg "(if (f64.eq (local.get $val) (f64.const -inf))")
  (swap! (:indent cg) inc)
  (emit! cg (str "(then (call " f64e-func " (local.get $val)) (return)))"))
  (swap! (:indent cg) dec)
  (emit! cg "(if (f64.eq (local.get $val) (f64.const 0))")
  (swap! (:indent cg) inc)
  (emit! cg (str "(then (call " f64e-func " (local.get $val)) (return)))"))
  (swap! (:indent cg) dec)
  (emit! cg "")
  ;; Get absolute value for range check
  (emit! cg ";; Compute |val| for range check")
  (emit! cg "(local.set $abs_val (f64.abs (local.get $val)))")
  (emit! cg "")
  ;; If abs_val < 0.0001 or abs_val >= 1000000 → use scientific
  (emit! cg ";; Use scientific if exponent < -4 or >= 6")
  (emit! cg "(if (f64.lt (local.get $abs_val) (f64.const 0.0001))")
  (swap! (:indent cg) inc)
  (emit! cg (str "(then (call " f64e-func " (local.get $val)) (return)))"))
  (swap! (:indent cg) dec)
  (emit! cg "(if (f64.ge (local.get $abs_val) (f64.const 1000000))")
  (swap! (:indent cg) inc)
  (emit! cg (str "(then (call " f64e-func " (local.get $val)) (return)))"))
  (swap! (:indent cg) dec)
  (emit! cg "")
  ;; Otherwise use fixed-point
  (emit! cg ";; In range: use fixed-point format")
  (emit! cg "(call $__print_f64 (local.get $val))")
  (swap! (:indent cg) dec)
  (emit! cg ")")
  (emit! cg ""))

(defn- emit-wasi-print-u32!
  "Emit $__print_u32: unsigned i32 to decimal string and print."
  [cg]
  (emit! cg ";; Print unsigned i32 as decimal string")
  (emit! cg "(func $__print_u32 (param $val i32)")
  (swap! (:indent cg) inc)
  (emit! cg "(local $buf_pos i32)")
  (emit! cg "(local $digit i32)")
  (emit! cg "")
  (emit! cg "(local.set $buf_pos (i32.const 47))")
  (emit! cg "(if (i32.eqz (local.get $val))")
  (swap! (:indent cg) inc)
  (emit! cg "(then")
  (swap! (:indent cg) inc)
  (emit! cg "(i32.store8 (local.get $buf_pos) (i32.const 48))")
  (emit! cg "(local.set $buf_pos (i32.sub (local.get $buf_pos) (i32.const 1))))")
  (swap! (:indent cg) dec)
  (swap! (:indent cg) dec)
  (emit! cg "(else")
  (swap! (:indent cg) inc)
  (emit! cg "(block $done")
  (swap! (:indent cg) inc)
  (emit! cg "(loop $digit_loop")
  (swap! (:indent cg) inc)
  (emit! cg "(br_if $done (i32.eqz (local.get $val)))")
  (emit! cg "(local.set $digit (i32.rem_u (local.get $val) (i32.const 10)))")
  (emit! cg "(i32.store8 (local.get $buf_pos) (i32.add (i32.const 48) (local.get $digit)))")
  (emit! cg "(local.set $val (i32.div_u (local.get $val) (i32.const 10)))")
  (emit! cg "(local.set $buf_pos (i32.sub (local.get $buf_pos) (i32.const 1)))")
  (emit! cg "(br $digit_loop)")
  (swap! (:indent cg) dec)
  (emit! cg ")")
  (swap! (:indent cg) dec)
  (emit! cg ")))")
  (swap! (:indent cg) dec)
  (emit! cg "")
  (emit! cg "(call $__print")
  (swap! (:indent cg) inc)
  (emit! cg "(i32.add (local.get $buf_pos) (i32.const 1))")
  (emit! cg "(i32.sub (i32.const 47) (local.get $buf_pos)))")
  (swap! (:indent cg) dec)
  (swap! (:indent cg) dec)
  (emit! cg ")")
  (emit! cg ""))

(defn- emit-wasi-print-hex-impl!
  "Emit hex print function with given name and base char for a-f/A-F."
  [cg func-name alpha-base]
  (emit! cg (str ";; Print i32 as " (if (= alpha-base 97) "lowercase" "uppercase") " hex"))
  (emit! cg (str "(func " func-name " (param $val i32)"))
  (swap! (:indent cg) inc)
  (emit! cg "(local $buf_pos i32)")
  (emit! cg "(local $digit i32)")
  (emit! cg "")
  (emit! cg "(local.set $buf_pos (i32.const 47))")
  (emit! cg "(if (i32.eqz (local.get $val))")
  (swap! (:indent cg) inc)
  (emit! cg "(then")
  (swap! (:indent cg) inc)
  (emit! cg "(i32.store8 (local.get $buf_pos) (i32.const 48))")
  (emit! cg "(local.set $buf_pos (i32.sub (local.get $buf_pos) (i32.const 1))))")
  (swap! (:indent cg) dec)
  (swap! (:indent cg) dec)
  (emit! cg "(else")
  (swap! (:indent cg) inc)
  (emit! cg "(block $done")
  (swap! (:indent cg) inc)
  (emit! cg "(loop $digit_loop")
  (swap! (:indent cg) inc)
  (emit! cg "(br_if $done (i32.eqz (local.get $val)))")
  (emit! cg "(local.set $digit (i32.and (local.get $val) (i32.const 15)))")
  (emit! cg "(if (i32.lt_u (local.get $digit) (i32.const 10))")
  (swap! (:indent cg) inc)
  (emit! cg "(then (i32.store8 (local.get $buf_pos) (i32.add (i32.const 48) (local.get $digit))))")
  (emit! cg (str "(else (i32.store8 (local.get $buf_pos) (i32.add (i32.const " alpha-base ") (i32.sub (local.get $digit) (i32.const 10))))))"))
  (swap! (:indent cg) dec)
  (emit! cg "(local.set $val (i32.shr_u (local.get $val) (i32.const 4)))")
  (emit! cg "(local.set $buf_pos (i32.sub (local.get $buf_pos) (i32.const 1)))")
  (emit! cg "(br $digit_loop)")
  (swap! (:indent cg) dec)
  (emit! cg ")")
  (swap! (:indent cg) dec)
  (emit! cg ")))")
  (swap! (:indent cg) dec)
  (emit! cg "")
  (emit! cg "(call $__print")
  (swap! (:indent cg) inc)
  (emit! cg "(i32.add (local.get $buf_pos) (i32.const 1))")
  (emit! cg "(i32.sub (i32.const 47) (local.get $buf_pos)))")
  (swap! (:indent cg) dec)
  (swap! (:indent cg) dec)
  (emit! cg ")")
  (emit! cg ""))

(defn- emit-wasi-print-oct!
  "Emit $__print_oct: i32 as octal string."
  [cg]
  (emit! cg ";; Print i32 as octal string")
  (emit! cg "(func $__print_oct (param $val i32)")
  (swap! (:indent cg) inc)
  (emit! cg "(local $buf_pos i32)")
  (emit! cg "(local $digit i32)")
  (emit! cg "")
  (emit! cg "(local.set $buf_pos (i32.const 47))")
  (emit! cg "(if (i32.eqz (local.get $val))")
  (swap! (:indent cg) inc)
  (emit! cg "(then")
  (swap! (:indent cg) inc)
  (emit! cg "(i32.store8 (local.get $buf_pos) (i32.const 48))")
  (emit! cg "(local.set $buf_pos (i32.sub (local.get $buf_pos) (i32.const 1))))")
  (swap! (:indent cg) dec)
  (swap! (:indent cg) dec)
  (emit! cg "(else")
  (swap! (:indent cg) inc)
  (emit! cg "(block $done")
  (swap! (:indent cg) inc)
  (emit! cg "(loop $digit_loop")
  (swap! (:indent cg) inc)
  (emit! cg "(br_if $done (i32.eqz (local.get $val)))")
  (emit! cg "(local.set $digit (i32.and (local.get $val) (i32.const 7)))")
  (emit! cg "(i32.store8 (local.get $buf_pos) (i32.add (i32.const 48) (local.get $digit)))")
  (emit! cg "(local.set $val (i32.shr_u (local.get $val) (i32.const 3)))")
  (emit! cg "(local.set $buf_pos (i32.sub (local.get $buf_pos) (i32.const 1)))")
  (emit! cg "(br $digit_loop)")
  (swap! (:indent cg) dec)
  (emit! cg ")")
  (swap! (:indent cg) dec)
  (emit! cg ")))")
  (swap! (:indent cg) dec)
  (emit! cg "")
  (emit! cg "(call $__print")
  (swap! (:indent cg) inc)
  (emit! cg "(i32.add (local.get $buf_pos) (i32.const 1))")
  (emit! cg "(i32.sub (i32.const 47) (local.get $buf_pos)))")
  (swap! (:indent cg) dec)
  (swap! (:indent cg) dec)
  (emit! cg ")")
  (emit! cg ""))

(defn- emit-wasi-print-char!
  "Emit $__print_char: print a single ASCII character from an i32 value."
  [cg]
  (emit! cg ";; Print single ASCII character from i32 value")
  (emit! cg "(func $__print_char (param $val i32)")
  (swap! (:indent cg) inc)
  (emit! cg ";; Store byte at scratch offset 16, print 1 byte")
  (emit! cg "(i32.store8 (i32.const 16) (local.get $val))")
  (emit! cg "(call $__print (i32.const 16) (i32.const 1))")
  (swap! (:indent cg) dec)
  (emit! cg ")")
  (emit! cg ""))

(defn- emit-wasi-print-ptr!
  "Emit $__print_ptr: print i32 as hex with 0x prefix."
  [cg]
  (emit! cg ";; Print i32 as pointer (0x prefix + lowercase hex)")
  (emit! cg "(func $__print_ptr (param $val i32)")
  (swap! (:indent cg) inc)
  (emit! cg ";; Store '0' and 'x' at scratch offsets 16-17, print prefix")
  (emit! cg "(i32.store8 (i32.const 16) (i32.const 48))")   ;; '0'
  (emit! cg "(i32.store8 (i32.const 17) (i32.const 120))")  ;; 'x'
  (emit! cg "(call $__print (i32.const 16) (i32.const 2))")
  (emit! cg ";; Print hex digits")
  (emit! cg "(call $__print_hex (local.get $val))")
  (swap! (:indent cg) dec)
  (emit! cg ")")
  (emit! cg ""))

(defn- emit-wasi-start!
  "Emit _start function that calls $main and exits with its return code."
  [cg has-main-result? globals-needing-init]
  (emit! cg ";; WASI entry point")
  (emit! cg "(func $__start (export \"_start\")")
  (swap! (:indent cg) inc)
  ;; Initialize globals with non-literal expressions before main
  (when (seq globals-needing-init)
    (doseq [g globals-needing-init]
      (emit-expr! cg (:init g))
      (emit! cg (str "(global.set " (var->wat (:name g)) ")"))))
  (if has-main-result?
    (do (emit! cg "(call $main)")
        (emit! cg "(call $proc_exit)"))
    (emit! cg "(call $main)"))
  (swap! (:indent cg) dec)
  (emit! cg ")")
  (emit! cg ""))

;; ── Top-Level ────────────────────────────────────────────────

(defn generate
  "Generate complete WAT source from an ARIA module AST.
   Options:
     :wasi? — emit WASI-compatible module with built-in print runtime
              and _start entry point (default: false, uses host imports)"
  ([module] (generate module {}))
  ([module {:keys [wasi? allocator] :or {wasi? false allocator :free-list}}]
   (let [;; Phase 1: Generate function bodies to determine needs
         func-cg (make-codegen)
         export-names (set (map :name (:exports module)))]

     ;; Register struct types from module type definitions
     (doseq [td (:types module)]
       (when (= :struct (:type/kind (:aria/type td)))
         (swap! (:struct-types func-cg) assoc (:name td) (:aria/type td))))

     ;; Register global variables
     (doseq [g (:globals module)]
       (let [wat-type (or (type->wat (:aria/type g)) "i32")]
         (swap! (:global-names func-cg) assoc (:name g)
                {:wat-type wat-type :aria-type (:aria/type g) :mutable? (:mutable? g)})
         ;; Register in local-types for pointer arithmetic inference
         (swap! (:local-types func-cg) assoc (:name g) (:aria/type g))))

     ;; Register function signatures for call/drop handling
     (doseq [func (:functions module)]
       (swap! (:func-sigs func-cg) assoc (:name func) {:result (:result func)}))
     (doseq [ext (or (:externs module) [])]
       (swap! (:func-sigs func-cg) assoc (:name ext) {:result (:result ext)}))

     ;; Set indent to 1 (inside module)
     (reset! (:indent func-cg) 1)

     ;; Generate all functions
     (doseq [func (:functions module)]
       (emit-function! func-cg func export-names))

     ;; Phase 2: Build complete module
     (let [cg (make-codegen)
           needs-print? @(:needs-print func-cg)
           needs-alloc? @(:needs-alloc func-cg)
           needs-fmt-buf? @(:needs-fmt-buf func-cg)
           needs-f64-prec? @(:needs-f64-prec func-cg)
           print-types @(:print-types func-cg)
           heap-start @(:data-offset func-cg)
           has-main? (some #(= (:name %) "$main") (:functions module))
           main-result? (when has-main?
                          (some-> (first (filter #(= (:name %) "$main") (:functions module)))
                                  :result))
           globals-needing-init (filter (fn [g]
                                          (when-let [init (:init g)]
                                            (not (#{:int-literal :float-literal :bool-literal}
                                                  (:node/type init)))))
                                        (:globals module))]

       (emit-raw! cg (str ";; Generated from ARIA module: " (:name module)))
       (emit-raw! cg (str ";; ARIA-WAT Transpiler v0.1" (when wasi? " (WASI)")))
       (emit-raw! cg "")
       (emit! cg "(module")
       (swap! (:indent cg) inc)

       ;; WASI imports
       (when (and wasi? needs-print?)
         (emit! cg "(import \"wasi_snapshot_preview1\" \"fd_write\" (func $fd_write (param i32 i32 i32 i32) (result i32)))")
         (when (and has-main? main-result?)
           (emit! cg "(import \"wasi_snapshot_preview1\" \"proc_exit\" (func $proc_exit (param i32)))"))
         (emit! cg ""))

       ;; Non-WASI: host imports for print
       (when (and (not wasi?) needs-print?)
         (emit! cg "(import \"env\" \"print\" (func $__print (param i32) (param i32)))")
         (when (contains? print-types :i32)
           (emit! cg "(import \"env\" \"print_i32\" (func $__print_i32 (param i32)))"))
         (when (contains? print-types :u32)
           (emit! cg "(import \"env\" \"print_u32\" (func $__print_u32 (param i32)))"))
         (when (contains? print-types :hex)
           (emit! cg "(import \"env\" \"print_hex\" (func $__print_hex (param i32)))"))
         (when (contains? print-types :HEX)
           (emit! cg "(import \"env\" \"print_HEX\" (func $__print_HEX (param i32)))"))
         (when (contains? print-types :oct)
           (emit! cg "(import \"env\" \"print_oct\" (func $__print_oct (param i32)))"))
         (when (contains? print-types :i64)
           (emit! cg "(import \"env\" \"print_i64\" (func $__print_i64 (param i64)))"))
         (when (contains? print-types :u64)
           (emit! cg "(import \"env\" \"print_u64\" (func $__print_u64 (param i64)))"))
         (when (contains? print-types :f64)
           (emit! cg "(import \"env\" \"print_f64\" (func $__print_f64 (param f64)))"))
         (emit! cg ""))

       ;; WASI without print: still need proc_exit if main returns a value
       (when (and wasi? (not needs-print?) has-main? main-result?)
         (emit! cg "(import \"wasi_snapshot_preview1\" \"proc_exit\" (func $proc_exit (param i32)))")
         (emit! cg ""))

       ;; Extern imports
       (doseq [ext (or (:externs module) [])]
         (let [ext-name (var->wat (:name ext))
               params-str (str/join " "
                                    (map #(str "(param " (or (type->wat (:param/type %)) "i32") ")")
                                         (:params ext)))
               result-str (when (:result ext)
                            (when-let [wt (type->wat (:result ext))]
                              (str " (result " wt ")")))]
           (emit! cg (str "(import \"" (:extern/module ext) "\" \"" (subs ext-name 1) "\""
                           " (func " ext-name
                           (when (seq params-str) (str " " params-str))
                           result-str "))"))))
       (when (seq (:externs module))
         (emit! cg ""))

       ;; Memory declaration
       (emit! cg "(memory (export \"memory\") 1)")
       (emit! cg "")

       ;; Stack pointer global (if alloc is used)
       (when needs-alloc?
         (emit! cg (str "(global $__stack_ptr (mut i32) (i32.const " heap-start "))"))
         (when (= allocator :free-list)
           (emit! cg "(global $__free_list (mut i32) (i32.const 0))"))
         (emit! cg ""))

       ;; Format buffer globals (for width/precision support)
       (when needs-fmt-buf?
         (emit! cg "(global $__buf_mode (mut i32) (i32.const 0))")
         (emit! cg "(global $__buf_len  (mut i32) (i32.const 0))")
         (emit! cg ""))

       ;; User-defined global variables
       (when (seq (:globals module))
         (doseq [g (:globals module)]
           (let [wat-name (var->wat (:name g))
                 wat-type (or (type->wat (:aria/type g)) "i32")
                 init-expr (global-init-expr g wat-type)]
             (if (:mutable? g)
               (emit! cg (str "(global " wat-name " (mut " wat-type ") " init-expr ")"))
               (emit! cg (str "(global " wat-name " " wat-type " " init-expr ")")))))
         (emit! cg ""))

       ;; Alloc function
       (when needs-alloc?
         (emit-alloc-function! cg allocator))

       ;; WASI print runtime functions
       (when (and wasi? needs-print?)
         (emit-wasi-print! cg needs-fmt-buf?)
         (when (contains? print-types :i32)
           (emit-wasi-print-i32! cg))
         (when (contains? print-types :u32)
           (emit-wasi-print-u32! cg))
         (when (contains? print-types :hex)
           (emit-wasi-print-hex-impl! cg "$__print_hex" 97))  ;; 'a'
         (when (contains? print-types :HEX)
           (emit-wasi-print-hex-impl! cg "$__print_HEX" 65))  ;; 'A'
         (when (contains? print-types :oct)
           (emit-wasi-print-oct! cg))
         (when (contains? print-types :i64)
           (emit-wasi-print-i64! cg))
         (when (contains? print-types :u64)
           (emit-wasi-print-u64! cg))
         (when (contains? print-types :f64)
           (when-not (or (contains? print-types :i64) (contains? print-types :u64))
             (emit-wasi-print-i64! cg))  ;; i64 needed by f64
           (emit-wasi-print-f64! cg))
         (when (contains? print-types :char)
           (emit-wasi-print-char! cg))
         (when (contains? print-types :ptr)
           ;; ptr depends on $__print_hex — emit it if not already emitted
           (when-not (contains? print-types :hex)
             (emit-wasi-print-hex-impl! cg "$__print_hex" 97))
           (emit-wasi-print-ptr! cg))
         ;; Scientific notation: %e/%E depend on i64 and f64 helpers
         (when (or (contains? print-types :f64e) (contains? print-types :f64E)
                   (contains? print-types :f64g) (contains? print-types :f64G))
           (when-not (or (contains? print-types :i64) (contains? print-types :u64)
                         (contains? print-types :f64))
             (emit-wasi-print-i64! cg)))  ;; i64 needed by scientific notation
         (when (contains? print-types :f64e)
           (emit-wasi-print-f64e-impl! cg "$__print_f64e" 101))  ;; 'e'
         (when (contains? print-types :f64E)
           (emit-wasi-print-f64e-impl! cg "$__print_f64E" 69))   ;; 'E'
         (when (contains? print-types :f64g)
           ;; %g depends on both $__print_f64 and $__print_f64e
           (when-not (contains? print-types :f64)
             (when-not (or (contains? print-types :i64) (contains? print-types :u64))
               (emit-wasi-print-i64! cg))
             (emit-wasi-print-f64! cg))
           (when-not (contains? print-types :f64e)
             (emit-wasi-print-f64e-impl! cg "$__print_f64e" 101))
           (emit-wasi-print-f64g-impl! cg "$__print_f64g" "$__print_f64e"))
         (when (contains? print-types :f64G)
           ;; %G depends on both $__print_f64 and $__print_f64E
           (when-not (contains? print-types :f64)
             (when-not (or (contains? print-types :i64) (contains? print-types :u64))
               (emit-wasi-print-i64! cg))
             (emit-wasi-print-f64! cg))
           (when-not (contains? print-types :f64E)
             (emit-wasi-print-f64e-impl! cg "$__print_f64E" 69))
           (emit-wasi-print-f64g-impl! cg "$__print_f64G" "$__print_f64E"))
         ;; Format buffer flush function (for width/padding support)
         (when needs-fmt-buf?
           (emit-wasi-flush-padded! cg))
         ;; Precision-aware f64 print
         (when needs-f64-prec?
           ;; Depends on i64
           (when-not (or (contains? print-types :i64) (contains? print-types :u64)
                         (contains? print-types :f64)
                         (contains? print-types :f64e) (contains? print-types :f64E)
                         (contains? print-types :f64g) (contains? print-types :f64G))
             (emit-wasi-print-i64! cg))
           (emit-wasi-print-f64-prec! cg)))

       ;; Append all function output
       (doseq [line @(:output func-cg)]
         (swap! (:output cg) conj line))

       ;; WASI _start entry point
       (when (and wasi? has-main?)
         (emit-wasi-start! cg (some? main-result?) globals-needing-init))

       ;; Data segments
       (when (seq @(:data-segments func-cg))
         (doseq [{:keys [offset bytes]} @(:data-segments func-cg)]
           (emit! cg (str "(data (i32.const " offset ") \"" bytes "\")")))
         (emit! cg ""))

       (swap! (:indent cg) dec)
       (emit! cg ")")

       (str/join "\n" @(:output cg))))))
