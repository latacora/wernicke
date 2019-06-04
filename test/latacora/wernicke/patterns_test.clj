(ns latacora.wernicke.patterns-test
  (:require [latacora.wernicke.patterns :as wp]
            [clojure.test :as t]
            [clojure.java.io :as io]))

(def arns
  "Examples of ARNs.

  See http://docs.aws.amazon.com/general/latest/gr/aws-arns-and-namespaces.html#arns-syntax"
  (->> "valid-arns" io/resource io/reader line-seq))

(t/deftest arn-re-tests
  (doseq [arn arns]
    (t/is (re-matches wp/arn-re arn))))
