(ns aria.codegen-c
  "ARIA-C Transpiler — compiles ARIA AST to portable C99 code.

   The generated C can be compiled with gcc/clang for any target.
   Pipeline: AI -> ARIA-IR -> C -> (gcc/clang) -> Native"
  (:require [aria.ast :as ast]
            [clojure.string :as str]))

;; ── Signed Integer Overflow ──────────────────────────────────
;; C signed integer overflow is undefined behavior (UB). Compilers may
;; optimize assuming no overflow, producing results that differ from WASM
;; at -O2 and above. WASM always wraps (modular 2^32 / 2^64).
;; For cross-backend reproducibility, compile with -fwrapv.
;; For intentional wrapping, prefer unsigned types (u32, u64).

;; ── State ─────────────────────────────────────────────────────

(defn- make-codegen []
  {:indent (atom 0)
   :output (atom [])
   :temp-counter (atom 0)
   :label-counter (atom 0)
   :loop-labels (atom {})})

(defn- indent-str [cg]
  (apply str (repeat @(:indent cg) "    ")))

(defn- emit! [cg line]
  (swap! (:output cg) conj (str (indent-str cg) line)))

(defn- emit-raw! [cg line]
  (swap! (:output cg) conj line))

(defn- fresh-label! [cg]
  (swap! (:label-counter cg) inc))

;; ── Type Mapping ──────────────────────────────────────────────

(def ^:private type-map
  {"i1" "int8_t" "i8" "int8_t" "i16" "int16_t"
   "i32" "int32_t" "i64" "int64_t"
   "u8" "uint8_t" "u16" "uint16_t"
   "u32" "uint32_t" "u64" "uint64_t"
   "f32" "float" "f64" "double"
   "bool" "bool" "void" "void"})

(defn- type->c [t]
  (case (:type/kind t)
    :primitive (get type-map (:type/name t) (:type/name t))
    :ptr       (str (type->c (:type/pointee t)) "*")
    :array     (type->c (:type/elem t))
    :struct    (if (seq (:type/name t))
                 (str "struct " (:type/name t))
                 "struct")
    "void"))

(defn- var->c
  "Convert ARIA variable name ($foo, %bar) to valid C identifier."
  [name]
  (-> name
      (str/replace #"^[$%]" "")
      (str/replace "." "_")
      (str/replace "-" "_")))

;; ── Expression Codegen ────────────────────────────────────────

(declare expr->c)

(def ^:private bin-op-map
  {"add" "+" "sub" "-" "mul" "*" "div" "/" "rem" "%"
   "and" "&" "or" "|" "xor" "^" "shl" "<<" "shr" ">>"})

(def ^:private cmp-op-map
  {"eq" "==" "ne" "!=" "lt" "<" "le" "<=" "gt" ">" "ge" ">="})

(defn- expr->c
  "Generate a C expression string from an ARIA AST node."
  [cg node]
  (case (:node/type node)
    :int-literal
    (str (:value node))

    :float-literal
    (str (:value node))

    :bool-literal
    (if (:value node) "true" "false")

    :string-literal
    (let [s (-> (:value node)
                (str/replace "\\" "\\\\")
                (str/replace "\"" "\\\"")
                (str/replace "\n" "\\n")
                (str/replace "\t" "\\t"))]
      (str "\"" s "\""))

    :var-ref
    (var->c (:name node))

    :bin-op
    (let [left (expr->c cg (:left node))
          right (expr->c cg (:right node))
          op (get bin-op-map (:op node) (:op node))]
      (str "(" left " " op " " right ")"))

    :unary-op
    (let [operand (expr->c cg (:operand node))]
      (case (:op node)
        "neg" (str "(-" operand ")")
        "not" (str "(~" operand ")")
        ;; Math functions
        (str (:op node) "(" operand ")")))

    :comparison
    (let [left (expr->c cg (:left node))
          right (expr->c cg (:right node))
          op (get cmp-op-map (:op node))]
      (str "(" left " " op " " right ")"))

    :call
    (let [target (var->c (:target node))
          args (str/join ", " (map #(expr->c cg %) (:args node)))]
      (str target "(" args ")"))

    :alloc
    (let [c-type (type->c (:alloc-type node))]
      (if (:count node)
        (let [count-expr (expr->c cg (:count node))]
          (str "(" c-type "*)malloc(sizeof(" c-type ") * " count-expr ")"))
        (str "(" c-type "*)malloc(sizeof(" c-type "))")))

    :load
    (let [ptr (expr->c cg (:ptr node))]
      (str "(*" ptr ")"))

    :load-field
    (let [ptr (expr->c cg (:ptr node))]
      (str ptr "->" (:field-name node)))

    :cast
    (let [c-type (type->c (:to-type node))
          val (expr->c cg (:value node))]
      (str "((" c-type ")" val ")"))

    :if
    (if (and (= 1 (count (:then-body node)))
             (= 1 (count (:else-body node))))
      (let [cond-expr (expr->c cg (:cond node))
            then-expr (expr->c cg (first (:then-body node)))
            else-expr (expr->c cg (first (:else-body node)))]
        (str "(" cond-expr " ? " then-expr " : " else-expr ")"))
      (str "/* complex if expr */"))

    ;; Fallback
    (str "/* unknown expr: " (:node/type node) " */")))

;; ── Statement Codegen ─────────────────────────────────────────

(declare gen-stmt!)

(defn- gen-stmt!
  "Generate C statement(s) for an ARIA AST node."
  [cg node]
  (case (:node/type node)
    :intent
    (emit! cg (str "/* INTENT: " (:description node) " */"))

    :let
    (let [c-type (if (:aria/type node) (type->c (:aria/type node)) "auto")
          name (var->c (:name node))
          val (expr->c cg (:value node))]
      (emit! cg (str c-type " " name " = " val ";")))

    :set-var
    (let [name (var->c (:name node))
          val (expr->c cg (:value node))]
      (emit! cg (str name " = " val ";")))

    :store
    (let [ptr (expr->c cg (:ptr node))
          val (expr->c cg (:value node))]
      (emit! cg (str "*" ptr " = " val ";")))

    :store-field
    (let [ptr (expr->c cg (:ptr node))
          val (expr->c cg (:value node))]
      (emit! cg (str ptr "->" (:field-name node) " = " val ";")))

    :free
    (let [ptr (expr->c cg (:ptr node))]
      (emit! cg (str "free(" ptr ");")))

    :return
    (if (:value node)
      (let [val (expr->c cg (:value node))]
        (emit! cg (str "return " val ";")))
      (emit! cg "return;"))

    :print
    (let [args-c (str/join ", " (map #(expr->c cg %) (:args node)))
          fmt (-> (:format-str node)
                  (str/replace "\\" "\\\\")
                  (str/replace "\"" "\\\"")
                  (str/replace "\n" "\\n")
                  (str/replace "\t" "\\t"))]
      (if (seq args-c)
        (emit! cg (str "printf(\"" fmt "\", " args-c ");"))
        (emit! cg (str "printf(\"" fmt "\");"))))

    :block
    (do (emit! cg "{")
        (swap! (:indent cg) inc)
        (doseq [n (:body node)]
          (gen-stmt! cg n))
        (swap! (:indent cg) dec)
        (emit! cg "}"))

    :if
    (let [cond-expr (expr->c cg (:cond node))]
      (emit! cg (str "if (" cond-expr ") {"))
      (swap! (:indent cg) inc)
      (doseq [n (:then-body node)]
        (gen-stmt! cg n))
      (swap! (:indent cg) dec)
      (if (seq (:else-body node))
        (do (emit! cg "} else {")
            (swap! (:indent cg) inc)
            (doseq [n (:else-body node)]
              (gen-stmt! cg n))
            (swap! (:indent cg) dec)
            (emit! cg "}"))
        (emit! cg "}")))

    :loop
    (let [label-id (fresh-label! cg)]
      (when (seq (:label node))
        (swap! (:loop-labels cg) assoc (:label node) label-id))
      (emit! cg (str "while (1) { /* loop " (:label node) " */"))
      (swap! (:indent cg) inc)
      (doseq [n (:body node)]
        (gen-stmt! cg n))
      (swap! (:indent cg) dec)
      (emit! cg "}")
      (when (seq (:label node))
        (emit! cg (str "__break_" label-id ":;"))))

    :branch
    (if (nil? (:cond node))
      (emit! cg (str "break; /* br " (:label node) " */"))
      (let [cond-expr (expr->c cg (:cond node))]
        (emit! cg (str "if (" cond-expr ") break; /* br_if " (:label node) " */"))))

    :seq
    (doseq [n (:body node)]
      (gen-stmt! cg n))

    :call
    (let [expr (expr->c cg node)]
      (emit! cg (str expr ";")))

    ;; Expression used as statement
    (let [expr (expr->c cg node)]
      (when-not (str/starts-with? expr "/*")
        (emit! cg (str expr ";"))))))

;; ── Top-Level Codegen ─────────────────────────────────────────

(defn- gen-struct! [cg stype name]
  (emit-raw! cg (str "typedef struct " name " {"))
  (swap! (:indent cg) inc)
  (doseq [[fname ftype] (:type/fields stype)]
    (let [c-type (type->c ftype)]
      (if (= :array (:type/kind ftype))
        (emit! cg (str c-type " " fname "[" (:type/size ftype) "];"))
        (emit! cg (str c-type " " fname ";")))))
  (swap! (:indent cg) dec)
  (emit-raw! cg (str "} " name ";"))
  (emit-raw! cg ""))

(defn- gen-function! [cg func]
  ;; Intent as doc comment
  (when (:intent func)
    (emit-raw! cg (str "/* " (:intent func) " */")))
  ;; Signature
  (let [ret-type (if (:result func) (type->c (:result func)) "void")
        params (if (seq (:params func))
                 (str/join ", "
                           (map (fn [p]
                                  (str (type->c (:param/type p)) " "
                                       (var->c (:param/name p))))
                                (:params func)))
                 "void")
        fname (var->c (:name func))]
    (emit-raw! cg (str ret-type " " fname "(" params ") {"))
    (swap! (:indent cg) inc)
    ;; Body (locals are now let-bindings in body, handled by gen-stmt!)
    (doseq [node (:body func)]
      (gen-stmt! cg node))
    (swap! (:indent cg) dec)
    (emit-raw! cg "}")
    (emit-raw! cg "")))

(defn generate
  "Generate complete C99 source from an ARIA module AST."
  [module]
  (let [cg (make-codegen)]
    ;; Header
    (emit-raw! cg "/* ═══════════════════════════════════════════════════════════")
    (emit-raw! cg (str "   Generated from ARIA module: " (:name module)))
    (emit-raw! cg "   ARIA-C Transpiler v0.1")
    (emit-raw! cg "   ═══════════════════════════════════════════════════════════ */")
    (emit-raw! cg "")
    (emit-raw! cg "#include <stdio.h>")
    (emit-raw! cg "#include <stdlib.h>")
    (emit-raw! cg "#include <stdint.h>")
    (emit-raw! cg "#include <stdbool.h>")
    (emit-raw! cg "#include <math.h>")
    (emit-raw! cg "#include <string.h>")
    (emit-raw! cg "")

    ;; Type definitions
    (doseq [td (:types module)]
      (when (= :struct (:type/kind (:aria/type td)))
        (gen-struct! cg (:aria/type td) (:name td))))

    ;; Forward declarations
    (doseq [func (:functions module)]
      (let [ret-type (if (:result func) (type->c (:result func)) "void")
            params (if (seq (:params func))
                     (str/join ", "
                               (map (fn [p]
                                      (str (type->c (:param/type p)) " "
                                           (var->c (:param/name p))))
                                    (:params func)))
                     "void")
            fname (var->c (:name func))]
        (emit-raw! cg (str ret-type " " fname "(" params ");"))))
    (emit-raw! cg "")

    ;; Globals
    (doseq [g (:globals module)]
      (let [c-type (type->c (:aria/type g))
            c-name (var->c (:name g))]
        (if (:init g)
          (emit-raw! cg (str c-type " " c-name " = " (expr->c cg (:init g)) ";"))
          (emit-raw! cg (str c-type " " c-name ";")))))
    (when (seq (:globals module))
      (emit-raw! cg ""))

    ;; Functions
    (doseq [func (:functions module)]
      (gen-function! cg func))

    (str/join "\n" @(:output cg))))
