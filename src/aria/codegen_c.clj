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

(defn- var->c
  "Convert ARIA variable name ($foo, %bar) to valid C identifier."
  [name]
  (-> name
      (str/replace #"^[$%]" "")
      (str/replace "." "_")
      (str/replace "-" "_")
      ;; Handle reader-processed sigils (aria$foo, aria%foo, aria/foo)
      (str/replace #"^aria[$%/]" "")))

(def ^:private type-map
  {"i1" "int8_t" "i8" "int8_t" "i16" "int16_t"
   "i32" "int32_t" "i64" "int64_t"
   "u8" "uint8_t" "u16" "uint16_t"
   "u32" "uint32_t" "u64" "uint64_t"
   "f32" "float" "f64" "double"
   "bool" "bool" "void" "void"})

(defn- type->c [t]
  (case (:type/kind t)
    :primitive (let [n (:type/name t)]
                 (or (get type-map n)
                     ;; Named type reference (e.g. $Node → Node)
                     (var->c n)))
    :ptr       (str (type->c (:type/pointee t)) "*")
    :array     (type->c (:type/elem t))
    :struct    (if (seq (:type/name t))
                 (str "struct " (var->c (:type/name t)))
                 "struct")
    "void"))

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
      (str "(uint8_t*)\"" s "\""))

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

    :switch
    (let [expr-c (expr->c cg (:expr node))
          cases (:cases node)]
      (doseq [[i c] (map-indexed vector cases)]
        (let [val-c (expr->c cg (:case/value c))
              ;; Use strcmp for string literals, == for integers
              cond-str (if (= :string-literal (:node/type (:case/value c)))
                         (str "strcmp((const char*)" expr-c ", (const char*)" val-c ") == 0")
                         (str expr-c " == " val-c))
              prefix (if (zero? i) "if" "} else if")]
          (emit! cg (str prefix " (" cond-str ") {"))
          (swap! (:indent cg) inc)
          (doseq [n (:case/body c)]
            (gen-stmt! cg n))
          (swap! (:indent cg) dec)))
      (when (:default-body node)
        (emit! cg "} else {")
        (swap! (:indent cg) inc)
        (doseq [n (:default-body node)]
          (gen-stmt! cg n))
        (swap! (:indent cg) dec))
      (emit! cg "}"))

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
  (emit-raw! cg (str "typedef struct " (var->c name) " {"))
  (swap! (:indent cg) inc)
  (doseq [[fname ftype] (:type/fields stype)]
    (let [c-type (type->c ftype)
          c-fname (var->c fname)]
      (if (= :array (:type/kind ftype))
        (emit! cg (str c-type " " c-fname "[" (:type/size ftype) "];"))
        (emit! cg (str c-type " " c-fname ";")))))
  (swap! (:indent cg) dec)
  (emit-raw! cg (str "} " (var->c name) ";"))
  (emit-raw! cg ""))

(defn- has-ptr-param? [func]
  (some #(= :ptr (:type/kind (:param/type %))) (:params func)))

(defn- returns-ptr? [func]
  (= :ptr (:type/kind (:result func))))

(defn- effects->c-attr
  "Map ARIA effect set to a C compiler attribute string, or nil."
  [func]
  (let [effs (set (:effects func))]
    (cond
      ;; pure + no pointer params = truly const (no memory reads)
      (and (contains? effs :pure) (not (has-ptr-param? func)))
      "__attribute__((const)) "

      ;; pure + pointer params = reads memory but no side effects
      (and (contains? effs :pure) (has-ptr-param? func))
      "__attribute__((pure)) "

      ;; mem + returns pointer = malloc-like
      (and (contains? effs :mem) (returns-ptr? func))
      "__attribute__((malloc)) "

      :else nil)))

(defn- effects->c-comment
  "Emit an effects comment for non-attribute cases, or nil."
  [func]
  (when (seq (:effects func))
    (let [effs (sort (map name (:effects func)))]
      (str "/* effects: " (str/join " " effs) " */"))))

(defn- gen-function! [cg func]
  ;; Intent as doc comment
  (when (:intent func)
    (emit-raw! cg (str "/* " (:intent func) " */")))
  ;; Effects comment (always emitted when effects present)
  (when-let [comment (effects->c-comment func)]
    (emit-raw! cg comment))
  ;; Signature
  (let [ret-type (if (:result func) (type->c (:result func)) "void")
        params (if (seq (:params func))
                 (str/join ", "
                           (map (fn [p]
                                  (str (type->c (:param/type p)) " "
                                       (var->c (:param/name p))))
                                (:params func)))
                 "void")
        fname (var->c (:name func))
        attr (or (effects->c-attr func) "")]
    (emit-raw! cg (str attr ret-type " " fname "(" params ") {"))
    (swap! (:indent cg) inc)
    ;; Body (locals are now let-bindings in body, handled by gen-stmt!)
    (doseq [node (:body func)]
      (gen-stmt! cg node))
    (swap! (:indent cg) dec)
    (emit-raw! cg "}")
    (emit-raw! cg "")))

;; ── Extern Runtime Implementations ──────────────────────────

(def ^:private extern-impls
  "C implementations for ARIA extern functions, keyed by [module name]."
  {["io" "$file_read_all"]
   "uint8_t* file_read_all(uint8_t* path) {
    FILE* f = fopen((const char*)path, \"rb\");
    if (!f) { fprintf(stderr, \"Error: cannot open '%s'\\n\", path); exit(1); }
    fseek(f, 0, SEEK_END);
    long len = ftell(f);
    fseek(f, 0, SEEK_SET);
    uint8_t* buf = (uint8_t*)malloc(len + 1);
    fread(buf, 1, len, f);
    buf[len] = 0;
    fclose(f);
    return buf;
}"
   ["io" "$file_write"]
   "void file_write(uint8_t* path, uint8_t* content) {
    FILE* f = fopen((const char*)path, \"wb\");
    if (!f) { fprintf(stderr, \"Error: cannot write '%s'\\n\", path); exit(1); }
    fputs((const char*)content, f);
    fclose(f);
}"
   ["sys" "$args_count"]
   "static int __aria_argc;
static char** __aria_argv;
int32_t args_count(void) { return __aria_argc; }"
   ["sys" "$args_get"]
   "uint8_t* args_get(int32_t i) { return (uint8_t*)__aria_argv[i]; }"
   ["sys" "$trap"]
   "void trap(uint8_t* msg) { fprintf(stderr, \"TRAP: %s\\n\", (const char*)msg); exit(1); }"
   ["sys" "$str_len"]
   "int32_t str_len(uint8_t* s) { return (int32_t)strlen((const char*)s); }"
   ["sys" "$str_eq"]
   "int32_t str_eq(uint8_t* a, uint8_t* b) { return strcmp((const char*)a, (const char*)b) == 0; }"})

(defn- needs-sys-args? [externs]
  (some #(and (= (:extern/module %) "sys")
              (#{"$args_count" "$args_get"} (:name %)))
        externs))

(defn- gen-extern-impls! [cg externs]
  (let [emitted (atom #{})]
    (doseq [ext externs]
      (let [key [(:extern/module ext) (:name ext)]]
        (when-let [impl (get extern-impls key)]
          (when-not (@emitted key)
            (swap! emitted conj key)
            (emit-raw! cg impl)
            (emit-raw! cg "")))))))

(defn generate
  "Generate complete C99 source from an ARIA module AST."
  [module]
  (let [cg (make-codegen)
        externs (or (:externs module) [])]
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

    ;; Forward struct declarations (needed for self-referential types)
    (doseq [td (:types module)]
      (when (= :struct (:type/kind (:aria/type td)))
        (let [cname (var->c (:name td))]
          (emit-raw! cg (str "typedef struct " cname " " cname ";")))))
    (when (seq (:types module))
      (emit-raw! cg ""))

    ;; Extern runtime implementations
    (when (seq externs)
      (gen-extern-impls! cg externs))

    ;; Type definitions
    (doseq [td (:types module)]
      (when (= :struct (:type/kind (:aria/type td)))
        (gen-struct! cg (:aria/type td) (:name td))))

    ;; Forward declarations
    (let [sys-args? (needs-sys-args? externs)]
      (doseq [func (:functions module)]
        (let [ret-type (if (:result func) (type->c (:result func)) "void")
              params (if (seq (:params func))
                       (str/join ", "
                                 (map (fn [p]
                                        (str (type->c (:param/type p)) " "
                                             (var->c (:param/name p))))
                                      (:params func)))
                       "void")
              fname (if (and (= (:name func) "$main") sys-args?)
                      "aria_main"
                      (var->c (:name func)))
              attr (or (effects->c-attr func) "")]
          (emit-raw! cg (str attr ret-type " " fname "(" params ");")))))

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
      ;; When sys args are used, rename $main to aria_main and generate a wrapper
      (if (and (= (:name func) "$main") (needs-sys-args? externs))
        (let [renamed (assoc func :name "$aria_main")]
          (gen-function! cg renamed))
        (gen-function! cg func)))

    ;; If sys args used, generate main wrapper that captures argc/argv
    (when (needs-sys-args? externs)
      (emit-raw! cg "int main(int argc, char** argv) {")
      (emit-raw! cg "    __aria_argc = argc;")
      (emit-raw! cg "    __aria_argv = argv;")
      (emit-raw! cg "    return aria_main();")
      (emit-raw! cg "}"))

    (str/join "\n" @(:output cg))))
