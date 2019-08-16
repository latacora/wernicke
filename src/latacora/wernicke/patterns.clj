(ns latacora.wernicke.patterns
  (:require
   [clojure.string :as str]))

(def aws-resource-id-re
  "A regex matching generic AWS resource id.

  Examples: rtb-ff1234, acl-cafe12, subnet-fefe34."
  #"(?<type>[a-z]{1,15}-)+(?<id>[0-9a-f]{5,17})")

(def timestamp-re
  "A regex matching an ISO8601 timestamp as used in AWS."
  #"20\d{2}-[01]\d-[0-3]\dT[0-2]\d:[0-5]\d:[0-5]\d\.\d+Z")

(def mac-colon-re
  "A MAC address with colons between the bytes."
  #"([0-9a-f]{2}:){5}([0-9a-f]{2})")

(def mac-dash-re
  "A MAC address with dashes between the bytes."
  #"([0-9a-f]{2}-){5}([0-9a-f]{2})")

(def mac-re
  "A MAC address with dashes or colons between the bytes."
  (re-pattern (format "(%s|%s)" mac-colon-re mac-dash-re)))

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

(def aws-iam-unique-id-re
  "A regex for IAM unique ids.

  See https://docs.aws.amazon.com/IAM/latest/UserGuide/reference_identifiers.html#identifiers-unique-ids"
  #"(?<type>AAGA|ACCA|AGPA|AIDA|AIPA|AKIA|ANPA|ANVA|APKA|AROA|ASCA|ASIA)(?<id>[A-Z0-9]{16,})")
