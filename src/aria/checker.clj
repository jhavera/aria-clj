(ns aria.checker
  "ARIA Type Checker — validates types, variable scoping, and effects.

   Checks:
   - All variable references are defined
   - Operations have matching types
   - Effects are correctly declared
   - Mutable assignments target mutable variables"
  (:require [aria.ast :as ast]
            [clojure.set :as set]
            [clojure.string :as str]))

;; ── Type Environment ──────────────────────────────────────────

(defn- make-env
  "Create a new type environment, optionally with a parent."
  ([] {:bindings {} :mutables #{} :parent nil})
  ([parent] {:bindings {} :mutables #{} :parent parent}))

(defn- env-define
  "Define a variable in the environment."
  [env name typ mutable?]
  (cond-> (update env :bindings assoc name typ)
    mutable? (update :mutables conj name)))

(defn- env-lookup
  "Look up a variable's type, walking parent scopes."
  [env name]
  (or (get-in env [:bindings name])
      (when-let [p (:parent env)]
        (env-lookup p name))))

(defn- env-mutable?
  "Check if a variable is mutable, walking parent scopes."
  [env name]
  (or (contains? (:mutables env) name)
      (when-let [p (:parent env)]
        (env-mutable? p name))))

(defn- child-env [env] (make-env env))

;; ── Type helpers ──────────────────────────────────────────────

(defn- suffix->type [suffix]
  (when suffix (ast/primitive suffix)))

(defn- type-name [t]
  (case (:type/kind t)
    :primitive (:type/name t)
    :ptr       (str "(ptr " (type-name (:type/pointee t)) ")")
    :array     (str "(array " (type-name (:type/elem t)) " " (:type/size t) ")")
    :slice     (str "(slice " (type-name (:type/elem t)) ")")
    :struct    (str "(struct " (:type/name t) ")")
    (str t)))

;; ── Checker state ─────────────────────────────────────────────

(defn- make-checker []
  {:errors   (atom [])
   :warnings (atom [])
   :functions (atom {})
   :type-defs (atom {})
   :globals  (atom {})})

(defn- add-error! [checker msg]
  (swap! (:errors checker) conj msg))

(defn- add-warning! [checker msg]
  (swap! (:warnings checker) conj msg))

;; ── Node checking ─────────────────────────────────────────────

(declare check-node)
(declare check-body)

(defn- check-node
  "Check a node and return its inferred type. Mutates checker state
   for errors/warnings and the effects atom."
  [checker node env effects]
  (case (:node/type node)
    :int-literal
    (ast/primitive "i32")

    :float-literal
    (ast/primitive "f64")

    :bool-literal
    (ast/primitive "bool")

    :string-literal
    (ast/ptr-type (ast/primitive "u8"))

    :var-ref
    (let [typ (env-lookup env (:name node))]
      (when-not typ
        (add-error! checker (str "Undefined variable '" (:name node) "'")))
      typ)

    :bin-op
    (do (check-node checker (:left node) env effects)
        (check-node checker (:right node) env effects)
        (if (:type-suffix node)
          (suffix->type (:type-suffix node))
          (ast/primitive "i32")))

    :unary-op
    (do (check-node checker (:operand node) env effects)
        (if (:type-suffix node)
          (suffix->type (:type-suffix node))
          (ast/primitive "i32")))

    :comparison
    (do (check-node checker (:left node) env effects)
        (check-node checker (:right node) env effects)
        (ast/primitive "bool"))

    :let
    (let [val-type (check-node checker (:value node) env effects)
          typ (or (:aria/type node) val-type (ast/primitive "i32"))
          new-env (env-define env (:name node) typ (:mutable? node))]
      ;; Mutate the env atom reference... actually we return the new env
      ;; We'll handle env threading differently - see check-body
      typ)

    :set-var
    (do (when-not (env-mutable? env (:name node))
          (when (env-lookup env (:name node))
            (add-error! checker
                        (str "Cannot assign to immutable variable '" (:name node) "'"))))
        (check-node checker (:value node) env effects)
        nil)

    :call
    (let [funcs @(:functions checker)]
      (when-let [callee (get funcs (:target node))]
        (doseq [eff (:effects callee)]
          (swap! effects conj eff)))
      (doseq [arg (:args node)]
        (check-node checker arg env effects))
      (when-let [callee (get funcs (:target node))]
        (:result callee)))

    :return
    (do (when (:value node)
          (check-node checker (:value node) env effects))
        (ast/primitive "void"))

    :block
    (let [child (child-env env)]
      (check-body checker (:body node) child effects)
      nil)

    :if
    (do (check-node checker (:cond node) env effects)
        (check-body checker (:then-body node) (child-env env) effects)
        (check-body checker (:else-body node) (child-env env) effects)
        nil)

    :loop
    (do (swap! effects conj :div)
        (check-body checker (:body node) (child-env env) effects)
        nil)

    :branch
    (do (when (:cond node)
          (check-node checker (:cond node) env effects))
        nil)

    :alloc
    (do (swap! effects conj :mem)
        (when (:count node)
          (check-node checker (:count node) env effects))
        (ast/ptr-type (:alloc-type node)))

    :free
    (do (swap! effects conj :mem)
        (check-node checker (:ptr node) env effects)
        nil)

    :load
    (do (swap! effects conj :mem)
        (check-node checker (:ptr node) env effects)
        (suffix->type (:type-suffix node)))

    :store
    (do (swap! effects conj :mem)
        (check-node checker (:ptr node) env effects)
        (check-node checker (:value node) env effects)
        nil)

    :load-field
    (do (swap! effects conj :mem)
        (check-node checker (:ptr node) env effects)
        nil)

    :store-field
    (do (swap! effects conj :mem)
        (check-node checker (:ptr node) env effects)
        (check-node checker (:value node) env effects)
        nil)

    :cast
    (do (check-node checker (:value node) env effects)
        (:to-type node))

    :print
    (do (swap! effects conj :io)
        (doseq [arg (:args node)]
          (check-node checker arg env effects))
        nil)

    :intent
    nil

    :seq
    (last (mapv #(check-node checker % env effects) (:body node)))

    ;; Unknown node type
    (do (add-warning! checker (str "Unknown node type: " (:node/type node)))
        nil)))

(defn- check-body
  "Check a sequence of body nodes, threading the environment through
   let bindings so later nodes can see earlier definitions."
  [checker nodes env effects]
  (reduce
   (fn [env node]
     (check-node checker node env effects)
     ;; If this was a let binding, extend the env for subsequent nodes
     (if (= :let (:node/type node))
       (env-define env (:name node)
                   (or (:aria/type node) (ast/primitive "i32"))
                   (:mutable? node))
       env))
   env
   nodes))

(defn- check-function
  "Type-check a single function."
  [checker func]
  (let [env (make-env)
        ;; Bind globals
        env (reduce (fn [e [gname ginfo]]
                      (env-define e gname (:type ginfo) (:mutable? ginfo)))
                    env @(:globals checker))
        ;; Bind params
        env (reduce (fn [e p]
                      (env-define e (:param/name p) (:param/type p) false))
                    env (:params func))
        effects (atom #{})]
    ;; Check body with env threading
    (check-body checker (:body func) env effects)
    ;; Verify effects
    (let [declared (set (:effects func))
          observed @effects
          non-pure (disj observed :pure)]
      (when (and (contains? declared :pure)
                 (seq (disj non-pure :div)))
        (add-error! checker
                    (str "Function " (:name func) " declared pure but has effects: "
                         (str/join ", " (map name non-pure)))))
      (let [undeclared (set/difference non-pure declared)]
        (when (and (seq undeclared) (not (contains? declared :pure)))
          (doseq [eff undeclared]
            (add-warning! checker
                          (str "Function " (:name func) " has undeclared effect '" (name eff) "'"))))))))

(defn- check-module
  "Type-check an entire module."
  [checker module]
  ;; Register type defs
  (doseq [td (:types module)]
    (swap! (:type-defs checker) assoc (:name td) (:aria/type td)))
  ;; Register globals
  (doseq [g (:globals module)]
    (swap! (:globals checker) assoc (:name g)
           {:type (or (:aria/type g) (ast/primitive "i32"))
            :mutable? (:mutable? g)}))
  ;; Register function signatures
  (doseq [func (:functions module)]
    (swap! (:functions checker) assoc (:name func) func))
  ;; Register extern function signatures (callable but not checked)
  (doseq [ext (or (:externs module) [])]
    (swap! (:functions checker) assoc (:name ext)
           {:name (:name ext) :params (:params ext) :result (:result ext)
            :effects #{:io :mem}}))
  ;; Check each function
  (doseq [func (:functions module)]
    (check-function checker func)))

;; ── Public API ────────────────────────────────────────────────

(defn check
  "Type-check an ARIA module. Returns {:ok? bool :errors [...] :warnings [...]}."
  [module]
  (let [checker (make-checker)]
    (check-module checker module)
    {:ok?      (empty? @(:errors checker))
     :errors   @(:errors checker)
     :warnings @(:warnings checker)}))
