(ns latacora.wernicke.core-test
  (:require [clojure.data :refer [diff]]
            [latacora.wernicke.core :as wc]
            [latacora.wernicke.patterns :as wp]
            [latacora.wernicke.patterns-test :refer [arns]]
            [clojure.test :as t]
            [clojure.string :as str]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :as tct]
            [com.gfredericks.test.chuck.generators :as gen']
            [com.gfredericks.test.chuck.regexes :as cre]
            [com.gfredericks.test.chuck.clojure-test :as tct']))

(t/deftest keygen-test
  (t/is (-> (#'wc/key!) count (= 16))))

(def zero-key (byte-array 16))

(defn redact*
  [x]
  (#'wc/redact x (assoc wc/default-config ::wc/key zero-key)))

(t/deftest redact-test
  (t/are [x] (= x (redact* x))
    {}
    [])

  (let [orig-vpc "vpc-12345"
        {:keys [a b]} (redact* {:a orig-vpc :b orig-vpc})]
    (t/is (= a b))
    (t/is (not= a orig-vpc))))

(def nested
  {:a {:b {:c ["vpc-12345"]
           :d "Constant"}}
   :h "LongStringToBeRedacted"
   :x [:y :z]})

(t/deftest nested-redact-test
  (let [redacted (redact* nested)
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
          :let [after (redact* before)]]
    (t/is (re-matches re before))
    (t/is (re-matches re after))
    (t/is (not= before after))))

(t/deftest regex-compile-test
  (let [rule (wc/compile-rule {::wc/type ::wc/regex ::pattern wp/ipv4-re})]
    (t/is (some? rule))))

(t/deftest regex-with-fixed-group-test
  (let [{:keys [vpc-id sg-id acl-id]} (wc/redact! {:vpc-id "vpc-12345"
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

(tct/defspec fixed-val-generators-work-as-expected
  (prop/for-all
   [v (cre/analyzed->generator fixed-alt-pattern)]
   (= v "x")))

(def one-to-inf-rep-pattern
  "(?<a>x{1,})")

(def fixed-one-to-inf-rep-pattern
  (-> one-to-inf-rep-pattern cre/parse (#'wc/set-group-length "a" 1)))

(t/deftest set-group-length-test
  (t/is (= {:type :alternation
            :elements
            [{:type :concatenation
              :elements
              [{:type :group
                :elements
                [{:type :alternation
                  :elements
                  [{:type :concatenation
                    :elements
                    [{:type :repetition
                      :elements
                      [{:type :character
                        :character \x
                        :elements nil}]
                      :bounds [1 1]}]}]}]
                :flag [:GroupFlags
                       [:NamedCapturingGroup
                        [:GroupName "a"]]]}]}]}
           fixed-one-to-inf-rep-pattern)))

(tct/defspec fixed-len-generators-work-as-expected
  (prop/for-all
   [v (cre/analyzed->generator fixed-one-to-inf-rep-pattern)]
   (= (count v) 1)))

(tct/defspec regex-redaction-with-groups-preserves-regex-match
  (tct'/for-all
   [pattern (->> @#'wc/default-config
                 ::wc/rules
                 (filter ::wc/group-config)
                 (map ::wc/pattern)
                 gen/elements)
    orig (gen'/string-from-regex pattern)
    :let [redacted (wc/redact! orig)]]
   (t/is (re-matches pattern redacted))))

(defn re-group
  [re s group]
  (let [m (re-matcher re s)]
    (when (.matches m)
      (.group m group))))

(defn count-occurrences
  [needle haystack]
  (-> haystack (str/split (re-pattern needle)) count dec))

(tct/defspec no-duplicated-kept-groups
  (let [default-rules (::wc/rules @#'wc/default-config)
        kept-groups (->>
                     (for [{::wc/keys [pattern group-config]} default-rules
                           :let [kept (for [[group-name config] group-config
                                            :let [behavior (::wc/behavior config)]
                                            :when (= behavior ::wc/keep)]
                                        group-name)]
                           :when (seq kept)]
                       [pattern kept])
                     (into {}))]
    (tct'/for-all
     [pattern (-> kept-groups keys gen/elements)
      fixed-group-name (-> pattern kept-groups gen/elements)
      orig (-> pattern gen'/string-from-regex)
      :let [redacted (redact* orig)
            redacted-fixed-val (re-group pattern redacted fixed-group-name)]]
     (t/is (= 1 (count-occurrences redacted-fixed-val redacted)))
     (t/is (= (re-group pattern orig fixed-group-name) redacted-fixed-val)))))

(t/deftest aws-iam-unique-id-tests
  (let [before {:a (str "AKIA" (str/join (repeat 16 "X")))
                :b (str "AROA" (str/join (repeat 16 "Y")))}
        after (wc/redact! before)]
    (t/is (-> after :a (str/starts-with? "AKIA")))
    (t/is (-> after :b (str/starts-with? "AROA")))
    (t/is (-> after :a count (= 20)))
    (t/is (-> after :b count (= 20)))
    (t/is (not= before after))))

(t/deftest redact-sensitive-substring-tests
  (t/testing "single sensitive substring"
    (let [orig-vpc "vpc-12345"
          redacted-vpc (redact* orig-vpc)
          template (partial format "The VPC is %s")]
      (t/is (= (template redacted-vpc)
               (redact* (template orig-vpc))))))

  (t/testing "multiple different sensitive substrings"
    (let [orig-vpc "vpc-12345"
          redacted-vpc (redact* orig-vpc)
          orig-sg "sg-12345"
          redacted-sg (redact* orig-sg)
          template (partial format "The VPC is %s and the SG is %s")]
      (t/is (= (template redacted-vpc redacted-sg)
               (redact* (template orig-vpc orig-sg))))))

  (t/testing "multiple identical sensitive substrings"
    (let [orig-vpc "vpc-12345"
          redacted-vpc (redact* orig-vpc)
          template (fn [s] (format "The VPC is %s. I repeat, the VPC is %s." s s))]
      (t/is (= (template redacted-vpc)
               (redact* (template orig-vpc)))))))

(t/deftest redact-within-keys-tests
  (t/testing "sensitive key, non sensitive value"
    (let [orig-vpc "vpc-12345"
          redacted-vpc (redact* orig-vpc)
          template (fn [vpc-id] {"instance_counts" {vpc-id 1}})]
      (t/is (= (template redacted-vpc)
               (redact* (template orig-vpc))))))

  (t/testing "sensitive key, sensitive value"
    (let [orig-vpc "vpc-12345"
          orig-ec2 "i-abcdef"
          redacted-vpc (redact* orig-vpc)
          redacted-ec2 (redact* orig-ec2)
          template (fn [vpc-id ec2-id] {"vpc_map" {ec2-id vpc-id}})]
      (t/is (= (template redacted-vpc redacted-ec2)
               (redact* (template orig-vpc orig-ec2)))))))

(t/deftest redaction-with-opts
  (let [orig {:vpc "vpc-12345"
              :ip "10.0.0.1"}
        ip-rule-config (update
                        @#'wc/default-opts
                        ::wc/rules
                        (fn [rules]
                          (filter #(= ::wp/ipv4-re (::wc/name %)) rules)))
        redacted (#'wc/redact! orig ip-rule-config)]
    (t/testing "explicit rules"
      (t/is (= (:vpc orig) (:vpc redacted)))
      (t/is (not= (:ip orig) (:ip redacted))))))
