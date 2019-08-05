(ns latacora.wernicke.core
  "Tools for recording, redacting and replaying responses.

  If a value occurs twice in a document it will redact to the same value. Where
  possible, values will be redacted to similar-looking values, e.g. MAC
  addresses map to MAC addresses. Long values we don't recognize should be
  redacted to a string of the same length as a failsafe. Finally, only leaf
  values will be redacted; the key `PrivateIpAddresses` should not be redacted
  even though its value should be."
  (:require [cheshire.core :as json]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.random :as rand]
            [clojure.test.check.rose-tree :as rose]
            [com.gfredericks.test.chuck.generators :as cgen]
            [com.gfredericks.test.chuck.regexes :as cre]
            [com.rpl.specter :as sr]
            [eidolon.core :refer [TREE-LEAVES]]
            [latacora.wernicke.patterns :as p]
            [taoensso.nippy :as nippy]
            [clojure.spec.alpha :as s])
  (:import com.zackehh.siphash.SipHash
           java.security.SecureRandom)
  (:gen-class))

(defmulti compile-rule ::type)
(defmethod compile-rule ::regex
  [{::keys [pattern] :as rule}]
  (let [parsed (-> pattern str cre/parse)]
    (assoc
     rule
     ::matcher-fn (partial re-matches pattern)
     ;; Unlike [[cgen/string-from-regex]], we're willing to temporarily ignore
     ;; unsupported features like named groups. That's _generally_ a bug, and we
     ;; should check for them, but that's blocked on upstream test.chuck work.
     ::generator-fn (fn [match] (cre/analyzed->generator parsed)))))

(defn regex-rule
  "Given a regex and optional ops, produce a compiled rule."
  ([pattern]
   (regex-rule pattern nil))
  ([pattern opts]
   (compile-rule (assoc opts ::type ::regex ::pattern pattern))))

(def pattern? (partial instance? java.util.regex.Pattern))
(s/def ::pattern pattern?)

(def matcher? some?)
(s/def ::matcher-fn matcher?)
(def generator? some?)
(s/def ::generator-fn generator?)

(defn ^:private key!
  "Generate a new SipHash key."
  []
  (let [k (byte-array 16)]
    (.nextBytes (SecureRandom.) k)
    k))

(defn ^:private siphash
  "Given a key and a value (both bytes), hash to a long."
  [key v]
  (-> (SipHash. key) (.hash v) (.get)))

(defn ^:private compute-dual
  [key val re]
  (let [rnd (->> val nippy/freeze (siphash key) rand/make-random)]
    (-> re cgen/string-from-regex (gen/call-gen rnd 1) rose/root)))

(defn ^:private compute-dual2
  [hash generator val]
  (let [rnd (->> val hash rand/make-random)]
    (-> generator (gen/call-gen rnd 1) rose/root)))

(def ^:private redactable-regexes
  [p/timestamp-re
   p/mac-colon-re
   p/mac-dash-re
   p/ipv4-re
   p/long-decimal-re
   p/internal-ec2-hostname-re
   p/arn-re])

(def ^:private default-rules
  (map regex-rule redactable-regexes))

(defn ^:private redact-1
  "Redacts a single item, assuming it is redactable."
  [key val]
  (let [compute-dual (partial compute-dual key val)
        hash (let [sh (SipHash. key)]
               #(->> % nippy/freeze (.hash sh) (.get)))]
    (if (string? val)
      (or (when-let [[_ pfx _] (re-matches p/aws-resource-id-re val)]
            (str pfx (compute-dual p/aws-resource-suffix-re)))
          (first
           (for [{::keys [generator-fn matcher-fn]} default-rules
                 :let [match (matcher-fn val)]
                 :when match
                 :let [gen (generator-fn match)]]
             (compute-dual2 hash gen val)))
          (when (>= (count val) 12)
            (compute-dual (re-pattern (str "\\w{" (count val) "}"))))
          val)
      val)))

(defn redact
  "Attempt to automatically redact the AWS response."
  ([x]
   (redact x (key!)))
  ([x k]
   (sr/transform [TREE-LEAVES] (partial redact-1 k) x)))

(defn redact-stdio!
  "Redacts the JSON value read from stdin and writes it to stdout."
  []
  (-> *in* json/decode-stream redact (json/encode-stream *out*)))

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (redact-stdio!))
