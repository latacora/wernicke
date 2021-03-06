(ns latacora.wernicke.patterns
  (:require
   [clojure.string :as str]))

(def aws-resource-id-re
  "A regex matching generic AWS resource id.

  Examples: rtb-ff1234, acl-cafe12, subnet-fefe34."
  #"(?<type>(?:[a-z]{1,15}-?)+)-(?<id>[0-9a-f]{5,17})")

(def timestamp-re
  "A regex matching an ISO8601 timestamp as used in AWS."
  (let [year "(?<year>20\\d{2})"
        month "(?<month>[0][1-9]|[1][0-2])"
        day "(?<day>[0][1-9]|[1-2][0-9]|[3][0-1])"
        hour "(?<hour>0[0-9]|1[0-9]|2[0-3])"
        minute "(?<minute>[0-5]\\d)"
        second "(?<second>[0-5]\\d)"
        end "(?<rest>\\.\\d+Z)"]
    (re-pattern (format "%s-%s-%sT%s:%s:%s%s" year month day hour minute second end))))

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
  #"(25[0-5]|2[0-4][0-9]|[1][0-9][0-9]|[1-9][0-9]|[0-9])")

(def ipv4-re
  (re-pattern (str/join "\\." (repeat 4 ipv4-octet-re))))

(def decimal-re
  #"[0-9]{5,}")

(def long-decimal-re
   #"(?<s>[0-9]{12,})")

(def long-alphanumeric-re
  "A regex for long (24+) alphanumeric strings."
  #"(?<s>[A-Za-z0-9]{24,})")

(def internal-ec2-hostname-re
  (let [ip-part (str/join "-" (repeat 4 ipv4-octet-re))
        suffix "\\.ec2\\.internal"]
    (re-pattern (str "ip-" ip-part suffix))))

(def arn-re
  (let [partition "aws"
        service "(?<service>[a-z0-9\\-]{2,20})"
        region "(?<region>((us|eu)-(west|east)(-\\d)?)|\\*)?"
        account (str "(?<account>" decimal-re "|\\*)?")
        resource "[A-Za-z0-9\\-\\._ */:]+"]
    (re-pattern (str/join ":" ["arn" partition service region account resource]))))

(def aws-iam-unique-id-re
  "A regex for IAM unique ids.

  See https://docs.aws.amazon.com/IAM/latest/UserGuide/reference_identifiers.html#identifiers-unique-ids"
  #"(?<type>AAGA|ACCA|AGPA|AIDA|AIPA|AKIA|ANPA|ANVA|APKA|AROA|ASCA|ASIA)(?<id>[A-Z0-9]{16,})")

(def base16-re
  "A regex for base16 or hex strings"
  #"(?<s>[0-9a-f]{24,})")

(def base16-re-uppercase
  "A regex for base16 or hex strings"
  #"(?<s>[0-9A-F]{24,})")

(def base32-re
  #"(?<s>[A-Z2-7]{32,})(?<pad>={0,6})")

(def base64-re
  #"(?<s>[A-Za-z0-9+/]{32,})(?<pad>={0,3})")

