(ns latacora.wernicke.cli-test
  (:require [latacora.wernicke.cli :as cli]
            [clojure.test :as t]
            [clojure.string :as str]
            [cheshire.core :as json]))

(defmacro with-fake-exit
  [& body]
  `(let [exit-calls# (atom [])]
     (with-redefs [cli/exit! (fn [& args#] (swap! exit-calls# conj args#))]
       (let [result# (do ~@body)]
         {::exit-calls @exit-calls#
          ::result result#}))))

(defn cli-test-harness
  [stdin args]
  (with-in-str stdin (with-fake-exit (with-out-str (apply cli/-main args)))))

(def help-lines
  ["Redact structured data."
   ""
   "Usage: wernicke [OPTIONS] < infile > outfile"
   ""
   "Input is only read from stdin, output only written to stdout."
   ""
   "Options:"
   "  -h, --help                 display help message"
   "  -v, --verbose              verbosity level"
   "  -i, --input FORMAT   json  input format (one of json, edn)"
   "  -o, --output FORMAT  json  output format (one of json, edn)"])

(def expected-help
  (str/join \newline help-lines))

(t/deftest cli-tests
  (t/is (= #::{:exit-calls [[expected-help 0]] :result ""}
           (cli-test-harness "{}" ["--help"])))
  (t/is (= #::{:exit-calls [[(->> help-lines
                                  (into ["The following error occurred while parsing your command:"
                                         "Unknown option: \"--nonsense\""
                                         ""])
                                  (str/join \newline))
                             1]]
               :result ""}
           (cli-test-harness "{}" ["--nonsense"])))

  (let [data {:a 1}
        json (json/generate-string data)
        edn (pr-str data)]
    (t/is (= #::{:exit-calls [] :result json}
             (cli-test-harness json []))
          "implicit json in, implicit json out")
    (t/is (= #::{:exit-calls [] :result json}
             (cli-test-harness json ["--input" "json"]))
          "explicit json in, implicit json out")
    (t/is (= #::{:exit-calls [] :result json}
             (cli-test-harness json ["--input=json"]))
          "explicit json in, implicit json out")
    (t/is (= #::{:exit-calls [] :result json}
             (cli-test-harness json ["--input" "json" "--output" "json"]))
          "explicit json in, explicit json out")

    (t/is (= #::{:exit-calls [] :result json}
             (cli-test-harness edn ["--input" "edn"])))))
