(ns aria.main
  "ARIA Compiler CLI — ariac equivalent.

   Usage:
     clojure -M:run <file.aria>                          Parse + type-check + emit C
     clojure -M:run <file.aria> --emit-c                 Emit C to stdout
     clojure -M:run <file.aria> --emit-wat               Emit WAT to stdout
     clojure -M:run <file.aria> --emit-ast               Dump AST
     clojure -M:run <file.aria> --check                  Type-check only
     clojure -M:run <file.aria> --run                    Compile + execute
     clojure -M:run <file.aria> --backend wasm -o out.wasm  WAT -> wasm pipeline
     clojure -M:run <file.aria> --backend wasm --run     Compile + run with wasmtime
     clojure -M:run <file.aria> -o output.c              Write C to file"
  (:require [aria.parser :as parser]
            [aria.checker :as checker]
            [aria.codegen-c :as codegen-c]
            [aria.codegen-wat :as codegen-wat]
            [aria.reader :as reader]
            [clojure.pprint :as pp]
            [clojure.string :as str])
  (:import [java.io File]))

(defn compile-c!
  "Compile a C file with gcc and return the exit code."
  [c-path out-path]
  (let [proc (.start (ProcessBuilder.
                      ["gcc" "-std=c99" "-Wall" "-o" out-path c-path "-lm"]))]
    (.waitFor proc)
    (when-not (zero? (.exitValue proc))
      (let [err (slurp (.getErrorStream proc))]
        (binding [*out* *err*]
          (println "gcc warnings/errors:")
          (println err))))
    (.exitValue proc)))

(defn compile-wat!
  "Assemble WAT to WASM binary using wat2wasm. Returns exit code."
  [wat-path wasm-path]
  (let [pb (ProcessBuilder. ["wat2wasm" wat-path "-o" wasm-path])
        proc (.start pb)]
    (.waitFor proc)
    (when-not (zero? (.exitValue proc))
      (let [err (slurp (.getErrorStream proc))]
        (binding [*out* *err*]
          (println "wat2wasm errors:")
          (println err))))
    (.exitValue proc)))

(defn optimize-wasm!
  "Optimize WASM binary using wasm-opt. Returns exit code."
  [wasm-path out-path]
  (let [pb (ProcessBuilder. ["wasm-opt" "-O3" wasm-path "-o" out-path])
        proc (.start pb)]
    (.waitFor proc)
    (when-not (zero? (.exitValue proc))
      (let [err (slurp (.getErrorStream proc))]
        (binding [*out* *err*]
          (println "wasm-opt errors:")
          (println err))))
    (.exitValue proc)))

(defn run-binary!
  "Run a compiled binary and print its output."
  [path]
  (let [pb (doto (ProcessBuilder. [path])
             (.inheritIO))]
    (let [proc (.start pb)]
      (.waitFor proc)
      (.exitValue proc))))

(defn run-wasm!
  "Run a WASI WASM binary using wasmtime. Returns exit code."
  [wasm-path]
  (let [pb (doto (ProcessBuilder. ["wasmtime" "run" wasm-path])
             (.inheritIO))
        proc (.start pb)]
    (.waitFor proc)
    (.exitValue proc)))

;; ── Comptime evaluation (two-pass) ──────────────────────────

(defn- comptime-form?
  "Is this raw s-expression a (comptime ...) block?"
  [form]
  (and (seq? form)
       (symbol? (first form))
       (= "comptime" (name (first form)))))

(defn- separate-comptime
  "Given the raw module form (module \"name\" form1 form2 ...),
   return {:module-head [module-sym name], :forms [...], :has-comptime? bool}.
   Each element in :forms is either a normal form or {:comptime-idx N :forms [...]}"
  [module-form]
  (let [[module-sym module-name & top-forms] module-form
        indexed (map-indexed
                 (fn [idx form]
                   (if (comptime-form? form)
                     {:comptime-idx idx :forms (rest form)}
                     form))
                 top-forms)]
    {:module-head [module-sym module-name]
     :forms (vec indexed)
     :has-comptime? (some map? indexed)}))

(defn- build-comptime-module
  "Wrap comptime forms in a temporary ARIA module source string."
  [idx forms]
  (let [module-name (str "comptime_" idx)
        ;; Convert forms back to source strings
        form-strs (map pr-str forms)]
    (str "(module \"" module-name "\"\n"
         (str/join "\n" form-strs)
         ")")))

(defn- compile-and-run-c!
  "Compile ARIA C source, run the binary, and return {:exit int :stdout string :stderr string}."
  [c-source module-name]
  (let [tmp-c (str "/tmp/aria_comptime_" module-name ".c")
        tmp-bin (str "/tmp/aria_comptime_" module-name)]
    (spit tmp-c c-source)
    (let [gcc-proc (.start (ProcessBuilder.
                            ["gcc" "-std=c99" "-Wall" "-fwrapv"
                             "-o" tmp-bin tmp-c "-lm"]))]
      (.waitFor gcc-proc)
      (if-not (zero? (.exitValue gcc-proc))
        (let [err (slurp (.getErrorStream gcc-proc))]
          {:exit (.exitValue gcc-proc)
           :stdout ""
           :stderr (str "gcc compilation failed:\n" err)})
        ;; Run the binary and capture stdout
        (let [run-pb (ProcessBuilder. [tmp-bin])
              run-proc (.start run-pb)
              stdout (slurp (.getInputStream run-proc))
              stderr (slurp (.getErrorStream run-proc))]
          (.waitFor run-proc)
          ;; Clean up temp files
          (.delete (File. tmp-c))
          (.delete (File. tmp-bin))
          {:exit (.exitValue run-proc)
           :stdout stdout
           :stderr stderr})))))

(defn- evaluate-comptime-block
  "Evaluate a single comptime block: compile as temp module via C backend, execute,
   return stdout as a string of ARIA s-expressions."
  [idx forms]
  (let [module-src (build-comptime-module idx forms)
        ;; Parse and compile the temp module through the normal pipeline
        module (parser/parse module-src)
        check-result (checker/check module)]
    (when-not (:ok? check-result)
      (throw (ex-info (str "Comptime block " idx " failed type-check")
                      {:errors (:errors check-result)})))
    (let [c-source (codegen-c/generate module)
          result (compile-and-run-c! c-source (str "comptime_" idx))]
      (when-not (zero? (:exit result))
        (throw (ex-info (str "Comptime block " idx " execution failed")
                        {:exit (:exit result)
                         :stderr (:stderr result)})))
      (:stdout result))))

(defn- splice-comptime-results
  "Replace comptime block placeholders with their evaluated results.
   Returns a new raw module form ready for normal parsing."
  [{:keys [module-head forms]} comptime-results]
  (let [expanded (mapcat
                  (fn [form]
                    (if (map? form)
                      ;; This is a comptime placeholder — get its result
                      (let [stdout (get comptime-results (:comptime-idx form))]
                        (if (str/blank? stdout)
                          []  ;; Empty output → remove cleanly
                          (reader/read-all-aria stdout)))
                      [form]))
                  forms)]
    (apply list (concat module-head expanded))))

(defn expand-comptime
  "Two-pass comptime expansion. Returns the expanded raw module form."
  [module-form]
  (let [{:keys [has-comptime?] :as separated} (separate-comptime module-form)]
    (if-not has-comptime?
      module-form  ;; Fast path: no comptime blocks
      (let [;; Evaluate all comptime blocks
            comptime-results
            (reduce
             (fn [results form]
               (if (map? form)
                 (let [idx (:comptime-idx form)]
                   (println (str "  Evaluating comptime block " idx "..."))
                   (assoc results idx (evaluate-comptime-block idx (:forms form))))
                 results))
             {}
             (:forms separated))]
        (splice-comptime-results separated comptime-results)))))

(defn- process-file
  [path {:keys [emit-c emit-wat emit-ast check-only run output backend optimize]}]
  (let [source (slurp path)
        _ (println (str "Parsing " path "..."))
        ;; Two-pass: expand comptime blocks before parsing
        raw-form (reader/read-aria source)
        expanded-form (expand-comptime raw-form)
        module (parser/parse-module expanded-form)
        _ (println (str "  Module: " (:name module)
                        " (" (count (:functions module)) " functions)"))

        ;; Type check
        _ (println "Type checking...")
        result (checker/check module)]

    (when (seq (:warnings result))
      (doseq [w (:warnings result)]
        (println (str "  Warning: " w))))

    (when-not (:ok? result)
      (doseq [e (:errors result)]
        (println (str "  Error: " e)))
      (println "Type checking failed.")
      (System/exit 1))

    (println "  OK")

    (cond
      emit-ast
      (do (println "\n=== AST ===")
          (pp/pprint module))

      check-only
      (println "Type check passed.")

      ;; WAT/WASM backend
      (or (= backend "wasm") emit-wat)
      (let [wasi? (and (= backend "wasm") (not emit-wat))
            wat-source (codegen-wat/generate module {:wasi? wasi?})]
        (cond
          emit-wat
          (println wat-source)

          run
          (let [tmp-wat (str "/tmp/aria_" (:name module) ".wat")
                tmp-wasm (str "/tmp/aria_" (:name module) ".wasm")]
            (spit tmp-wat wat-source)
            (println "Assembling WAT...")
            (let [wat-rc (compile-wat! tmp-wat tmp-wasm)]
              (when (zero? wat-rc)
                (when optimize
                  (println "Optimizing...")
                  (let [opt-wasm (str tmp-wasm ".opt")]
                    (when (zero? (optimize-wasm! tmp-wasm opt-wasm))
                      (.renameTo (File. opt-wasm) (File. tmp-wasm)))))
                (println "Running with wasmtime...")
                (println "---")
                (let [rc (run-wasm! tmp-wasm)]
                  (println "---")
                  (println (str "Exit code: " rc))
                  (System/exit rc)))))

          output
          (if (str/ends-with? output ".wasm")
            ;; Full pipeline: WAT -> wasm -> optimize
            (let [tmp-wat (str "/tmp/aria_" (:name module) ".wat")]
              (spit tmp-wat wat-source)
              (println "Assembling WAT...")
              (let [wat-rc (compile-wat! tmp-wat output)]
                (when (and (zero? wat-rc) optimize)
                  (println "Optimizing...")
                  (let [opt-wasm (str output ".opt")]
                    (when (zero? (optimize-wasm! output opt-wasm))
                      (.renameTo (File. opt-wasm) (File. output)))))
                (when (zero? wat-rc)
                  (println (str "Wrote WASM to " output)))))
            ;; Just write WAT
            (do (spit output wat-source)
                (println (str "Wrote WAT to " output))))

          :else
          (println wat-source)))

      ;; C backend (default)
      :else
      (let [c-source (codegen-c/generate module)]
        (cond
          emit-c
          (println c-source)

          run
          (let [tmp-c (str "/tmp/aria_" (:name module) ".c")
                tmp-bin (str "/tmp/aria_" (:name module))]
            (spit tmp-c c-source)
            (println "Compiling to C...")
            (let [gcc-rc (compile-c! tmp-c tmp-bin)]
              (when (zero? gcc-rc)
                (println "Running...")
                (println "---")
                (let [rc (run-binary! tmp-bin)]
                  (println "---")
                  (println (str "Exit code: " rc))
                  (System/exit rc)))))

          output
          (do (spit output c-source)
              (println (str "Wrote C to " output)))

          :else
          (println c-source))))))

(defn- parse-args [args]
  (loop [args args
         opts {}]
    (if (empty? args)
      opts
      (let [arg (first args)]
        (cond
          (= arg "--emit-c")   (recur (rest args) (assoc opts :emit-c true))
          (= arg "--emit-wat") (recur (rest args) (assoc opts :emit-wat true))
          (= arg "--emit-ast") (recur (rest args) (assoc opts :emit-ast true))
          (= arg "--check")    (recur (rest args) (assoc opts :check-only true))
          (= arg "--run")      (recur (rest args) (assoc opts :run true))
          (= arg "--optimize") (recur (rest args) (assoc opts :optimize true))
          (= arg "--backend")  (recur (drop 2 args) (assoc opts :backend (second args)))
          (= arg "-o")         (recur (drop 2 args) (assoc opts :output (second args)))
          (str/starts-with? arg "-")
          (do (println (str "Unknown option: " arg))
              (System/exit 1))
          :else (recur (rest args) (assoc opts :file arg)))))))

(defn -main [& args]
  (if (empty? args)
    (do (println "Usage: ariac <file.aria> [options]")
        (println "")
        (println "Options:")
        (println "  --emit-c          Emit C to stdout")
        (println "  --emit-wat        Emit WAT to stdout")
        (println "  --emit-ast        Dump AST")
        (println "  --check           Type-check only")
        (println "  --run             Compile + execute")
        (println "  --backend wasm    Use WASM backend")
        (println "  --optimize        Apply wasm-opt (WASM only)")
        (println "  -o <file>         Write output to file")
        (System/exit 1))
    (let [opts (parse-args args)]
      (if-let [path (:file opts)]
        (process-file path opts)
        (do (println "No input file specified.")
            (System/exit 1))))))
