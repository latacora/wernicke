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

(t/deftest re-test
  (doseq [[before re] (concat
                       [["2017-01-01T12:34:56.000Z"
                         wp/timestamp-re]

                        ["01:23:45:67:89:ab"
                         wp/mac-re]

                        ["10.0.0.1"
                         wp/ipv4-re]

                        ["123456789" ;; ec2 requester-id, owner-id...
                         wp/long-decimal-re]

                        ["ip-10-0-0-1.ec2.internal"
                         wp/internal-ec2-hostname-re]]
                       (for [arn arns]
                         [arn wp/arn-re]))
          :let [after (#'wc/redact before)]]
    (t/is (re-matches re before))
    (t/is (re-matches re after))
    (t/is (not= before after))))
