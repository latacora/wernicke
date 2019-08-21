(ns latacora.wernicke.core-test
  (:require [clojure.data :refer [diff]]
            [latacora.wernicke.core :as wc]
            [latacora.wernicke.patterns :as wp]
            [latacora.wernicke.patterns-test :refer [arns]]
            [clojure.test :as t]
            [clojure.string :as str]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :as ct]
            [com.gfredericks.test.chuck.regexes :as cre]))

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

(def alt-pattern
  "(?<a>(a|b|c))")

(def fixed-alt-pattern
  (-> alt-pattern cre/parse (#'wc/set-group-value "a" "x")))

(t/deftest fixed-alt-pattern-tests
  (t/is (=  {:type :alternation
             :elements [{:type :concatenation
                         :elements [{:type :character
                                     :character "x"
                                     :elements nil}]}]}
            fixed-alt-pattern)))

(ct/defspec fixed-val-generators-work-as-expected
  (prop/for-all
   [v (cre/analyzed->generator fixed-alt-pattern)]
   (= v "x")))

(def one-to-inf-rep-pattern
  "(?<a>x{1,})")

(def fixed-one-to-inf-rep-pattern
  (-> one-to-inf-rep-pattern cre/parse (#'wc/set-group-length "a" 1)))

(t/deftest set-group-length-test
  (t/is (= {:type :alternation
            :elements [{:type :concatenation
                        :elements [{:type :group
                                    :elements [{:type :alternation
                                                :elements [{:type :concatenation
                                                            :elements [{:type :repetition
                                                                        :elements [{:type :character
                                                                                    :character \x
                                                                                    :elements nil}]
                                                                        :bounds [1 1]}]}]}]
                                    :flag [:GroupFlags [:NamedCapturingGroup [:GroupName "a"]]]}]}]}
           fixed-one-to-inf-rep-pattern)))

(ct/defspec fixed-len-generators-work-as-expected
  (prop/for-all
   [v (cre/analyzed->generator fixed-one-to-inf-rep-pattern)]
   (= (count v) 1)))

(t/deftest aws-iam-unique-id-tests
  (let [before {:a (str "AKIA" (str/join (repeat 16 "X")))
                :b (str "AROA" (str/join (repeat 16 "Y")))}
        after (wc/redact before)]
    (t/is (-> after :a (str/starts-with? "AKIA")))
    (t/is (-> after :b (str/starts-with? "AROA")))
    (t/is (-> after :a count (= 20)))
    (t/is (-> after :b count (= 20)))
    (t/is (not= before after))))
