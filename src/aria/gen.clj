(ns aria.gen
  "aria_gen — LLM-powered ARIA-IR Generator.

   Prompts Claude to generate valid ARIA-IR from natural language,
   validates through the parser and type checker, and retries on failure.

   Usage:
     clojure -M:gen \"sort a list of integers\"
     clojure -M:gen --run \"print hello world\"
     clojure -M:gen --emit-c \"compute fibonacci of 10\"
     clojure -M:gen --model claude-sonnet-4-20250514 \"sum an array\""
  (:require [aria.parser :as parser]
            [aria.checker :as checker]
            [aria.codegen-c :as codegen]
            [aria.main :as main]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
                          HttpResponse$BodyHandlers]))

;; ── System Prompt ──────────────────────────────────────────

(defn- condensed-spec []
  "### Module Structure
```
(module \"name\"
  (type ...)       ; type definitions
  (global ...)     ; global variables
  (func ...)       ; function definitions
  (export ...)     ; exported symbols
)
```

### Function Structure
```
(func $name
  (param $p1 type1) (param $p2 type2) ...
  (result return_type)
  (effects pure|io|mem|div ...)
  (intent \"description\")
  (local mut $var type init)  ; mutable local
  body...
)
```

### Types
- Signed integers: i8, i16, i32, i64
- Unsigned integers: u8, u16, u32, u64
- Floating point: f32, f64
- Boolean: bool
- Pointer: (ptr T)
- Array: (array T size)
- Void: void

### Instructions
- Arithmetic (type-suffixed): add.i32, sub.i32, mul.i32, div.i32, rem.i32, neg.i32
- Comparison (type-suffixed, returns bool): eq.i32, ne.i32, lt.i32, le.i32, gt.i32, ge.i32
- Bitwise: and.i32, or.i32, xor.i32, shl.i32, shr.i32, not.i32
- Math: sqrt.f64, abs.i32, ceil.f64, floor.f64
- Bindings: (let %name type expr), (local mut $name type init), (set $name expr)
- Control: (if cond (then ...) (else ...)), (loop $label ...), (br $label), (br_if cond $label)
- Functions: (call $func args...), (return expr)
- Memory: (alloc type count), (free ptr), (load.T ptr), (store.T ptr val)
- Output: (print \"fmt\" args...)
- Cast: (cast from_type to_type expr)
- Intent: (intent \"description\")

### Effects
- pure: no side effects
- io: performs I/O (print)
- mem: memory operations (alloc, free, load, store)
- div: may diverge (loops, recursion)

### Variable Naming
- $name: mutable variables (locals declared with `local mut`)
- %name: SSA values (immutable, from `let` bindings)")

(def ^:private example-descriptions
  {"fibonacci.aria"   "Compute Fibonacci numbers using recursion and iteration"
   "math_demo.aria"   "Mathematical functions: GCD, factorial, primality test, fast exponentiation"
   "bubble_sort.aria" "Sort an array using bubble sort with pointer operations"})

(def ^:private example-order
  ["fibonacci.aria" "math_demo.aria" "bubble_sort.aria"])

(defn- load-examples []
  (let [examples-dir (io/file "examples")]
    (->> example-order
         (keep (fn [filename]
                 (let [f (io/file examples-dir filename)]
                   (when (.exists f)
                     (let [desc (get example-descriptions filename filename)
                           source (slurp f)]
                       (str "### " desc "\n\n" source))))))
         (str/join "\n\n"))))

(defn- build-system-prompt []
  (str "You are an ARIA-IR code generator. You translate natural language descriptions into valid ARIA-IR programs.

## ARIA-IR Syntax Reference

" (condensed-spec) "

## Rules

1. Output ONLY raw ARIA-IR source code — no markdown fences, no explanation, no commentary.
2. Always wrap the program in `(module \"name\" ...)`.
3. Always include a `$main` function with `(result i32)` and `(export $main)`.
4. The `$main` function must end with `(return 0)`.
5. Add `(intent \"...\")` to every function describing what it does.
6. Declare effects correctly: `pure` for no side effects, `io` for print, `mem` for pointer/alloc/load/store, `div` for loops.
7. Use `$name` for mutable variables and `%name` for SSA bindings (immutable let bindings).
8. All arithmetic/comparison ops are type-suffixed: `add.i32`, `mul.f64`, `eq.i32`, etc.
9. Loops use `(loop $label body...)` with `(br $label)` to break out.
10. Conditionals use `(if cond (then ...) (else ...))`.
11. Mutable locals are declared with `(local mut $name type init)`.
12. Immutable SSA bindings use `(let %name type expr)`.
13. Mutable assignment uses `(set $name expr)`.
14. Function calls use `(call $func_name args...)`.
15. Print uses `(print \"format string\" args...)` with C-style format specifiers.
16. For pointer arithmetic and array access, use `(add.i32 ptr index)` to offset, then `(load.i32 ptr)` / `(store.i32 ptr val)`.
17. Use `(cast from_type to_type expr)` for type conversions (e.g., `(cast i32 i64 $x)`).
18. SCOPING: Declare ALL mutable locals (`local mut`) at the TOP of the function body, BEFORE any loops, conditionals, or blocks. Variables declared inside a loop, if, or block are scoped to that construct and invisible outside.
19. A variable must be declared BEFORE it is referenced. `(set $x ...)` and `$x` in expressions will fail if `$x` was not declared above via `(local mut $x ...)`.
20. Keep programs simple and flat. Prefer a single loop with conditionals inside rather than deeply nested structures.

## Examples

" (load-examples)))

;; ── HTTP API Call ──────────────────────────────────────────

(def ^:private http-client
  (delay (-> (HttpClient/newBuilder)
             (.build))))

(defn- call-api
  "Call the Anthropic Messages API. On retry, sends a 3-message conversation
   with the previous bad output and error context."
  [api-key system user-prompt model error-context]
  (let [messages (if error-context
                   [{"role" "user", "content" user-prompt}
                    {"role" "assistant", "content" error-context}
                    {"role" "user", "content"
                     (str "The ARIA-IR you produced has validation errors. "
                          "Please fix all errors and output the corrected ARIA-IR. "
                          "Common cause: variables used inside loops/if/blocks must be "
                          "declared with (local mut $name type init) at the TOP of the "
                          "function body BEFORE any loops or conditionals. "
                          "Output ONLY raw ARIA-IR, no explanation.")}]
                   [{"role" "user", "content" user-prompt}])
        body (json/write-str {"model"      model
                              "max_tokens" 4096
                              "system"     system
                              "messages"   messages})
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
      (throw (ex-info "Invalid ANTHROPIC_API_KEY. Check your key at https://console.anthropic.com/"
                      {:type :auth-error})))
    (when-not (<= 200 status 299)
      (throw (ex-info (str "API request failed with status " status ": " (.body response))
                      {:type :api-error :status status :body (.body response)})))
    (let [resp (json/read-str (.body response))
          content (get resp "content")]
      (get (first content) "text"))))

;; ── Extract & Validate ─────────────────────────────────────

(defn- extract-aria-source
  "Strip markdown code fences if present."
  [response-text]
  (-> response-text
      str/trim
      (str/replace #"^```(?:aria|aria-ir|lisp|scheme|s-expression|sexp)?\s*\n" "")
      (str/replace #"\n```\s*$" "")
      str/trim))

(defn- validate
  "Parse and type-check ARIA source. Returns [module errors]."
  [source]
  (try
    (let [module (parser/parse source)
          result (checker/check module)]
      (if (seq (:errors result))
        [module (mapv #(str "Type error: " %) (:errors result))]
        [module []]))
    (catch clojure.lang.ExceptionInfo e
      [nil [(str "Parse error: " (.getMessage e))]])
    (catch Exception e
      [nil [(str "Unexpected parse error: " (.getMessage e))]])))

;; ── Generate (retry loop) ──────────────────────────────────

(defn generate
  "Generate valid ARIA-IR from a natural language prompt.
   Returns the IR source string. Throws ex-info on failure."
  [prompt & {:keys [model max-retries]
             :or   {model       "claude-sonnet-4-20250514"
                    max-retries 3}}]
  (let [api-key (System/getenv "ANTHROPIC_API_KEY")]
    (when-not (seq api-key)
      (throw (ex-info (str "ANTHROPIC_API_KEY is not set.\n"
                           "  export ANTHROPIC_API_KEY='sk-ant-...'\n"
                           "  Get a key at https://console.anthropic.com/")
                      {:type :config-error})))
    (let [system (build-system-prompt)]
      (loop [attempt     1
             last-source nil
             last-errors []]
        (if (> attempt max-retries)
          (throw (ex-info (str "Failed to generate valid ARIA-IR after " max-retries " attempts")
                          {:type :gen-error :errors last-errors :last-source last-source}))
          (let [error-context (when (and (seq last-errors) last-source)
                                (str last-source
                                     "\n\n--- VALIDATION ERRORS ---\n"
                                     (str/join "\n" last-errors)))
                raw    (call-api api-key system prompt model error-context)
                source (extract-aria-source raw)
                [_ errors] (validate source)]
            (if (empty? errors)
              source
              (do
                (binding [*out* *err*]
                  (println (str "aria_gen: attempt " attempt "/" max-retries
                                " failed (" (count errors) " error(s)), retrying...")))
                (recur (inc attempt) source errors)))))))))

;; ── CLI ────────────────────────────────────────────────────

(defn- parse-args [args]
  (loop [args args
         opts {}]
    (if (empty? args)
      opts
      (let [arg (first args)]
        (cond
          (= arg "--emit-c")      (recur (rest args) (assoc opts :emit-c true))
          (= arg "--run")         (recur (rest args) (assoc opts :run true))
          (= arg "--model")       (recur (drop 2 args) (assoc opts :model (second args)))
          (= arg "--max-retries") (recur (drop 2 args) (assoc opts :max-retries (parse-long (second args))))
          (= arg "-o")            (recur (drop 2 args) (assoc opts :output (second args)))
          (str/starts-with? arg "-")
          (do (println (str "Unknown option: " arg))
              (System/exit 1))
          :else (recur (rest args) (assoc opts :prompt arg)))))))

(defn -main [& args]
  (if (empty? args)
    (do (println "Usage: aria_gen <prompt> [--run | --emit-c | --model M | --max-retries N | -o file]")
        (System/exit 1))
    (let [opts (parse-args args)]
      (when-not (:prompt opts)
        (println "No prompt specified.")
        (System/exit 1))
      (let [gen-opts (cond-> {}
                       (:model opts)       (assoc :model (:model opts))
                       (:max-retries opts) (assoc :max-retries (:max-retries opts)))
            source (try
                     (apply generate (:prompt opts) (mapcat identity gen-opts))
                     (catch clojure.lang.ExceptionInfo e
                       (binding [*out* *err*]
                         (println (str "aria_gen: " (.getMessage e)))
                         (doseq [err (:errors (ex-data e))]
                           (println (str "  " err)))
                         (when-let [src (:last-source (ex-data e))]
                           (println "\n--- Last generated source ---")
                           (println src)))
                       (System/exit 1)))]
        (cond
          ;; --run: parse + check + codegen + gcc + execute
          (:run opts)
          (let [module (parser/parse source)
                result (checker/check module)]
            (when-not (:ok? result)
              (binding [*out* *err*]
                (println "aria_gen: type errors in generated code:")
                (doseq [e (:errors result)]
                  (println (str "  " e))))
              (System/exit 1))
            (let [c-source (codegen/generate module)
                  tmp-c    (str "/tmp/aria_gen_" (:name module) ".c")
                  tmp-bin  (str "/tmp/aria_gen_" (:name module))]
              (spit tmp-c c-source)
              (println "Compiling...")
              (let [gcc-rc (main/compile-c! tmp-c tmp-bin)]
                (when-not (zero? gcc-rc)
                  (System/exit gcc-rc))
                (println "Running...")
                (println "---")
                (let [rc (main/run-binary! tmp-bin)]
                  (println "---")
                  (println (str "Exit code: " rc))
                  (System/exit rc)))))

          ;; --emit-c: parse + check + codegen
          (:emit-c opts)
          (let [module (parser/parse source)
                result (checker/check module)]
            (when-not (:ok? result)
              (binding [*out* *err*]
                (println "aria_gen: type errors in generated code:")
                (doseq [e (:errors result)]
                  (println (str "  " e))))
              (System/exit 1))
            (let [c-source (codegen/generate module)]
              (if (:output opts)
                (do (spit (:output opts) c-source)
                    (println (str "Wrote C to " (:output opts))))
                (println c-source))))

          ;; Default: print ARIA-IR source
          :else
          (if (:output opts)
            (do (spit (:output opts) source)
                (println (str "Wrote ARIA-IR to " (:output opts))))
            (println source)))))))
