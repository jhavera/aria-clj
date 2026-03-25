(ns aria.parser
  "ARIA Parser — transforms raw s-expressions into validated AST maps.

   Since clojure.edn/read already handles the s-expression structure,
   the parser is really just validation + AST construction.
   Each form (keyword arg ...) is dispatched on the keyword."
  (:require [aria.ast :as ast]
            [aria.reader :as reader]
            [clojure.string :as str]))

(defn parse-error [msg & [form]]
  (throw (ex-info (str "Parse error: " msg)
                  {:type :parse-error :form form})))

;; ── Symbol helpers ────────────────────────────────────────────

(defn- sym-name
  "Get the string name of a symbol, handling namespaced symbols."
  [sym]
  (if (namespace sym)
    (str (namespace sym) "/" (name sym))
    (name sym)))

(defn- dotted-sym?
  "Is this a dotted symbol like add.i32?"
  [sym]
  (and (symbol? sym)
       (if (namespace sym)
         true  ;; Clojure reads foo.bar as ns=foo, name=bar sometimes
         (str/includes? (name sym) "."))))

(defn- split-op
  "Split a potentially dotted operation symbol into [base-op type-suffix].
   add.i32 -> [\"add\" \"i32\"]
   add     -> [\"add\" nil]"
  [sym]
  (let [full (sym-name sym)]
    (if (str/includes? full ".")
      (let [parts (str/split full #"\." 2)]
        [(first parts) (second parts)])
      [full nil])))

;; ── Type Parsing ──────────────────────────────────────────────

(defn- parse-type
  "Parse an ARIA type from a form.
   Primitive: i32, f64, bool, void, etc (symbols)
   Compound:  (ptr T), (array T N), (slice T), (struct ...), (func ...)"
  [form]
  (cond
    (symbol? form)
    (let [n (sym-name form)]
      (if (ast/primitive-types n)
        (ast/primitive n)
        ;; Could be a named type reference
        (ast/primitive n)))

    (seq? form)
    (let [kw (sym-name (first form))]
      (case kw
        "ptr"    (ast/ptr-type (parse-type (second form)))
        "array"  (ast/array-type (parse-type (second form)) (nth form 2))
        "slice"  (ast/slice-type (parse-type (second form)))
        "struct" (let [sname (if (string? (second form)) (second form) "")
                       fields-start (if (string? (second form)) 2 1)
                       fields (mapv (fn [f]
                                      ;; Support both formats:
                                      ;; (field $name Type) — new explicit form
                                      ;; ($name Type)       — short form
                                      (if (= "field" (sym-name (first f)))
                                        [(sym-name (second f)) (parse-type (nth f 2))]
                                        [(sym-name (first f)) (parse-type (second f))]))
                                    (drop fields-start form))]
                   (ast/struct-type sname fields))
        "func"   (let [param-types (mapv parse-type (second form))
                       result (parse-type (nth form 2))]
                   (ast/func-type param-types result))
        (parse-error (str "Unknown type constructor: " kw) form)))

    :else
    (parse-error (str "Expected type, got: " (pr-str form)) form)))

;; ── Expression Parsing ────────────────────────────────────────

(def ^:private bin-ops #{"add" "sub" "mul" "div" "rem" "and" "or" "xor" "shl" "shr"})
(def ^:private unary-ops #{"neg" "not" "sqrt" "abs" "ceil" "floor"})
(def ^:private cmp-ops #{"eq" "ne" "lt" "le" "gt" "ge"})

(declare parse-expr)

(defn- parse-expr-list
  "Parse a sequence of expressions until the list ends."
  [forms]
  (mapv parse-expr forms))

(defn- parse-form
  "Parse an s-expression form (keyword args...)."
  [form]
  (let [head (first form)
        [base-op type-suffix] (split-op head)
        args (rest form)]
    (cond
      ;; Binary ops: (add.i32 left right)
      (bin-ops base-op)
      (let [[left right] args]
        (ast/bin-op base-op type-suffix (parse-expr left) (parse-expr right)))

      ;; Unary ops: (neg.i32 operand)
      (unary-ops base-op)
      (let [[operand] args]
        (ast/unary-op base-op type-suffix (parse-expr operand)))

      ;; Comparisons: (eq.i32 left right)
      (cmp-ops base-op)
      (let [[left right] args]
        (ast/comparison base-op type-suffix (parse-expr left) (parse-expr right)))

      ;; Let binding: (let %name Type expr)
      (= base-op "let")
      (let [[var-sym type-form val-form] args]
        (ast/let-binding (reader/var-name var-sym)
                         (parse-type type-form)
                         (parse-expr val-form)
                         false))

      ;; Set: (set $var expr)
      (= base-op "set")
      (let [[var-sym val-form] args]
        (ast/set-var (reader/var-name var-sym) (parse-expr val-form)))

      ;; Call: (call $target arg1 arg2 ...)
      (= base-op "call")
      (let [target (reader/var-name (first args))
            call-args (parse-expr-list (rest args))]
        (ast/call-node target call-args))

      ;; Return: (return expr?)
      (= base-op "return")
      (ast/return-node (when (seq args) (parse-expr (first args))))

      ;; Block: (block $label body...)
      (= base-op "block")
      (let [[first-arg & rest-args] args
            [label body-forms] (if (reader/aria-var? first-arg)
                                 [(reader/var-name first-arg) rest-args]
                                 ["" (cons first-arg rest-args)])]
        (ast/block-node label (parse-expr-list body-forms)))

      ;; If: (if cond (then ...) (else ...))
      (= base-op "if")
      (let [[cond-form then-form & else-forms] args
            then-body (parse-expr-list (rest then-form))  ;; skip 'then symbol
            else-body (if (seq else-forms)
                        (parse-expr-list (rest (first else-forms)))  ;; skip 'else symbol
                        [])]
        (ast/if-node (parse-expr cond-form) then-body else-body))

      ;; Loop: (loop $label body...)
      (= base-op "loop")
      (let [[first-arg & rest-args] args
            [label body-forms] (if (reader/aria-var? first-arg)
                                 [(reader/var-name first-arg) rest-args]
                                 ["" (cons first-arg rest-args)])]
        (ast/loop-node label (parse-expr-list body-forms)))

      ;; Branch: (br $label)
      (= base-op "br")
      (ast/branch (reader/var-name (first args)))

      ;; Branch if: (br_if cond $label)
      (= base-op "br_if")
      (let [[cond-form label-sym] args]
        (ast/branch-if (parse-expr cond-form) (reader/var-name label-sym)))

      ;; Alloc: (alloc Type count?)
      (= base-op "alloc")
      (let [[type-form & count-forms] args]
        (ast/alloc-node (parse-type type-form)
                        (when (seq count-forms) (parse-expr (first count-forms)))))

      ;; Free: (free ptr)
      (= base-op "free")
      (ast/free-node (parse-expr (first args)))

      ;; Load: (load.T ptr)
      (and (= base-op "load") type-suffix (not= type-suffix "field"))
      (ast/load-node type-suffix (parse-expr (first args)))

      ;; Load field: (load.field ptr "field")
      (and (= base-op "load") (= type-suffix "field"))
      (let [[ptr-form field-str] args]
        (ast/load-field (parse-expr ptr-form) field-str))

      ;; Store: (store.T ptr value)
      (and (= base-op "store") type-suffix (not= type-suffix "field"))
      (let [[ptr-form val-form] args]
        (ast/store-node type-suffix (parse-expr ptr-form) (parse-expr val-form)))

      ;; Store field: (store.field ptr "field" value)
      (and (= base-op "store") (= type-suffix "field"))
      (let [[ptr-form field-str val-form] args]
        (ast/store-field (parse-expr ptr-form) field-str (parse-expr val-form)))

      ;; Cast: (cast FromType ToType expr)
      (= base-op "cast")
      (let [[from-form to-form val-form] args]
        (ast/cast-node (parse-type from-form) (parse-type to-form) (parse-expr val-form)))

      ;; Print: (print "format" args...)
      (= base-op "print")
      (let [[fmt-str & print-args] args]
        (ast/print-node fmt-str (parse-expr-list print-args)))

      ;; Intent: (intent "description")
      (= base-op "intent")
      (ast/intent-node (first args))

      ;; Local (in function body): (local mut $var Type init?)
      (= base-op "local")
      (let [rest-args args
            [mutable? rest-args] (if (= 'mut (first rest-args))
                                   [true (rest rest-args)]
                                   [false rest-args])
            [var-sym type-form & init-forms] rest-args]
        (ast/let-binding (reader/var-name var-sym)
                         (parse-type type-form)
                         (if (seq init-forms)
                           (parse-expr (first init-forms))
                           (ast/int-literal 0))
                         mutable?))

      ;; Switch: (switch expr (case value body...) ... (default body...))
      (= base-op "switch")
      (let [[expr-form & clauses] args
            expr (parse-expr expr-form)
            cases (atom [])
            default (atom nil)]
        (doseq [clause clauses]
          (let [kw (sym-name (first clause))]
            (case kw
              "case" (let [[_ val-form & body-forms] clause]
                       (swap! cases conj
                              (ast/switch-case (parse-expr val-form)
                                               (parse-expr-list body-forms))))
              "default" (reset! default (parse-expr-list (rest clause)))
              (parse-error (str "Expected case or default in switch, got: " kw) clause))))
        (ast/switch-node expr @cases @default))

      ;; Seq: (seq body...)
      (= base-op "seq")
      (ast/seq-node (parse-expr-list args))

      :else
      (parse-error (str "Unknown form: " base-op) form))))

(defn- parse-expr
  "Parse any ARIA expression — literal, variable, or s-expression form."
  [form]
  (cond
    (integer? form)       (ast/int-literal form)
    (float? form)         (ast/float-literal form)
    (true? form)          (ast/bool-literal true)
    (false? form)         (ast/bool-literal false)
    (string? form)        (ast/string-literal form)
    (reader/aria-var? form) (ast/var-ref (reader/var-name form))
    (symbol? form)        (ast/var-ref (name form)) ;; bare symbol as var ref
    (seq? form)           (parse-form form)
    (list? form)          (parse-form form)
    :else                 (parse-error (str "Unexpected form: " (pr-str form)) form)))

;; ── Top-Level Parsing ─────────────────────────────────────────

(defn- parse-param
  "Parse (param $name Type)."
  [form]
  ;; form is the interior: ($name Type) after 'param has been confirmed
  (let [[_ var-sym type-form] form]  ;; _ = 'param
    (ast/param (reader/var-name var-sym) (parse-type type-form))))

(defn- parse-function
  "Parse (func $name (param ...) ... (result Type) (effects ...) body...)."
  [form]
  (let [[_ func-name & parts] form
        func-name-str (reader/var-name func-name)]
    ;; Walk through parts, pulling off known sections
    (loop [parts parts
           params []
           result nil
           effects []
           intent nil
           body []]
      (if (empty? parts)
        (ast/function func-name-str params result (set effects) intent body)
        (let [part (first parts)]
          (if (seq? part)
            (let [kw (sym-name (first part))]
              (case kw
                "param"   (recur (rest parts)
                                 (conj params (parse-param part))
                                 result effects intent body)
                "result"  (recur (rest parts)
                                 params
                                 (parse-type (second part))
                                 effects intent body)
                "effects" (recur (rest parts)
                                 params result
                                 (mapv (comp keyword name) (rest part))
                                 intent body)
                "intent"  (recur (rest parts)
                                 params result effects
                                 (second part)
                                 body)
                ;; Anything else is body
                (recur (rest parts)
                       params result effects intent
                       (conj body (parse-expr part)))))
            ;; Non-list in function body (shouldn't happen for well-formed ARIA)
            (recur (rest parts)
                   params result effects intent
                   (conj body (parse-expr part)))))))))

(defn- parse-global
  "Parse (global [mut] $name Type init?)."
  [form]
  (let [[_ & parts] form
        [mutable? parts] (if (= 'mut (first parts))
                           [true (rest parts)]
                           [false parts])
        [var-sym type-form & init-forms] parts]
    (ast/global-var (reader/var-name var-sym)
                    (parse-type type-form)
                    (when (seq init-forms) (parse-expr (first init-forms)))
                    mutable?)))

(defn- parse-typedef
  "Parse (type $Name Type)."
  [form]
  (let [[_ name-sym type-form] form]
    (ast/type-def (sym-name name-sym) (parse-type type-form))))

(defn- parse-export
  "Parse (export $name \"alias\"?)."
  [form]
  (let [[_ var-sym & rest-parts] form]
    (ast/export-node (reader/var-name var-sym)
                     (first rest-parts))))  ;; alias or nil

(defn- parse-extern
  "Parse (extern \"module\" $name (param ...) ... (result Type)?).
   Extern functions have no body or effects — they are host-provided."
  [form]
  (let [[_ module-name func-name & parts] form
        func-name-str (reader/var-name func-name)]
    (loop [parts parts
           params []
           result nil]
      (if (empty? parts)
        (ast/extern-func func-name-str params result module-name)
        (let [part (first parts)]
          (if (seq? part)
            (let [kw (sym-name (first part))]
              (case kw
                "param"  (recur (rest parts)
                                (conj params (parse-param part))
                                result)
                "result" (recur (rest parts)
                                params
                                (parse-type (second part)))
                ;; skip unknown
                (recur (rest parts) params result)))
            (recur (rest parts) params result)))))))

(defn parse-module
  "Parse a complete ARIA module from a raw s-expression form."
  [form]
  (let [[module-sym module-name & top-forms] form]
    (when-not (= 'module (if (symbol? module-sym) module-sym nil))
      (parse-error "Expected (module ...) at top level" form))
    (when-not (string? module-name)
      (parse-error "Module name must be a string" form))
    (loop [forms top-forms
           types []
           globals []
           functions []
           exports []
           externs []]
      (if (empty? forms)
        (ast/module module-name types globals functions exports externs)
        (let [f (first forms)
              kw (when (seq? f) (sym-name (first f)))]
          (case kw
            "func"     (recur (rest forms) types globals
                              (conj functions (parse-function f)) exports externs)
            "global"   (recur (rest forms) types
                              (conj globals (parse-global f)) functions exports externs)
            "type"     (recur (rest forms)
                              (conj types (parse-typedef f)) globals functions exports externs)
            "export"   (recur (rest forms) types globals functions
                              (conj exports (parse-export f)) externs)
            "extern"   (recur (rest forms) types globals functions exports
                              (conj externs (parse-extern f)))
            "metadata" (recur (rest forms) types globals functions exports externs) ;; skip
            (parse-error (str "Unknown top-level form: " kw) f)))))))

;; ── Convenience ───────────────────────────────────────────────

(defn parse
  "Parse ARIA-IR source string into an AST module."
  [source]
  (parse-module (reader/read-aria source)))

(defn parse-file
  "Parse an ARIA-IR file into an AST module."
  [path]
  (parse (slurp path)))
