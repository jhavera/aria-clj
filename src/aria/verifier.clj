(ns aria.verifier
  "Layer 4 intent verification for ARIA-IR.
  Verifies that each function's implementation matches its declared
  (intent ...) annotation using the Claude API.
  Requires ANTHROPIC_API_KEY environment variable.
  Entry points: verify, format-report"
  (:require [clojure.string :as str]
            [clojure.data.json :as json]
            [aria.parser :as parser]
            [aria.checker :as checker]
            [aria.codegen-c :as codegen-c])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest
                          HttpRequest$BodyPublishers
                          HttpResponse$BodyHandlers]
           [java.io File]
           [java.lang ProcessBuilder]))

;; ── Intent extraction ────────────────────────────────────────

(defn- extract-intent
  "Returns the intent string from a function body, or nil if none declared."
  [func]
  (or (:intent func)
      (->> (:body func)
           (filter #(= :intent (:node/type %)))
           first
           :description)))

;; ── ARIA-IR reconstruction ───────────────────────────────────

(declare reconstruct-aria)

(defn- reconstruct-aria
  "Reconstruct a readable ARIA-IR string from an AST node."
  [node]
  (case (:node/type node)
    :int-literal    (str (:value node))
    :float-literal  (str (:value node))
    :bool-literal   (if (:value node) "true" "false")
    :string-literal (str "\"" (:value node) "\"")
    :var-ref        (:name node)

    :bin-op
    (let [op-name (name (:op node))]
      (str "(" op-name "." (:type-suffix node) " "
           (reconstruct-aria (:left node)) " "
           (reconstruct-aria (:right node)) ")"))

    :comparison
    (let [op-name (name (:op node))]
      (str "(" op-name "." (:type-suffix node) " "
           (reconstruct-aria (:left node)) " "
           (reconstruct-aria (:right node)) ")"))

    :unary-op
    (let [op-name (name (:op node))]
      (str "(" op-name "." (:type-suffix node) " "
           (reconstruct-aria (:operand node)) ")"))

    :let
    (str "(let " (:name node) " "
         (when-let [t (:aria/type node)]
           (str (or (:type/name t) "?") " "))
         (reconstruct-aria (:value node)) ")")

    :set-var
    (str "(set " (:name node) " "
         (reconstruct-aria (:value node)) ")")

    :return
    (if (:value node)
      (str "(return " (reconstruct-aria (:value node)) ")")
      "(return)")

    :if
    (str "(if " (reconstruct-aria (:cond node)) "\n"
         "  (then " (str/join "\n    " (map reconstruct-aria (:then-body node))) ")\n"
         "  (else " (str/join "\n    " (map reconstruct-aria (:else-body node))) "))")

    :loop
    (str "(loop " (:label node) "\n"
         "  " (str/join "\n  " (map reconstruct-aria (:body node))) ")")

    :call
    (str "(call " (:target node)
         (when (seq (:args node))
           (str " " (str/join " " (map reconstruct-aria (:args node)))))
         ")")

    :print
    (str "(print \"" (:format-str node) "\""
         (when (seq (:args node))
           (str " " (str/join " " (map reconstruct-aria (:args node)))))
         ")")

    :alloc
    (let [t (:alloc-type node)]
      (str "(alloc " (or (:type/name t) (str t))
           (when (:count node)
             (str " " (reconstruct-aria (:count node))))
           ")"))

    :load
    (str "(load." (:type-suffix node) " "
         (reconstruct-aria (:ptr node)) ")")

    :store
    (str "(store." (:type-suffix node) " "
         (reconstruct-aria (:ptr node)) " "
         (reconstruct-aria (:value node)) ")")

    :free
    (str "(free " (reconstruct-aria (:ptr node)) ")")

    :cast
    (let [from (:from-type node)
          to (:to-type node)]
      (str "(cast " (or (:type/name from) (str from)) " "
           (or (:type/name to) (str to)) " "
           (reconstruct-aria (:value node)) ")"))

    :intent nil

    :branch
    (str "(br " (:label node) ")")

    ;; Default
    (str "<" (:node/type node) ">")))

(defn- type-str
  "Render a type as a string."
  [t]
  (when t
    (case (:type/kind t)
      :primitive (:type/name t)
      :ptr       (str "(ptr " (type-str (:type/pointee t)) ")")
      :array     (str "(array " (type-str (:type/elem t)) " " (:type/size t) ")")
      :slice     (str "(slice " (type-str (:type/elem t)) ")")
      :struct    (str "(struct " (:type/name t) ")")
      (str t))))

(defn- function->aria-str
  "Render a function as a readable ARIA-IR string for the prompt.
  Includes params, result type, effects, and reconstructed body."
  [func]
  (str "(func " (:name func) "\n"
       (str/join "\n"
         (concat
           (map (fn [p]
                  (str "  (param " (:param/name p) " " (type-str (:param/type p)) ")"))
                (:params func))
           (when (:result func)
             [(str "  (result " (type-str (:result func)) ")")])
           (when (seq (:effects func))
             [(str "  (effects " (str/join " " (map name (:effects func))) ")")])
           (keep (fn [node]
                   (when-not (= :intent (:node/type node))
                     (str "  " (reconstruct-aria node))))
                 (:body func))))
       ")"))

;; ── HTTP client ──────────────────────────────────────────────

(def ^:private http-client
  (delay (-> (HttpClient/newBuilder) (.build))))

;; ── API call ─────────────────────────────────────────────────

(defn- call-api
  "Call the Anthropic Messages API for intent verification."
  [api-key user-message model max-tokens]
  (let [body (json/write-str {"model"      model
                              "max_tokens" max-tokens
                              "messages"   [{"role" "user" "content" user-message}]})
        request (-> (HttpRequest/newBuilder)
                    (.uri (URI/create "https://api.anthropic.com/v1/messages"))
                    (.header "Content-Type" "application/json")
                    (.header "x-api-key" api-key)
                    (.header "anthropic-version" "2023-06-01")
                    (.POST (HttpRequest$BodyPublishers/ofString body))
                    (.build))
        response (.send @http-client request (HttpResponse$BodyHandlers/ofString))
        status (.statusCode response)]
    (when (= status 401)
      (throw (ex-info "Invalid ANTHROPIC_API_KEY." {:type :auth-error})))
    (when-not (<= 200 status 299)
      (throw (ex-info (str "API request failed with status " status)
                      {:type :api-error :status status :body (.body response)})))
    (let [resp (json/read-str (.body response))
          content (get resp "content")]
      (get (first content) "text"))))

(defn- parse-verdict
  "Parse a VERIFIED/NOT_VERIFIED response into a result map."
  [response func-name intent]
  (let [lines (->> (str/split-lines response)
                   (remove str/blank?))
        verdict (str/trim (first lines))
        explanation (str/trim (or (second lines) "No explanation provided."))]
    {:function func-name
     :intent intent
     :verified? (= verdict "VERIFIED")
     :explanation explanation}))

;; ── Dynamic verification helpers ─────────────────────────────

(defn- primitive-type?
  "Returns true if the type is a primitive scalar (i32, i64, f64, bool, str)."
  [t]
  (and (= :primitive (:type/kind t))
       (#{"i32" "i64" "f64" "bool" "str"} (:type/name t))))

(defn- all-primitive-params?
  "Returns true if all function params are primitive scalar types."
  [func]
  (every? #(primitive-type? (:param/type %)) (:params func)))

(defn- print-format-for-type
  "Returns the printf format specifier for a primitive type."
  [type-name]
  (case type-name
    "i32"  "%d"
    "i64"  "%ld"
    "f64"  "%.6f"
    "bool" "%d"
    "str"  "%s"
    "%d"))

(defn- test-value-nodes
  "Returns a vector of 3 AST literal nodes appropriate for a primitive type."
  [type-name]
  (case type-name
    ("i32" "i64") [{:node/type :int-literal :value 0}
                   {:node/type :int-literal :value 1}
                   {:node/type :int-literal :value 5}]
    ("f32" "f64") [{:node/type :float-literal :value 0.0}
                   {:node/type :float-literal :value 1.0}
                   {:node/type :float-literal :value 2.5}]
    "bool"        [{:node/type :bool-literal :value false}
                   {:node/type :bool-literal :value true}
                   {:node/type :bool-literal :value false}]
    [{:node/type :int-literal :value 0}
     {:node/type :int-literal :value 1}
     {:node/type :int-literal :value 2}]))

(defn- test-value-strs
  "Returns a vector of 3 string representations of test values."
  [type-name]
  (case type-name
    ("i32" "i64") ["0" "1" "5"]
    ("f32" "f64") ["0.0" "1.0" "2.5"]
    "bool"        ["false" "true" "false"]
    ["0" "1" "2"]))

(defn- generate-wrapper-module
  "Generate a wrapper module AST that calls func with test inputs and
  prints the results. Embeds all original module functions.
  Only call this for functions where all-primitive-params? is true."
  [func module]
  (let [target-name (:name func)
        clean-name (-> target-name (str/replace #"^\$" ""))
        module-name (str "aria_verify_" (str/replace clean-name "-" "_"))
        params (:params func)
        result (:result func)
        has-printable-result? (and result
                                   (= :primitive (:type/kind result))
                                   (not= "void" (:type/name result)))
        ;; Handle $main naming conflict
        is-main? (= target-name "$main")
        call-target (if is-main? "$aria_target" target-name)
        ;; Prepare original functions:
        ;; - If verifying $main: rename it to $aria_target
        ;; - Otherwise: remove original $main (our wrapper takes its place)
        orig-funcs (if is-main?
                     (mapv (fn [f]
                             (if (= (:name f) "$main")
                               (assoc f :name "$aria_target")
                               f))
                           (:functions module))
                     (filterv #(not= (:name %) "$main") (:functions module)))
        ;; Build wrapper body
        body (if (empty? params)
               ;; No params: call once
               (let [call {:node/type :call :target call-target :args []}]
                 (if has-printable-result?
                   [{:node/type :print
                     :format-str (str clean-name "()=" (print-format-for-type (:type/name result)) "\n")
                     :args [call]}
                    {:node/type :return :value {:node/type :int-literal :value 0}}]
                   [call
                    {:node/type :return :value {:node/type :int-literal :value 0}}]))
               ;; Has params: 3 test runs
               (let [val-nodes (mapv #(test-value-nodes (:type/name (:param/type %))) params)
                     val-strs (mapv #(test-value-strs (:type/name (:param/type %))) params)]
                 (vec (concat
                        (for [i (range 3)]
                          (let [args (mapv #(nth % i) val-nodes)
                                arg-strs (mapv #(nth % i) val-strs)
                                call {:node/type :call :target call-target :args args}]
                            (if has-printable-result?
                              {:node/type :print
                               :format-str (str clean-name "(" (str/join ", " arg-strs) ")="
                                                (print-format-for-type (:type/name result)) "\n")
                               :args [call]}
                              call)))
                        [{:node/type :return :value {:node/type :int-literal :value 0}}]))))
        wrapper {:node/type :function
                 :name "$main"
                 :params []
                 :result {:type/kind :primitive :type/name "i32"}
                 :effects #{:io :mem :div}
                 :intent "Verification wrapper"
                 :body body}]
    {:node/type :module
     :name module-name
     :types (or (:types module) [])
     :globals (or (:globals module) [])
     :functions (conj orig-funcs wrapper)
     :exports [{:node/type :export :name "$main" :alias nil}]
     :externs (or (:externs module) [])}))

;; ── Compile and run ──────────────────────────────────────────

(defn- compile-and-run!
  "Compile a checked ARIA module to C, compile with gcc, run, and
  capture stdout. Returns {:ok? bool :stdout str :error str-or-nil}.
  Cleans up all temp files on exit."
  [module]
  (let [c-source (codegen-c/generate module)
        tmp-name (str "aria_verify_" (:name module))
        tmp-c    (str "/tmp/" tmp-name ".c")
        tmp-bin  (str "/tmp/" tmp-name)]
    (try
      (spit tmp-c c-source)
      (let [gcc-proc (.start (ProcessBuilder.
                               ["gcc" "-std=c99" "-Wall" "-o"
                                tmp-bin tmp-c "-lm"]))
            _ (.waitFor gcc-proc)]
        (if-not (zero? (.exitValue gcc-proc))
          {:ok? false :stdout ""
           :error (slurp (.getErrorStream gcc-proc))}
          (let [run-proc (.start (ProcessBuilder. [tmp-bin]))
                stdout   (slurp (.getInputStream run-proc))
                _        (.waitFor run-proc)]
            {:ok? true :stdout stdout :error nil})))
      (finally
        (.delete (File. tmp-c))
        (.delete (File. tmp-bin))))))

;; ── Dynamic verification ─────────────────────────────────────

(defn- dynamic-verify-function
  "Verify a function by compiling and running it with test inputs,
  then asking Claude whether the observed output matches the intent.
  Returns {:function str :intent str :verified? bool :explanation str}."
  [func module api-key model]
  (let [func-name (:name func)
        intent (extract-intent func)]
    (try
      (let [wrapper-module (generate-wrapper-module func module)
            check-result (checker/check wrapper-module)]
        (if-not (:ok? check-result)
          {:function func-name :intent intent :verified? false
           :explanation (str "Wrapper generation failed: "
                             (str/join "; " (:errors check-result)))}
          (let [run-result (compile-and-run! wrapper-module)]
            (if-not (:ok? run-result)
              {:function func-name :intent intent :verified? false
               :explanation (str "Compilation failed: " (:error run-result))}
              (let [prompt (str "You are verifying that a function's observed behavior matches "
                                "its declared intent.\n\n"
                                "Function: " func-name "\n"
                                "Declared intent: \"" intent "\"\n"
                                "Observed behavior:\n" (:stdout run-result) "\n"
                                "Does the observed output show correct results consistent with "
                                "the declared intent? Focus on whether the function produces the "
                                "right values — ignore implementation details like algorithm "
                                "choice, performance characteristics, or method names.\n"
                                "Respond with exactly two lines:\n"
                                "Line 1: VERIFIED or NOT_VERIFIED\n"
                                "Line 2: One sentence explaining why.")
                    response (call-api api-key prompt model 256)]
                (parse-verdict response func-name intent))))))
      (catch Exception e
        {:function func-name :intent intent :verified? false
         :explanation (str "Verification unavailable: " (.getMessage e))}))))

;; ── Static verification (fallback for complex params) ────────

(defn- static-verify-function
  "Verify a function by asking Claude to read the ARIA-IR code.
  Used as fallback when dynamic verification is not possible
  (e.g., pointer/complex params).
  Returns {:function str :intent str :verified? bool :explanation str}."
  [func api-key model]
  (let [func-name (:name func)
        intent (extract-intent func)
        aria-str (function->aria-str func)
        prompt (str "You are verifying ARIA-IR code against its declared intent.\n\n"
                    "Function: " func-name "\n"
                    "Declared intent: \"" intent "\"\n\n"
                    "Implementation:\n" aria-str "\n\n"
                    "Does this implementation match the declared intent?\n"
                    "Respond with exactly two lines:\n"
                    "Line 1: VERIFIED or NOT_VERIFIED\n"
                    "Line 2: One sentence explaining why.")]
    (try
      (let [response (call-api api-key prompt model 256)]
        (parse-verdict response func-name intent))
      (catch Exception e
        {:function func-name :intent intent :verified? false
         :explanation (str "Verification unavailable: " (.getMessage e))}))))

;; ── Verification routing ─────────────────────────────────────

(defn- verify-function
  "Route to dynamic or static verification based on function signature.
  Dynamic verification is used when the function has only primitive
  params (or no params). Static is used for pointer/complex params."
  [func module api-key model]
  (if (all-primitive-params? func)
    (dynamic-verify-function func module api-key model)
    (static-verify-function func api-key model)))

;; ── Public API ───────────────────────────────────────────────

(defn verify
  "Verify intent annotations for a checked ARIA-IR module.
  Calls the Claude API for each function that declares an (intent ...).
  Functions without an intent annotation are skipped.
  Returns {:ok?     bool
           :results [{:function    str
                      :intent      str
                      :verified?   bool
                      :explanation str}]}.
  Requires ANTHROPIC_API_KEY in the environment.
  If the key is missing, returns {:ok? false :results []
  :error \"ANTHROPIC_API_KEY is not set\"}."
  ([module] (verify module {}))
  ([module {:keys [model] :or {model "claude-sonnet-4-20250514"}}]
   (let [api-key (System/getenv "ANTHROPIC_API_KEY")]
     (if-not (seq api-key)
       {:ok? false :results []
        :error "ANTHROPIC_API_KEY is not set. export ANTHROPIC_API_KEY='sk-ant-...'"}
       (let [funcs-with-intent (filter #(some? (extract-intent %))
                                       (:functions module))
             results (mapv #(verify-function % module api-key model) funcs-with-intent)
             all-ok? (every? :verified? results)]
         {:ok? all-ok?
          :results results})))))

(defn format-report
  "Format a verify result map as a human-readable string.
  Suitable for printing to stdout."
  [result]
  (if (:error result)
    (str "Intent verification skipped: " (:error result))
    (let [results (:results result)
          total (count results)
          verified (count (filter :verified? results))
          lines (mapv (fn [r]
                        (str "  " (:function r) (str (apply str (repeat (max 1 (- 20 (count (:function r)))) " ")))
                             (if (:verified? r) "VERIFIED" "NOT_VERIFIED") "\n"
                             "    \"" (:intent r) "\"\n"
                             "    " (:explanation r)))
                      results)
          summary (if (:ok? result)
                    (str "Verification passed. (" verified "/" total " functions verified)")
                    (str "Verification FAILED. (" verified "/" total " functions verified)"))]
      (str "Intent Verification Report\n"
           "--------------------------\n"
           (str/join "\n\n" lines)
           "\n\n" summary))))
