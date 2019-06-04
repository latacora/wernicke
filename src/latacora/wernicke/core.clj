(ns latacora.wernicke.core
  "Tools for recording, redacting and replaying responses.

  If a value occurs twice in a document it will redact to the same value. Where
  possible, values will be redacted to similar-looking values, e.g. MAC
  addresses map to MAC addresses. Long values we don't recognize should be
  redacted to a string of the same length as a failsafe. Finally, only leaf
  values will be redacted; the key `PrivateIpAddresses` should not be redacted
  even though its value should be."
  (:require [cheshire.core :as json]
            [clojure.test.check
             [generators :as gen]
             [random :as rand]
             [rose-tree :as rose]]
            [com.gfredericks.test.chuck.generators :as cgen]
            [com.rpl.specter :as sr]
            [eidolon.core :refer [TREE-LEAVES]]
            [latacora.wernicke.patterns :as p]
            [taoensso.nippy :as nippy])
  (:import com.zackehh.siphash.SipHash
           [java.awt.datatransfer DataFlavor StringSelection]
           java.security.SecureRandom)
  (:gen-class))

(defn key!
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

(def ^:private redactable
  [p/timestamp-re
   p/mac-colon-re
   p/mac-dash-re
   p/ipv4-re
   p/long-decimal-re
   p/internal-ec2-hostname-re
   p/arn-re])

(defn ^:private redact-1
  "Redacts a single item, assuming it is redactable."
  [key val]
  (let [compute-dual (partial compute-dual key val)]
    (if (string? val)
      (or (when-let [[_ pfx _] (re-matches p/aws-resource-id-re val)]
            (str pfx (compute-dual p/aws-resource-suffix-re)))
          (first
           (for [re redactable :when (re-matches re val)]
             (compute-dual re)))
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

(defn ^:private get-clipboard
  "Get the current clipboard instance."
  []
  (.getSystemClipboard (java.awt.Toolkit/getDefaultToolkit)))

(defn ^:private read-from-clipboard!
  "Slurps from the clipboard."
  []
  (if-some [contents (.getContents (get-clipboard) nil)]
    (.getTransferData contents DataFlavor/stringFlavor)))

(defn ^:private write-to-clipboard!
  "Sets text to clipboard."
  [text]
  (.setContents (get-clipboard) (StringSelection. text) nil))

(defn redact-clipboard!
  "Redacts the value in the clipboard and puts the result back."
  []
  (-> (read-from-clipboard!) json/decode redact json/encode write-to-clipboard!))

(defn redact-stdio!
  "Redacts the JSON value read from stdin and writes it to stdout."
  []
  (-> *in* json/decode-stream redact (json/encode-stream *out*)))

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (redact-stdio!))
