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

(def ^:private RE-PARSE-ELEMS
  "A navigator for all the parsed elements in a test.chuck regex parse tree.

  Stops at each regex part and recursively navigates down into every element."
  (sr/recursive-path [] p [(sr/stay-then-continue :elements sr/ALL p)]))

(defn ^:private named-group-sel
  "Creates a Specter navigator to the given named regex group entry in a
  test.chuck regex parse tree."
  [group-name]
  [RE-PARSE-ELEMS
   (comp #{:group} :type)
   (comp #{[:GroupFlags [:NamedCapturingGroup [:GroupName group-name]]]} :flag)])

(defn ^:private set-group-value
  "Given a test.chuck regex parse tree, find the group with the given name, and
  return a new parse tree with the group replaced with the constant string
  value."
  [parsed group-name constant]
  (sr/setval (named-group-sel group-name) {:type :character :character constant} parsed))

(defn ^:private set-group-length
  "Given a test.chuck regex parse tree, find the group with the given name, and
  then fix the length of all repetitions to be exactly the given len."
  [parsed group-name len]
  (sr/setval
   [(named-group-sel group-name) ;; Find the parent named group
    RE-PARSE-ELEMS (comp #{:repetition} :type) :bounds]
   [len len]
   parsed))

(s/def ::group-behavior #{::keep ::keep-length})
(s/def ::group-config (s/map-of string? ::group-behavior))

(defn ^:private apply-group-behavior
  "Apply all of the behaviors specified in the group config to this test.chuck
  regex parse tree."
  [parsed group-config ^java.util.regex.Matcher m]
  (reduce
   (fn [parsed [group-name behavior]]
     (let [actual (.group m group-name)]
       (case behavior
         ::keep (set-group-value parsed group-name actual)
         ::keep-length (set-group-length parsed group-name (count actual)))))
   parsed group-config))

(defmulti compile-rule ::type)
(defmethod compile-rule ::regex
  [{::keys [pattern group-config] :as rule}]
  (let [parsed (-> pattern str cre/parse)]
    (assoc
     rule
     ;; Unlike [[cgen/string-from-regex]], we're willing to temporarily ignore
     ;; unsupported features like named groups. That's _generally_ a bug, and we
     ;; should check for them, but that's blocked on upstream test.chuck work.
     ::generator-fn (fn [val]
                      (let [m (re-matcher pattern val)]
                        (when (.matches m)
                          (-> parsed
                              (apply-group-behavior group-config m)
                              (cre/analyzed->generator))))))))

(defn regex-rule
  "Given a regex and optional ops, produce a compiled rule."
  ([pattern]
   (regex-rule pattern nil))
  ([pattern opts]
   (compile-rule (assoc opts ::type ::regex ::pattern pattern))))

(def pattern? (partial instance? java.util.regex.Pattern))
(s/def ::pattern pattern?)

(def generator-fn? some?)
(s/def ::generator-fn generator-fn?)

(defn ^:private key!
  "Generate a new SipHash key."
  []
  (let [k (byte-array 16)]
    (.nextBytes (SecureRandom.) k)
    k))

(def ^:private default-rules
  [(regex-rule p/timestamp-re)
   (regex-rule p/mac-colon-re)
   (regex-rule p/mac-dash-re)
   (regex-rule p/ipv4-re)
   (regex-rule p/long-decimal-re)
   (regex-rule p/internal-ec2-hostname-re)
   (regex-rule p/arn-re)
   (regex-rule p/aws-resource-id-re {::group-config {"type" ::keep "id" ::keep-length}})
   (regex-rule #"(?<s>[A-Za-z0-9]{12,})" {::group-config {"s" ::keep-length}})])

(defn ^:private redact-1
  "Redacts a single item, assuming it is redactable."
  [hash val]
  (let [rnd (-> val hash rand/make-random)]
    (-> (eduction
         (map (fn [{::keys [generator-fn]}]
                (some-> val generator-fn (gen/call-gen rnd 1) rose/root)))
         (filter some?)
         default-rules)
        (first)
        (or val))))

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
