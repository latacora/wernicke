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
   [eidolon.core :as ec]
   [latacora.wernicke.patterns :as p]
   [taoensso.nippy :as nippy]
   [clojure.spec.alpha :as s]
   [taoensso.timbre :as log])
  (:import
   io.whitfin.siphash.SipHasher
   java.security.SecureRandom
   java.util.BitSet
   java.util.function.Function))

(set! *warn-on-reflection* true)

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

(s/def ::behavior #{::keep ::keep-length})
(s/def ::group-config (s/keys :req [::behavior]))

(defn ^:private apply-group-behavior
  "Apply all of the behaviors specified in the group config to this test.chuck
  regex parse tree."
  [parsed group-config ^java.util.regex.Matcher matcher]
  (reduce
   (fn [parsed [group-name {::keys [behavior replacement]}]]
     (let [actual (.group matcher ^String group-name)]
       (case behavior
         ::keep (set-group-value parsed group-name actual)
         ::replace-with (set-group-value parsed group-name replacement)
         ::keep-length (set-group-length parsed group-name (count actual)))))
   parsed
   group-config))

(defmulti compile-rule ::type)
(defmethod compile-rule ::regex
  [rule]
  (-> rule
      ;; pattern may already be a Pattern, unless it came from EDN
      ;; configuration, in which case it'll be a string. EDN doesn't support
      ;; regexes out of the box; we could add a reader macro, but it's easier on
      ;; the user to just always convert to a pattern here. (re-pattern returns
      ;; Patterns verbatim.)
      (update ::pattern re-pattern)
      (assoc ::parsed-pattern (-> rule ::pattern str cre/parse))))

(defn regex-rule
  "Given a regex and optional ops, produce a compiled rule."
  ([pattern]
   (regex-rule pattern nil))
  ([pattern rule-opts]
   (compile-rule (assoc rule-opts ::type ::regex ::pattern pattern))))

(defmacro regex-rule*
  "Like [[regex-rule]] but automatically sets the name based on the sym."
  ([pattern-sym]
   `(regex-rule* ~pattern-sym nil))
  ([pattern-sym rule-opts]
   (let [var-meta (-> pattern-sym resolve meta)]
     `(regex-rule ~pattern-sym
                  (assoc ~rule-opts
                         ::name ~(keyword (-> var-meta :ns ns-name name)
                                          (-> var-meta :name name)))))))

(def pattern? (partial instance? java.util.regex.Pattern))
(s/def ::pattern pattern?)

(defn ^:private key!
  "Generate a new SipHash key."
  []
  (let [k (byte-array 16)]
    (.nextBytes (SecureRandom.) k)
    k))

(def default-opts
  {::rules
   [(regex-rule* p/timestamp-re)
    (regex-rule* p/mac-colon-re)
    (regex-rule* p/mac-dash-re)
    (regex-rule* p/ipv4-re)
    (regex-rule* p/aws-iam-unique-id-re
                 {::group-config
                  {"type" {::behavior ::keep}
                   "id" {::behavior ::keep-length}}})
    (regex-rule* p/internal-ec2-hostname-re)
    (regex-rule* p/arn-re
                 {::group-config
                  {"service" {::behavior ::keep}}})
    (regex-rule* p/aws-resource-id-re
                 {::group-config
                  {"type" {::behavior ::keep}
                   "id" {::behavior ::keep-length}}})
    (regex-rule* p/base32-re
                 {::group-config
                  {"s" {::behavior ::keep-length}
                   "pad" {::behavior ::keep}}})
    (regex-rule* p/base64-re
                 {::group-config
                  {"s" {::behavior ::keep-length}
                   "pad" {::behavior ::keep}}})
    (regex-rule* p/base16-re
                 {::group-config
                  {"s" {::behavior ::keep-length}}})
    (regex-rule* p/long-decimal-re)
    (regex-rule* p/long-alphanumeric-re
                 {::group-config
                  {"s" {::behavior ::keep-length}}})]})

(defn process-opts
  [opts]
  (let [opts (sr/setval
              [(sr/multi-path ec/TREE-KEYS ec/TREE-LEAVES)
               keyword? sr/NAMESPACE nil?]
              "latacora.wernicke.core"
              opts)]
    (->>
     opts
     (merge default-opts)
     (sr/transform
      [::rules]
      (fn [rules]
        (into
         (->> opts ::extra-rules (mapv compile-rule))
         (remove (comp (->> opts ::disabled-rules set) ::name))
         rules))))))

(defn ^Function ^:private ->Function
  [f]
  (reify Function
    (apply [this arg] (f arg))))

(defn ^:private redact-1
  [string-to-redact {::keys [hash rules]}]
  (let [cover (BitSet. (count string-to-redact))]
    (reduce
     (fn [s {::keys [pattern parsed-pattern group-config]}]
       (.replaceAll
        ^java.util.regex.Matcher (re-matcher pattern s)
        ^Function
        (->Function
         ;; The API only promises a MatchResult, not a Matcher, but in practice
         ;; it always is, and Matcher implements the named group API we want.
         (fn [^java.util.regex.Matcher mr]
           (let [start (.start mr)
                 end (.end mr)
                 match (.group mr)]
             (if-not (-> cover (.get start end) .isEmpty)
               match
               (let [rnd (-> match hash rand/make-random)]
                 (.set cover start end true)
                 (-> parsed-pattern
                     (apply-group-behavior group-config mr)
                     (cre/analyzed->generator)
                     (gen/call-gen rnd 1)
                     rose/root))))))))
     string-to-redact
     rules)))

(defn ^:private redact-1*
  "Like redact-1, but with logging."
  [val opts]
  (let [redacted (redact-1 val opts)]
    (if (identical? redacted val)
      (log/debug "Not redacting value" val)
      (log/trace "Redacted value" val redacted))
    redacted))

(defn ^:private redact
  "Redact the structured value under the given key."
  [x {::keys [key] :as opts}]
  (let [siphash (SipHasher/container key) ;; precompute w/ fixed key
        hash (fn [v] (->> v nippy/freeze (.hash siphash)))
        opts (assoc opts ::hash hash)]
    (sr/transform
     [(sr/multi-path ec/TREE-LEAVES ec/TREE-KEYS) string?]
     (fn [s] (redact-1* s opts))
     x)))

(defn redact!
  "Attempt to automatically redact the structured value.

  This is side-effectful because it will generate a new key each time."
  ([x opts]
   (redact x (assoc opts ::key (key!))))
  ([x]
   (redact! x default-opts)))
