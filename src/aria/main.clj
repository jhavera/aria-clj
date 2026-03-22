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

(defn- process-file
  [path {:keys [emit-c emit-wat emit-ast check-only run output backend optimize]}]
  (let [source (slurp path)
        _ (println (str "Parsing " path "..."))
        module (parser/parse source)
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
