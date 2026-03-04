(ns aria.main
  "ARIA Compiler CLI — ariac equivalent.

   Usage:
     clojure -M:run <file.aria>                   Parse + type-check + emit C
     clojure -M:run <file.aria> --emit-c          Emit C to stdout
     clojure -M:run <file.aria> --check            Type-check only
     clojure -M:run <file.aria> --emit-ast         Dump AST
     clojure -M:run <file.aria> --run              Compile + execute
     clojure -M:run <file.aria> -o output.c        Write C to file"
  (:require [aria.parser :as parser]
            [aria.checker :as checker]
            [aria.codegen-c :as codegen]
            [clojure.pprint :as pp]
            [clojure.string :as str])
  (:import [java.io File]))

(defn compile-c!
  "Compile a C file with gcc and return the output binary path."
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

(defn run-binary!
  "Run a compiled binary and print its output."
  [path]
  (let [pb (doto (ProcessBuilder. [path])
             (.inheritIO))]
    (let [proc (.start pb)]
      (.waitFor proc)
      (.exitValue proc))))

(defn- process-file
  [path {:keys [emit-c emit-ast check-only run output]}]
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

      :else
      (let [c-source (codegen/generate module)]
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
          (= arg "--emit-ast") (recur (rest args) (assoc opts :emit-ast true))
          (= arg "--check")    (recur (rest args) (assoc opts :check-only true))
          (= arg "--run")      (recur (rest args) (assoc opts :run true))
          (= arg "-o")         (recur (drop 2 args) (assoc opts :output (second args)))
          (str/starts-with? arg "-")
          (do (println (str "Unknown option: " arg))
              (System/exit 1))
          :else (recur (rest args) (assoc opts :file arg)))))))

(defn -main [& args]
  (if (empty? args)
    (do (println "Usage: ariac <file.aria> [--emit-c | --emit-ast | --check | --run | -o file.c]")
        (System/exit 1))
    (let [opts (parse-args args)]
      (if-let [path (:file opts)]
        (process-file path opts)
        (do (println "No input file specified.")
            (System/exit 1))))))
