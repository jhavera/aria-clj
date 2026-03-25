(ns aria.ast
  "ARIA AST — node constructors and predicates.

   Unlike the Python prototype that uses dataclasses, we use plain maps
   with a :node/type key for dispatch. This is idiomatic Clojure and
   plays well with multimethods and spec validation.")

;; ── Effects ──────────────────────────────────────────────────

(def effects #{:pure :mem :io :div})

;; ── Types ────────────────────────────────────────────────────

(def primitive-types
  #{"i1" "i8" "i16" "i32" "i64"
    "u8" "u16" "u32" "u64"
    "f32" "f64" "bool" "void"})

(defn primitive [name]
  {:type/kind :primitive :type/name name})

(defn ptr-type [pointee]
  {:type/kind :ptr :type/pointee pointee})

(defn array-type [elem size]
  {:type/kind :array :type/elem elem :type/size size})

(defn slice-type [elem]
  {:type/kind :slice :type/elem elem})

(defn struct-type [name fields]
  {:type/kind :struct :type/name name :type/fields fields})

(defn func-type [params result]
  {:type/kind :func-type :type/params params :type/result result})

;; ── Expression Nodes ─────────────────────────────────────────

(defn int-literal [value]
  {:node/type :int-literal :value value})

(defn float-literal [value]
  {:node/type :float-literal :value value})

(defn bool-literal [value]
  {:node/type :bool-literal :value value})

(defn string-literal [value]
  {:node/type :string-literal :value value})

(defn var-ref [name]
  {:node/type :var-ref :name name})

(defn bin-op [op type-suffix left right]
  {:node/type :bin-op :op op :type-suffix type-suffix :left left :right right})

(defn unary-op [op type-suffix operand]
  {:node/type :unary-op :op op :type-suffix type-suffix :operand operand})

(defn comparison [op type-suffix left right]
  {:node/type :comparison :op op :type-suffix type-suffix :left left :right right})

(defn let-binding [name typ value mutable?]
  {:node/type :let :name name :aria/type typ :value value :mutable? mutable?})

(defn set-var [name value]
  {:node/type :set-var :name name :value value})

(defn call-node [target args]
  {:node/type :call :target target :args args})

(defn return-node [value]
  {:node/type :return :value value})

(defn block-node [label body]
  {:node/type :block :label label :body body})

(defn if-node [cond then-body else-body]
  {:node/type :if :cond cond :then-body then-body :else-body else-body})

(defn loop-node [label body]
  {:node/type :loop :label label :body body})

(defn branch [label]
  {:node/type :branch :label label :cond nil})

(defn branch-if [cond label]
  {:node/type :branch :label label :cond cond})

(defn alloc-node [alloc-type count]
  {:node/type :alloc :alloc-type alloc-type :count count})

(defn free-node [ptr]
  {:node/type :free :ptr ptr})

(defn load-node [type-suffix ptr]
  {:node/type :load :type-suffix type-suffix :ptr ptr})

(defn store-node [type-suffix ptr value]
  {:node/type :store :type-suffix type-suffix :ptr ptr :value value})

(defn load-field [ptr field-name]
  {:node/type :load-field :ptr ptr :field-name field-name})

(defn store-field [ptr field-name value]
  {:node/type :store-field :ptr ptr :field-name field-name :value value})

(defn cast-node [from-type to-type value]
  {:node/type :cast :from-type from-type :to-type to-type :value value})

(defn seq-node [body]
  {:node/type :seq :body body})

(defn switch-node [expr cases default-body]
  {:node/type :switch :expr expr :cases cases :default-body default-body})

(defn switch-case [value body]
  {:case/value value :case/body body})

(defn intent-node [description]
  {:node/type :intent :description description})

(defn print-node [format-str args]
  {:node/type :print :format-str format-str :args args})

;; ── Top-Level Nodes ──────────────────────────────────────────

(defn param [name typ]
  {:param/name name :param/type typ})

(defn function [name params result effects intent body]
  {:node/type :function
   :name name
   :params params
   :result result
   :effects effects
   :intent intent
   :body body})

(defn global-var [name typ init mutable?]
  {:node/type :global :name name :aria/type typ :init init :mutable? mutable?})

(defn type-def [name typ]
  {:node/type :type-def :name name :aria/type typ})

(defn export-node [name alias]
  {:node/type :export :name name :alias alias})

(defn extern-func [name params result module-name]
  {:node/type :extern
   :name name
   :params params
   :result result
   :extern/module module-name})

(defn module
  ([name types globals functions exports]
   (module name types globals functions exports []))
  ([name types globals functions exports externs]
   {:node/type :module
    :name name
    :types types
    :globals globals
    :functions functions
    :exports exports
    :externs externs}))
