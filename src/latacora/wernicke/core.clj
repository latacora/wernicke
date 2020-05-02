(ns latacora.wernicke.core
  "Tools for recording, redacting and replaying responses.

  If a value occurs twice in a document it will redact to the same value. Where
  possible, values will be redacted to similar-looking values, e.g. MAC
  addresses map to MAC addresses. Long values we don't recognize should be
  redacted to a string of the same length as a failsafe. Finally, only leaf
  values will be redacted; the key `PrivateIpAddresses` should not be redacted
  even though its value should be."
  (:require
   [clojure.test.check.generators :as gen]
   [clojure.test.check.random :as rand]
   [clojure.test.check.rose-tree :as rose]
   [com.gfredericks.test.chuck.regexes :as cre]
   [com.rpl.specter :as sr]
   [eidolon.core :as ec :refer [TREE-LEAVES]]
   [latacora.wernicke.patterns :as p]
   [taoensso.nippy :as nippy]
   [clojure.spec.alpha :as s]
   [taoensso.timbre :as log]
   [clojure.string :as str])
  (:import
   com.zackehh.siphash.SipHash
   java.security.SecureRandom))

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

(def ^:private GROUP-NAMES
  "Gets all of the group names in a parsed regex tree."
  [RE-PARSE-ELEMS
   (comp #{:group} :type)
   :flag
   ;; Could use core.unify here I guess but I like match :shrug:
   (sr/view
    (fn [flag]
      (m/match
       [flag]
       [[:GroupFlags [:NamedCapturingGroup [:GroupName n]]]] n
       :else nil)))
   some?])

(defn ^:private set-group-value
  "Given a test.chuck regex parse tree, find the group with the given name, and
  return a new parse tree with the group replaced with the constant string
  value."
  [parsed group-name constant]
  (log/trace "fixing regex group to constant value" group-name constant)
  (sr/setval*
   (named-group-sel group-name)
   {:type :character :character constant}
   parsed))

(defn ^:private set-group-length
  "Given a test.chuck regex parse tree, find the group with the given name, and
  then fix the length of all repetitions to be exactly the given len."
  [parsed group-name len]
  (log/trace "fixing regex group to constant length" group-name len)
  (sr/setval*
   [(named-group-sel group-name) ;; Find the parent named group
    RE-PARSE-ELEMS (comp #{:repetition} :type) :bounds]
   [len len]
   parsed))

(s/def ::group-behavior #{::keep ::keep-length})
(s/def ::group-config (s/map-of string? ::group-behavior))

(defn ^:private apply-group-behavior
  "Apply all of the behaviors specified in the group config to this test.chuck
  regex parse tree."
  [parsed group-config named-groups]
  (reduce
   (fn [parsed [group-name behavior]]
     (let [actual (named-groups group-name)]
       (case behavior
         ::keep (set-group-value parsed group-name actual)
         ::keep-length (set-group-length parsed group-name (count actual)))))
   parsed
   group-config))

(defmulti compile-rule ::type)
(defmethod compile-rule ::regex
  [{::keys [pattern group-config] :as rule}]
  (let [parsed (-> pattern str cre/parse)]
    (assoc
     rule
     ::parsed-pattern parsed
     ::group-names (sr/select [GROUP-NAMES] parsed))))

(defn regex-rule
  "Given a regex and optional ops, produce a compiled rule."
  ([pattern]
   (regex-rule pattern nil))
  ([pattern opts]
   (compile-rule (assoc opts ::type ::regex ::pattern pattern))))

(defmacro regex-rule*
  "Like [[regex-rule]] but automatically sets the name based on the sym."
  ([pattern-sym]
   `(regex-rule* ~pattern-sym nil))
  ([pattern-sym opts]
   (let [var-meta (-> 'p/timestamp-re resolve meta)]
     `(compile-rule
       (assoc ~opts
              ::name ~(keyword
                       (-> var-meta :ns ns-name name)
                       (-> var-meta :name name))
              ::type ::regex
              ::pattern ~pattern-sym)))))

(def pattern? (partial instance? java.util.regex.Pattern))
(s/def ::pattern pattern?)

(defn ^:private key!
  "Generate a new SipHash key."
  []
  (let [k (byte-array 16)]
    (.nextBytes (SecureRandom.) k)
    k))

(def ^:private default-rules
  [(regex-rule* p/timestamp-re)
   (regex-rule* p/mac-colon-re)
   (regex-rule* p/mac-dash-re)
   (regex-rule* p/ipv4-re)
   (regex-rule* p/aws-iam-unique-id-re {::group-config {"type" ::keep "id" ::keep-length}})
   (regex-rule* p/internal-ec2-hostname-re)
   (regex-rule* p/arn-re)
   (regex-rule* p/aws-resource-id-re {::group-config {"type" ::keep "id" ::keep-length}})
   (regex-rule* p/long-decimal-re)
   (regex-rule* p/long-alphanumeric-re {::group-config {"s" ::keep-length}})])

(defn ^:private redact-1
  "Redacts a string if has substrings to redact."
  [hash string-to-redact]
  (reduce
   (fn [s {::keys [pattern parsed-pattern group-names group-config]}]
     (str/replace
      s pattern
      (fn [[match & groups]]
        (let [named-groups (zipmap group-names groups)
              rnd (-> match hash rand/make-random)]
          (-> parsed-pattern
              (apply-group-behavior group-config named-groups)
              (cre/analyzed->generator)
              (gen/call-gen rnd 1)
              rose/root)))))
   string-to-redact
   default-rules))

(defn ^:private redact-1*
  "Like redact-1, but with logging."
  [hash val]
  (let [redacted (redact-1 hash val)]
    (if (identical? redacted val)
      (log/debug "Not redacting value" val)
      (log/trace "Redacted value" val redacted))
    redacted))

(defn ^:private redact
  "Redact the structured value under the given key."
  [x k]
  (let [sh (SipHash. k) ;; Instantiate once for performance benefit.
        hash (fn [v] (->> v nippy/freeze (.hash sh) (.get)))]
    (sr/transform [TREE-LEAVES string?] (partial redact-1* hash) x)))

(defn redact!
  "Attempt to automatically redact the structured value.

  This is side-effectful because it will generate a new key each time."
  [x]
  (redact x (key!)))
