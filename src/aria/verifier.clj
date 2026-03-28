(ns aria.verifier
  "Layer 4 intent verification for ARIA-IR.
  Verifies that each function's implementation matches its declared
  (intent ...) annotation using the Claude API.
  Requires ANTHROPIC_API_KEY environment variable.
  Entry points: verify, format-report"
  (:require [clojure.string :as str]
            [clojure.data.json :as json])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest
                          HttpRequest$BodyPublishers
                          HttpResponse$BodyHandlers]))

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
  [api-key user-message model]
  (let [body (json/write-str {"model"      model
                              "max_tokens" 256
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

;; ── Single function verification ─────────────────────────────

(defn- verify-function
  "Call the Claude API to verify one function's intent annotation.
  Returns {:function name :intent str :verified? bool :explanation str}."
  [func api-key model]
  (let [func-name (:name func)
        intent (extract-intent func)
        aria-str (function->aria-str func)
        prompt (str "You are verifying ARIA-IR code against its declared intent.\n\n"
                    "Function: " func-name "\n"
                    "Declared intent: \"" intent "\"\n\n"
                    "Implementation:\n" aria-str "\n\n"
                    "ARIA-IR semantics note:\n"
                    "- (br $label) exits the named loop — it is a break, not a continue\n"
                    "- (loop $label body...) repeats body until a (br $label) is reached\n"
                    "- The exit condition is placed in a (then (br $label)) branch\n"
                    "- The loop body runs in the (else ...) branch\n\n"
                    "Does this implementation correctly fulfill the declared intent?\n"
                    "Respond with exactly two lines:\n"
                    "Line 1: VERIFIED or NOT_VERIFIED\n"
                    "Line 2: One sentence explaining why.")]
    (try
      (let [response (call-api api-key prompt model)
            lines (->> (str/split-lines response)
                       (remove str/blank?))
            verdict (str/trim (first lines))
            explanation (str/trim (or (second lines) "No explanation provided."))]
        {:function func-name
         :intent intent
         :verified? (= verdict "VERIFIED")
         :explanation explanation})
      (catch Exception e
        {:function func-name
         :intent intent
         :verified? false
         :explanation (str "Verification unavailable: " (.getMessage e))}))))

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
             results (mapv #(verify-function % api-key model) funcs-with-intent)
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
