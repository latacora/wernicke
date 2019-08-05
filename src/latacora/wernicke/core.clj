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
            [eidolon.core :as ec :refer [TREE-LEAVES]]
            [latacora.wernicke.patterns :as p]
            [taoensso.nippy :as nippy]
            [clojure.spec.alpha :as s])
  (:import com.zackehh.siphash.SipHash
           java.security.SecureRandom)
  (:gen-class))

(def RE-PARSE-ELEMS
  "A navigator for all the parsed elements in a test.chuck regex parse tree.

  Stops at each regex part and recursively navigates down into every element."
  (sr/recursive-path [] p [(sr/stay-then-continue :elements sr/ALL p)]))

(defn replace-group-with-constant
  "Given a test.chuck regex parse tree, find the group with the given name, and
  return a new parse tree with the group replaced with the constant string
  value."
  [parsed group-name constant]
  (sr/setval
   [RE-PARSE-ELEMS
    (comp #{:group} :type)
    (comp #{[:GroupFlags [:NamedCapturingGroup [:GroupName group-name]]]} :flag)]
   {:type :character :character constant}
   parsed))

(cre/parse "(?<a>abc)(?<b>def)")

(s/def ::group-behavior #{::keep ::keep-length})
(s/def ::group-config (s/map-of string? ::group-behavior))

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
  [hash val]
  (first
   (for [{::keys [generator-fn matcher-fn]} default-rules
         :let [gen (some-> val matcher-fn generator-fn)
               rnd (-> val hash rand/make-random)]]
     (-> gen (gen/call-gen rnd 1) rose/root))))

(defn redact
  "Attempt to automatically redact the structured value."
  ([x]
   (redact x (key!)))
  ([x k]
   (let [sh (SipHash. k) ;; Instantiate once for performance benefit.
         hash (fn [v] (->> v nippy/freeze (.hash sh) (.get)))]
     (sr/transform [TREE-LEAVES string?] (partial redact-1 hash) x))))

(defn redact-stdio!
  "Redacts the JSON value read from stdin and writes it to stdout."
  []
  (-> *in* json/decode-stream redact (json/encode-stream *out*)))

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (redact-stdio!))
