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
  ([x]
   (redact* x wc/default-opts))
  ([x opts]
   (#'wc/redact x (assoc opts ::wc/key zero-key))))

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
        ip-rule-config (update
                        @#'wc/default-opts
                        ::wc/rules
                        (fn [rules]
                          (filter #(= ::wp/ipv4-re (::wc/name %)) rules)))
        redacted (redact* orig ip-rule-config)]
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

(t/deftest base16-lenght-test
  (let [s "68656C6C6F20776F726C64"]
    (t/is
     (= (count s) (count (redact* s))))))

(comment

  ;rsa private key with header
  (let [s "LS0tLS1CRUdJTiBSU0EgUFJJVkFURSBLRVktLS0tLQpNSUlFcEFJQkFBS0NBUUVBdGQ4QXM4NXNPVWpqa2pWMTJ1ak1JWm1oeWVnWGtjbUdhVFdrMzE5dlFCMytjcEloCld1MG1Ca2U4UjI4alJ5bTlrTFFqMlJqYU8xQWRTeHNMeTRoUjJIeW5ZN2w2QlNiSVVyQWFtL2FDL2VWekptZzcKcWpWaWpQS1JUajdiZEc1ZFlOWllTRWlMOTh0LytYVnhvSmNYWE9FWTgzYzVXY0NueW9GdjU4TUc0VEdlSGkvMApjb1hLcGRHbEFxdFFVcWJwMnNHN1dDclhJR0pKZEJ2VURJUURRUTBJc242TUs0bktCQTEwdWNKbVYrb2s3REVQCmt5R2swM0tnQXgrVmllbjlFTHZvN1AwQU43NU5tMVc5RmlQNmdmb052VVhEQXBLRjdkdTFGVG40cjNwZUx6emoKNTB5NUdjaWZXWWZvUllpN09QaHhJNGNGWU9XbGVGbTFwSVM0UHdJREFRQUJBb0lCQVFDQmxldUNNa3FhWm56Lwo2R2VaR3RhWCtrZDAvWklOcG5IRzlSb01yb3N1UEREWW9aWnlteGJFMHNnc2ZkdTlFTmlwQ2pHZ3RqeUlsb1RJCnh2U1lpUUVJSjRsOVhPSzhXTzNUUFBjNHVXU01VN2pBWFBSbVNyTjFpa0JPYUNzbHdwMTJLa09zL1VQOXcxbmoKL1BLQllpYWJYeWZRRWRzalFFcE4xL3hNUG9IZ1lhNWJXSG01dHc3YUZuNmJuVVNtMVpQek1xdXZaRWtkWG9aeApjNWg1UDIwQnZjVnorT0prQ0xIM1NSUjZBRjdUWlltQkVzQkIwWHZWeXNPa3JJdmR1ZGNjVnFVRHJwanpVQmMzCkw4a3RXM0Z6RSt0ZVA3dnhpNngvbkZ1Rmg2a2lDRHlvTEJoUmxCSkkvYy9QemdUWXdXaEQvUlJ4a0x1ZXZ6SDcKVFU4SkZROUJBb0dCQU9JclFLd2lBSE53NHdubWlpbkdUdThJVzJrMzJMZ0k5MDBvWXUzdHk4akxHTDZxMUloRQpxalZNamxiSmhhZTU4bUFNeDFRcjhJdUhUUFNtd2VkTmpQQ2FWeXZqczVRYnJaeUNWVmp4MkJBVCt3ZDhwbDEwCk5CWFNGUVRNYmc2clZnZ0tJM3RIU0UxTlNkTzhrTGpJVFVpQUFqeG5uSndJRWdQSytsamdtR0VUQW9HQkFNM2MKQU5kLzF1bm43b090bGZHQVVIYjY0MmtOZ1h4SDdVK2dXM2h5dFdNY1lYVGVxblo1NmEza054VE1qZFZ5VGhsTwpxR1htQlI4NDVxNWozVmxGSmM0RXVicGtYRUdEVFRQQlNtdjIxWXlVMHpmNXhsU3A2ZlllK1J1NStocWxSTzRuCnJzbHV5TXZ6dERYT2lZTy9WZ1ZFVUVuTEd5ZEJiMUx3TEIrTVZSMmxBb0dBZEg3czcvMFBtR2JVT3p4SmZGME8KT1dkbmxsblN3bkN6MlVWdE43cmQxYzV2TDM3VXZHQUtBQ3d2d1JwS1F1dXZvYlBUVkZMUnN6ejg4YU9YaXluUgo1L2pIMys2SWlFaDljM2xhdHRiVGdPeVp4L0IzelBsVy9zcFlVMEZ0aXhiTDJKWklVbTZVR21VdUd1Y3M4RkVVCkpieng2ZVZBc01valpWcSsrdHF0QW9zQ2dZQjBLV0hjT0lvWVFVVG96dW5lZGE1eUJRNlArQXdLQ2poU0IwVzIKU053cnloY0FNS2wxNDBOR1daSHZUYUgzUU9IckMrU2dZMVNla3FndzNhOUlzV2tzd0tQaEZzS3NRU0F1UlRMdQppMEZqYTVOb2NheEZsLytxWHozb05HQjU2cXBqek1hbmFia3F4U0Q2ZjhvL0twZXFyeXF6Q1VZUU42OU8yTEc5Ck41M0w5UUtCZ1FDWmQwSzZSRmhoZEpXK0VoNy9hSWs4bThDaG80SW01dkZPRnJuOTllNEhLWUY1Qkpub1FwNHAKMVFUTE1zMkMzaFFYZEo0OUxUTHAweHI3N3pQeE5XVXBvTjRYQndxRFdMMHQwTVlrUlpGb0NBRzdKeTJQZ2Vndgp1T3VJcjZOSGZkZ0dCZ09UZXVjRyttUHRBRHNMWXVyRVF1VWxma2w1aFI3TGd3RiszcThiSFE9PQotLS0tLUVORCBSU0EgUFJJVkFURSBLRVktLS0tLQ=="]
    (println (count s))
    (println (count (redact* s))))

  ;no newline
  (let [s "TUlJRXBBSUJBQUtDQVFFQXRkOEFzODVzT1VqamtqVjEydWpNSVptaHllZ1hrY21HYVRXazMxOXZRQjMrY3BJaApXdTBtQmtlOFIyOGpSeW05a0xRajJSamFPMUFkU3hzTHk0aFIySHluWTdsNkJTYklVckFhbS9hQy9lVnpKbWc3CnFqVmlqUEtSVGo3YmRHNWRZTlpZU0VpTDk4dC8rWFZ4b0pjWFhPRVk4M2M1V2NDbnlvRnY1OE1HNFRHZUhpLzAKY29YS3BkR2xBcXRRVXFicDJzRzdXQ3JYSUdKSmRCdlVESVFEUVEwSXNuNk1LNG5LQkExMHVjSm1WK29rN0RFUApreUdrMDNLZ0F4K1ZpZW45RUx2bzdQMEFONzVObTFXOUZpUDZnZm9OdlVYREFwS0Y3ZHUxRlRuNHIzcGVMenpqCjUweTVHY2lmV1lmb1JZaTdPUGh4STRjRllPV2xlRm0xcElTNFB3SURBUUFCQW9JQkFRQ0JsZXVDTWtxYVpuei8KNkdlWkd0YVgra2QwL1pJTnBuSEc5Um9Ncm9zdVBERFlvWlp5bXhiRTBzZ3NmZHU5RU5pcENqR2d0anlJbG9USQp4dlNZaVFFSUo0bDlYT0s4V08zVFBQYzR1V1NNVTdqQVhQUm1Tck4xaWtCT2FDc2x3cDEyS2tPcy9VUDl3MW5qCi9QS0JZaWFiWHlmUUVkc2pRRXBOMS94TVBvSGdZYTViV0htNXR3N2FGbjZiblVTbTFaUHpNcXV2WkVrZFhvWngKYzVoNVAyMEJ2Y1Z6K09Ka0NMSDNTUlI2QUY3VFpZbUJFc0JCMFh2VnlzT2tySXZkdWRjY1ZxVURycGp6VUJjMwpMOGt0VzNGekUrdGVQN3Z4aTZ4L25GdUZoNmtpQ0R5b0xCaFJsQkpJL2MvUHpnVFl3V2hEL1JSeGtMdWV2ekg3ClRVOEpGUTlCQW9HQkFPSXJRS3dpQUhOdzR3bm1paW5HVHU4SVcyazMyTGdJOTAwb1l1M3R5OGpMR0w2cTFJaEUKcWpWTWpsYkpoYWU1OG1BTXgxUXI4SXVIVFBTbXdlZE5qUENhVnl2anM1UWJyWnlDVlZqeDJCQVQrd2Q4cGwxMApOQlhTRlFUTWJnNnJWZ2dLSTN0SFNFMU5TZE84a0xqSVRVaUFBanhubkp3SUVnUEsrbGpnbUdFVEFvR0JBTTNjCkFOZC8xdW5uN29PdGxmR0FVSGI2NDJrTmdYeEg3VStnVzNoeXRXTWNZWFRlcW5aNTZhM2tOeFRNamRWeVRobE8KcUdYbUJSODQ1cTVqM1ZsRkpjNEV1YnBrWEVHRFRUUEJTbXYyMVl5VTB6ZjV4bFNwNmZZZStSdTUraHFsUk80bgpyc2x1eU12enREWE9pWU8vVmdWRVVFbkxHeWRCYjFMd0xCK01WUjJsQW9HQWRIN3M3LzBQbUdiVU96eEpmRjBPCk9XZG5sbG5Td25DejJVVnRON3JkMWM1dkwzN1V2R0FLQUN3dndScEtRdXV2b2JQVFZGTFJzeno4OGFPWGl5blIKNS9qSDMrNklpRWg5YzNsYXR0YlRnT3laeC9CM3pQbFcvc3BZVTBGdGl4YkwySlpJVW02VUdtVXVHdWNzOEZFVQpKYnp4NmVWQXNNb2paVnErK3RxdEFvc0NnWUIwS1dIY09Jb1lRVVRvenVuZWRhNXlCUTZQK0F3S0NqaFNCMFcyClNOd3J5aGNBTUtsMTQwTkdXWkh2VGFIM1FPSHJDK1NnWTFTZWtxZ3czYTlJc1drc3dLUGhGc0tzUVNBdVJUTHUKaTBGamE1Tm9jYXhGbC8rcVh6M29OR0I1NnFwanpNYW5hYmtxeFNENmY4by9LcGVxcnlxekNVWVFONjlPMkxHOQpONTNMOVFLQmdRQ1pkMEs2UkZoaGRKVytFaDcvYUlrOG04Q2hvNEltNXZGT0Zybjk5ZTRIS1lGNUJKbm9RcDRwCjFRVExNczJDM2hRWGRKNDlMVExwMHhyNzd6UHhOV1Vwb040WEJ3cURXTDB0ME1Za1JaRm9DQUc3SnkyUGdlZ3YKdU91SXI2TkhmZGdHQmdPVGV1Y0crbVB0QURzTFl1ckVRdVVsZmtsNWhSN0xnd0YrM3E4YkhRPT0="]
    (println (count s))
    (println (count (redact* s))))

  ;rsa private key with q \n
  (let [s "cQpNSUlFcEFJQkFBS0NBUUVBdGQ4QXM4NXNPVWpqa2pWMTJ1ak1JWm1oeWVnWGtjbUdhVFdrMzE5dlFCMytjcEloCld1MG1Ca2U4UjI4alJ5bTlrTFFqMlJqYU8xQWRTeHNMeTRoUjJIeW5ZN2w2QlNiSVVyQWFtL2FDL2VWekptZzcKcWpWaWpQS1JUajdiZEc1ZFlOWllTRWlMOTh0LytYVnhvSmNYWE9FWTgzYzVXY0NueW9GdjU4TUc0VEdlSGkvMApjb1hLcGRHbEFxdFFVcWJwMnNHN1dDclhJR0pKZEJ2VURJUURRUTBJc242TUs0bktCQTEwdWNKbVYrb2s3REVQCmt5R2swM0tnQXgrVmllbjlFTHZvN1AwQU43NU5tMVc5RmlQNmdmb052VVhEQXBLRjdkdTFGVG40cjNwZUx6emoKNTB5NUdjaWZXWWZvUllpN09QaHhJNGNGWU9XbGVGbTFwSVM0UHdJREFRQUJBb0lCQVFDQmxldUNNa3FhWm56Lwo2R2VaR3RhWCtrZDAvWklOcG5IRzlSb01yb3N1UEREWW9aWnlteGJFMHNnc2ZkdTlFTmlwQ2pHZ3RqeUlsb1RJCnh2U1lpUUVJSjRsOVhPSzhXTzNUUFBjNHVXU01VN2pBWFBSbVNyTjFpa0JPYUNzbHdwMTJLa09zL1VQOXcxbmoKL1BLQllpYWJYeWZRRWRzalFFcE4xL3hNUG9IZ1lhNWJXSG01dHc3YUZuNmJuVVNtMVpQek1xdXZaRWtkWG9aeApjNWg1UDIwQnZjVnorT0prQ0xIM1NSUjZBRjdUWlltQkVzQkIwWHZWeXNPa3JJdmR1ZGNjVnFVRHJwanpVQmMzCkw4a3RXM0Z6RSt0ZVA3dnhpNngvbkZ1Rmg2a2lDRHlvTEJoUmxCSkkvYy9QemdUWXdXaEQvUlJ4a0x1ZXZ6SDcKVFU4SkZROUJBb0dCQU9JclFLd2lBSE53NHdubWlpbkdUdThJVzJrMzJMZ0k5MDBvWXUzdHk4akxHTDZxMUloRQpxalZNamxiSmhhZTU4bUFNeDFRcjhJdUhUUFNtd2VkTmpQQ2FWeXZqczVRYnJaeUNWVmp4MkJBVCt3ZDhwbDEwCk5CWFNGUVRNYmc2clZnZ0tJM3RIU0UxTlNkTzhrTGpJVFVpQUFqeG5uSndJRWdQSytsamdtR0VUQW9HQkFNM2MKQU5kLzF1bm43b090bGZHQVVIYjY0MmtOZ1h4SDdVK2dXM2h5dFdNY1lYVGVxblo1NmEza054VE1qZFZ5VGhsTwpxR1htQlI4NDVxNWozVmxGSmM0RXVicGtYRUdEVFRQQlNtdjIxWXlVMHpmNXhsU3A2ZlllK1J1NStocWxSTzRuCnJzbHV5TXZ6dERYT2lZTy9WZ1ZFVUVuTEd5ZEJiMUx3TEIrTVZSMmxBb0dBZEg3czcvMFBtR2JVT3p4SmZGME8KT1dkbmxsblN3bkN6MlVWdE43cmQxYzV2TDM3VXZHQUtBQ3d2d1JwS1F1dXZvYlBUVkZMUnN6ejg4YU9YaXluUgo1L2pIMys2SWlFaDljM2xhdHRiVGdPeVp4L0IzelBsVy9zcFlVMEZ0aXhiTDJKWklVbTZVR21VdUd1Y3M4RkVVCkpieng2ZVZBc01valpWcSsrdHF0QW9zQ2dZQjBLV0hjT0lvWVFVVG96dW5lZGE1eUJRNlArQXdLQ2poU0IwVzIKU053cnloY0FNS2wxNDBOR1daSHZUYUgzUU9IckMrU2dZMVNla3FndzNhOUlzV2tzd0tQaEZzS3NRU0F1UlRMdQppMEZqYTVOb2NheEZsLytxWHozb05HQjU2cXBqek1hbmFia3F4U0Q2ZjhvL0twZXFyeXF6Q1VZUU42OU8yTEc5Ck41M0w5UUtCZ1FDWmQwSzZSRmhoZEpXK0VoNy9hSWs4bThDaG80SW01dkZPRnJuOTllNEhLWUY1Qkpub1FwNHAKMVFUTE1zMkMzaFFYZEo0OUxUTHAweHI3N3pQeE5XVXBvTjRYQndxRFdMMHQwTVlrUlpGb0NBRzdKeTJQZ2Vndgp1T3VJcjZOSGZkZ0dCZ09UZXVjRyttUHRBRHNMWXVyRVF1VWxma2w1aFI3TGd3RiszcThiSFE9PQ=="]
    (println (count s))
    (println (count (redact* s))))
  )
