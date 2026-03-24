(ns aria.reader
  "ARIA-IR reader — parses .aria source into Clojure data structures.

   ARIA-IR is s-expressions with three quirks vs standard EDN:
   1. $var and %var prefixes for mutable/immutable variables
   2. Dotted operation names like add.i32, load.field
   3. ; line comments (same as Clojure)

   Strategy: pre-process the source to make it valid EDN, then use
   clojure.edn/read. Variables become symbols with metadata markers."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

;; ARIA uses $name for mutable vars and %name for SSA/immutable vars.
;; These aren't valid EDN symbols, so we convert them:
;;   $foo  -> aria$foo   (tagged with :aria/mutable)
;;   %foo  -> aria%foo   (tagged with :aria/ssa)
;;
;; Dotted ops like add.i32 are valid Clojure symbols (namespace/name),
;; but we want them as flat symbols. EDN reads add.i32 as an error
;; because the part after the dot looks like an unqualified name.
;; We handle this by reading with a custom approach.

(defn- preprocess
  "Transform ARIA source into something clojure.edn/read can handle.
   - $var -> aria$var
   - %var -> aria%var
   Handles these inside s-expressions but not inside strings."
  [source]
  (let [sb (StringBuilder.)
        len (count source)]
    (loop [i 0
           in-string? false
           escape? false]
      (if (>= i len)
        (str sb)
        (let [c (.charAt ^String source i)]
          (cond
            ;; Inside string: just track escapes
            escape?
            (do (.append sb c)
                (recur (inc i) in-string? false))

            (and in-string? (= c \\))
            (do (.append sb c)
                (recur (inc i) in-string? true))

            (and in-string? (= c \"))
            (do (.append sb c)
                (recur (inc i) false false))

            in-string?
            (do (.append sb c)
                (recur (inc i) true false))

            ;; Start of string
            (= c \")
            (do (.append sb c)
                (recur (inc i) true false))

            ;; Line comment — pass through
            (= c \;)
            (let [newline-idx (or (str/index-of source "\n" i) len)]
              ;; skip comment entirely (edn/read handles them natively...
              ;; but our workaround below needs them stripped since we
              ;; convert to EDN-safe format)
              (.append sb (subs source i newline-idx))
              (recur (int newline-idx) false false))

            ;; $ or % variable prefix — convert to aria$name / aria%name
            (and (or (= c \$) (= c \%))
                 ;; Must be preceded by whitespace/paren (start of token)
                 (or (zero? i)
                     (let [prev (.charAt ^String source (dec i))]
                       (or (Character/isWhitespace prev)
                           (= prev \()))))
            (do (.append sb "aria")
                (.append sb c)
                (recur (inc i) false false))

            :else
            (do (.append sb c)
                (recur (inc i) false false))))))))

(def ^:private aria-readers
  "Custom EDN tagged reader map. Currently empty — we handle ARIA
   conventions via symbol naming rather than tagged literals."
  {})

(defn- make-edn-readable
  "Convert dotted symbols that EDN can't handle. EDN treats foo.bar as
   an error or namespace-qualified symbol depending on reader.
   We pre-convert add.i32 -> add·i32 then post-convert back, but
   actually Clojure's reader handles dots in symbols fine for our case.

   Actually, let's just use Clojure's read-string which is more lenient
   than edn/read-string for dotted symbols."
  [source]
  source)

(defn read-aria
  "Read ARIA-IR source string into Clojure data structures.
   Returns the raw s-expression form (a list starting with 'module)."
  [source]
  (let [preprocessed (preprocess source)]
    ;; Use Clojure's reader (not EDN) because:
    ;; 1. Clojure reader handles dotted symbols (add.i32) naturally
    ;; 2. Handles ; comments natively
    ;; 3. More permissive with symbol characters
    ;; We bind *read-eval* to false for safety.
    (binding [*read-eval* false]
      (read-string preprocessed))))

(defn read-all-aria
  "Read all top-level s-expressions from ARIA source string.
   Returns a vector of raw forms. Used for parsing comptime output
   which may contain multiple top-level definitions."
  [source]
  (let [preprocessed (preprocess source)]
    (binding [*read-eval* false]
      (let [rdr (java.io.PushbackReader. (java.io.StringReader. preprocessed))]
        (loop [forms []]
          (let [form (read rdr false ::eof)]
            (if (= form ::eof)
              forms
              (recur (conj forms form)))))))))

(defn read-aria-file
  "Read an ARIA-IR source file."
  [path]
  (read-aria (slurp path)))

;; --- Variable helpers ---

(defn aria-var?
  "Is this symbol an ARIA variable ($name or %name)?"
  [sym]
  (and (symbol? sym)
       (let [n (name sym)]
         (or (str/starts-with? n "aria$")
             (str/starts-with? n "aria%")))))

(defn mutable-var?
  "Is this an ARIA mutable variable ($name)?"
  [sym]
  (and (symbol? sym)
       (str/starts-with? (name sym) "aria$")))

(defn ssa-var?
  "Is this an ARIA SSA/immutable variable (%name)?"
  [sym]
  (and (symbol? sym)
       (str/starts-with? (name sym) "aria%")))

(defn var-name
  "Get the original ARIA variable name (with $ or % prefix) from the
   mangled symbol."
  [sym]
  (let [n (name sym)]
    (cond
      (str/starts-with? n "aria$") (subs n 4)  ; "aria$foo" -> "$foo"
      (str/starts-with? n "aria%") (subs n 4)  ; "aria%foo" -> "%foo"
      :else n)))

(defn raw-var-name
  "Get the bare name without any prefix (no $, %, or aria)."
  [sym]
  (let [n (name sym)]
    (cond
      (str/starts-with? n "aria$") (subs n 5)  ; "aria$foo" -> "foo"
      (str/starts-with? n "aria%") (subs n 5)  ; "aria%foo" -> "foo"
      (str/starts-with? n "$") (subs n 1)
      (str/starts-with? n "%") (subs n 1)
      :else n)))
