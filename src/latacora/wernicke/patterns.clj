(ns latacora.wernicke.patterns
  (:require
   [clojure.string :as str]))

(def aws-resource-suffix-re
  "A regex matching the generic AWS resource id suffix."
  #"([0-9a-f]{5,7})")

(def aws-resource-id-re
  "A regex matching generic AWS resource id.

  Examples: rtb-ff1234, acl-cafe12, subnet-fefe34."
  (re-pattern (str #"([a-z]{2,7}-)+" aws-resource-suffix-re)))

(def timestamp-re
  "A regex matching an ISO8601 timestamp as used in AWS."
  #"20\d{2}-[01]\d-[0-3]\dT[0-2]\d:[0-5]\d:[0-5]\d\.\d+Z")

(def mac-re
  "A MAC address."
  #"([0-9a-f]{2}[:-]){5}([0-9a-f]{2})")

(def ipv4-octet-re
  #"(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)")

(def ipv4-re
  (re-pattern (str/join "\\." (repeat 4 ipv4-octet-re))))

(def long-decimal-re
  #"[0-9]{5,}")

(def internal-ec2-hostname-re
  (let [ip-part (str/join "-" (repeat 4 ipv4-octet-re))
        suffix "\\.ec2\\.internal"]
    (re-pattern (str "ip-" ip-part suffix))))

(def arn-re
  (let [partition "aws"
        service "([a-z0-9\\-]{2,20})"
        region "(((us|eu)-(west|east)(-\\d)?)|\\*)?"
        account (str "(" long-decimal-re "|\\*)?")
        resource "[A-Za-z0-9\\-\\._ */:]+"]
    (re-pattern (str/join ":" ["arn" partition service region account resource]))))
