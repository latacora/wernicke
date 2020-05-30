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
            [com.gfredericks.test.chuck.clojure-test :as tct']
            [multiformats.base :as mbase]
            [alphabase.bytes :as b]))

(t/deftest keygen-test
  (t/is (-> (#'wc/key!) count (= 16))))

(def zero-key (byte-array 16))

(defn redact*
  ([x]
   (redact* x wc/default-opts))
  ([x opts]
   (#'wc/redact x (assoc opts ::wc/key zero-key))))

(defn just-one-pattern
  "Creates opts that only match a single pattern."
  [pattern]
  (update
   @#'wc/default-opts
   ::wc/rules
   (fn [rules]
     (filter #(= pattern (::wc/pattern %)) rules))))

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
   :h "VeryLongStringToBeRedacted"
   :x [:y :z]})

(t/deftest nested-redact-test
  (let [redacted (redact* nested)
        [only-in-orig _ in-both] (diff nested redacted)]
    (t/is (= {:a {:b {:d "Constant"}}
              :x [:y :z]}
             in-both))
    (t/is (= {:a {:b {:c ["vpc-12345"]}}
              :h "VeryLongStringToBeRedacted"}
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
  (let [rule (wc/compile-rule {::wc/type ::wc/regex ::wc/pattern wp/ipv4-re})]
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
   [pattern (->> @#'wc/default-opts
                 ::wc/rules
                 (filter ::wc/group-config)
                 (map ::wc/pattern)
                 gen/elements)
    orig (gen'/string-from-regex pattern)
    :let [redacted (wc/redact! orig (just-one-pattern pattern))]]
   (t/is (re-matches pattern redacted))))

(defn re-group
  [re s group]
  (let [m (re-matcher re s)]
    (when (.matches m)
      (.group m group))))

(tct/defspec no-duplicated-kept-groups
  (let [default-rules (::wc/rules @#'wc/default-opts)
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
      :let [redacted (redact* orig (just-one-pattern pattern))
            redacted-fixed-val (re-group pattern redacted fixed-group-name)
            orig-fixed-val (re-group pattern orig fixed-group-name)]]
     (t/is (= orig-fixed-val redacted-fixed-val)))))

(t/deftest aws-iam-unique-id-tests
  (let [before {:a (str "AKIA" (str/join (repeat 16 "X")))
                :b (str "AROA" (str/join (repeat 16 "Y")))}
        after (wc/redact! before)]
    (t/is (-> after :a (str/starts-with? "AKIA")))
    (t/is (-> after :b (str/starts-with? "AROA")))
    (t/is (-> after :a count (= 20)))
    (t/is (-> after :b count (= 20)))
    (t/is (not= before after))))

(def orig-vpc "vpc-12345")
(def redacted-vpc (redact* orig-vpc))

(def orig-sg "sg-12345")
(def redacted-sg (redact* orig-sg))

(def orig-ec2 "i-abcdef")
(def redacted-ec2 (redact* orig-ec2))

(t/deftest redact-sensitive-substring-tests
  (t/testing "single sensitive substring"
    (let [template (partial format "The VPC is %s")]
      (t/is (= (template redacted-vpc)
               (redact* (template orig-vpc))))))

  (t/testing "multiple different sensitive substrings"
    (let [template (partial format "The VPC is %s and the SG is %s")]
      (t/is (= (template redacted-vpc redacted-sg)
               (redact* (template orig-vpc orig-sg))))))

  (t/testing "multiple identical sensitive substrings"
    (let [template (fn [s] (format "The VPC is %s. I repeat, the VPC is %s." s s))]
      (t/is (= (template redacted-vpc)
               (redact* (template orig-vpc)))))))

(t/deftest redact-within-keys-tests
  (t/testing "sensitive key, non sensitive value"
    (let [template (fn [vpc-id] {"instance_counts" {vpc-id 1}})]
      (t/is (= (template redacted-vpc)
               (redact* (template orig-vpc))))))

  (t/testing "sensitive key, sensitive value"
    (let [template (fn [vpc-id ec2-id] {"vpc_map" {ec2-id vpc-id}})]
      (t/is (= (template redacted-vpc redacted-ec2)
               (redact* (template orig-vpc orig-ec2)))))))

(t/deftest redaction-with-opts-tests
  (let [orig {:vpc "vpc-12345" :ip "10.0.0.1"}
        redacted (redact* orig (just-one-pattern wp/ipv4-re))]
    (t/testing "explicit rule just for ipv4 addresses"
      (t/is (= (:vpc orig) (:vpc redacted)))
      (t/is (not= (:ip orig) (:ip redacted)))))

  (let [orig {:vpc "vpc-12345" :ip "10.0.0.1"}
        redacted (redact*
                  orig
                  (wc/process-opts
                   {:disabled-rules
                    [:latacora.wernicke.patterns/ipv4-re]}))]
    (t/testing "removing a rule by name"
      (t/is (not= (:vpc orig) (:vpc redacted)))
      (t/is (= (:ip orig) (:ip redacted)))))

  (let [orig {:lyric "Cooking MCs like a pound of bacon" :sg orig-sg}
        foods ["bacon" "baloney" "pepperoni" "salami" "broken glass"]
        config (wc/process-opts
                {:extra-rules
                 [{:name :foods
                   :type :regex
                   :pattern (->> foods
                                 (str/join "|")
                                 (format "(%s)")
                                 (re-pattern))}]})
        ;; we just get lucky that this maps to not-bacon with an all-zero key:
        ;; the number of values this regex can match is clearly limited.
        redacted-a (redact* orig)
        redacted-b (redact* orig config)]
    (t/testing "explicit extra rules"
      (t/is (= (:lyric orig) (:lyric redacted-a))
            "not redacted by default")
      (t/is (not= (:lyric orig) (:lyric redacted-b))
            "redacted with the extra rule")
      (t/testing "extra rules do not break default rules"
        (t/is (not= (:sg orig) (:sg redacted-a)))
        (t/is (not= (:sg orig) (:sg redacted-b))))))

  (let [orig {:lyric "Cooking MCs like a pound of bacon"}
        config (wc/process-opts
                {:extra-rules
                 [{:name ::kosherify-foods
                   :type :regex
                   :pattern #"(?<food>bacon)"
                   :group-config {"food" {:behavior :replace-with
                                          :replacement "brisket"}}}]})
        redacted (redact* orig config)]
    (t/testing "explicit extra rules with replacement"
      (t/is (= "Cooking MCs like a pound of brisket" (:lyric redacted))))))

(tct/defspec base16-re-properties-test
  (tct'/for-all
   [s (gen/fmap #(mbase/format :base32pad (b/random-bytes %)) (gen/choose 32 128))]
   (t/is (= (count s) (count (redact* s))))
   (t/is (not= s (redact* s)))))

(tct/defspec base32-re-properties-test
  (tct'/for-all
   [s (gen/fmap #(mbase/format :BASE32PAD (b/random-bytes %)) (gen/choose 32 128))]
   (t/is (= (count s) (count (redact* s))))
   (t/is (not= s (redact* s)))
   (t/is (= (count (re-find #"=+" s)) (count (re-find #"=+" (redact* s)))))))

(tct/defspec base64-re-properties-test
  (tct'/for-all
   [s (gen/fmap #(mbase/format :base64pad (b/random-bytes %)) (gen/choose 32 128))]
   (t/is (= (count s) (count (redact* s))))
   (t/is (not= s (redact* s)))
   (t/is (= (count (re-find #"=+" s)) (count (re-find #"=+" (redact* s)))))))
