(ns latacora.wernicke.core-test
  (:require [clojure.data :refer [diff]]
            [latacora.wernicke.core :as wc]
            [latacora.wernicke.patterns :as wp]
            [latacora.wernicke.patterns-test :refer [arns]]
            [clojure.test :as t]
            [clojure.string :as str]))

(t/deftest redact-test
  (t/are [x] (= x (#'wc/redact x))
    {}
    [])

  (let [orig-vpc "vpc-12345"
        {:keys [a b]} (#'wc/redact {:a orig-vpc :b orig-vpc})]
    (t/is (= a b))
    (t/is (not= a orig-vpc))))

(def nested
  {:a {:b {:c ["vpc-12345"]
           :d "Constant"}}
   :h "LongStringToBeRedacted"
   :x [:y :z]})

(t/deftest nested-redact-test
  (let [redacted (#'wc/redact nested)
        [only-in-orig _ in-both] (diff nested redacted)]
    (t/is (= {:a {:b {:d "Constant"}}
              :x [:y :z]}
             in-both))
    (t/is (= {:a {:b {:c ["vpc-12345"]}}
              :h "LongStringToBeRedacted"}
             only-in-orig))))

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

(t/deftest regex-compile-test
  (let [rule (wc/compile-rule {::wc/type ::wc/regex ::pattern wp/ipv4-re})]
    (t/is (some? rule))))

(t/deftest regex-with-fixed-group-test
  (let [{:keys [vpc-id sg-id acl-id]} (wc/redact {:vpc-id "vpc-12345"
                                                  :sg-id "sg-12345"
                                                  :acl-id "acl-12345"})]
    (t/is (str/starts-with? vpc-id "vpc-"))
    (t/is (str/starts-with? sg-id "sg-"))
    (t/is (str/starts-with? acl-id "acl-"))))
