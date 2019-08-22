(ns latacora.wernicke.cli
  (:require
   [cheshire.core :as json]
   [clojure.tools.cli :as cli]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [latacora.wernicke.core :as wc])
  (:gen-class))

(def ^:private parsers
  {:json json/parse-stream
   :edn edn/read})

(def ^:private serializers
  {:json (fn [obj] (json/encode-stream obj *out*))
   :edn pr})

(defn format-opt
  [format-name impls]
  (let [short (str "-" (first format-name))
        long (format "--%s FORMAT" format-name)
        formats (->> impls keys (map name) (str/join ", "))
        message (format "%s format (one of %s)" format-name formats)
        formats-error (str "format must be one of " formats)]
    [short long message
     :id (keyword (str format-name "-fn"))
     :default (impls :json)
     :default-desc "json"
     :parse-fn (comp impls keyword str/lower-case)
     :validate [some? formats-error]]))

(def ^:private cli-opts
  [["-h" "--help" "display help message"]
   ["-v" "--verbose" "verbosity level"
    :id :verbosity
    :default 0
    :update-fn inc]
   (format-opt "input" parsers)
   (format-opt "output" serializers)])

(defn ^:private usage
  [opts-summary]
  (->> ["Redact structured data."
        ""
        "Usage: wernicke [OPTIONS] < infile > outfile"
        ""
        "Input is only read from stdin, output only written to stdout."
        ""
        "Options:"
        opts-summary]
       (str/join \newline)))

(defn ^:private error-msg
  [errors]
  (format
   "The following %s occurred while parsing your command:\n%s\n"
   (if (-> errors count (= 1)) "error" "errors")
   (str/join \newline errors)))

(defn ^:private validate-args
  [args]
  (let [{:keys [options arguments errors summary]} (cli/parse-opts args cli-opts)]
    (cond
      (or (:help options) (= :help (first arguments)))
      {:exit-message (usage summary) :ok true}

      errors
      {:exit-message (str (error-msg errors) "\n" (usage summary)) :ok false}

      :else
      {:opts options})))

(defn ^:private exit!
  "Prints the message and exits the process with the given code.

  This mostly exists so it can be stubbed out for testing, since it's annoying
  to test System/exit."
  [message code]
  (println message)
  (System/exit code))

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (let [{:keys [opts exit-message ok]} (validate-args args)
        {:keys [input-fn output-fn]} opts]
    (if exit-message
      (exit! exit-message (if ok 0 1))
      (-> *in* input-fn wc/redact output-fn))))
