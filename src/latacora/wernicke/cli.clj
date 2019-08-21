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
        message (str format-name " format")
        formats-error (->> (keys impls)
                           (map name)
                           (str/join ", ")
                           (str "format must be one of "))]
    [short long message
     :id (keyword (str format-name "-fn"))
     :default (impls :json)
     :parse-fn (comp impls keyword str/lower-case)
     :validate-fn [some? formats-error]]))

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
  (str "The following errors occurred while parsing your command:\n\n"
       (str/join \newline errors)))

(defn ^:private validate-args
  [args]
  (let [{:keys [options arguments errors summary]} (cli/parse-opts args cli-opts)]
    (cond
      (or (:help options) (= :help (first arguments)))
      {:exit-message (usage summary) :ok true}

      errors
      {:exit-message (error-msg errors) :ok false}

      :else
      {:opts options})))

(defn ^:private exit!
  [message code]
  (println message)
  (System/exit code))

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (let [{:keys [opts exit-message ok]} (validate-args args)
        {:keys [input-fn output-fn]} opts]
    (when exit-message (exit! exit-message (if ok 0 1)))
    (-> *in* input-fn wc/redact output-fn)))
