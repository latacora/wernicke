(ns latacora.wernicke.patterns-test
  (:require [latacora.wernicke.patterns :as wp]
            [clojure.test :as t]
            [clojure.java.io :as io]))

(def arns
  "Examples of ARNs.

  See http://docs.aws.amazon.com/general/latest/gr/aws-arns-and-namespaces.html#arns-syntax"
  (->> "valid-arns" io/resource io/reader line-seq))

(t/deftest arn-re-tests
  (doseq [arn arns]
    (t/is (re-matches wp/arn-re arn))))

(def mac-with-dashes "12-34-45-ab-cd-ef")
(def mac-with-colons "12:34:45:ab:cd:ef")
(def mac-travesty-with-mixed-delimiters "12:34-45:ab-cd-ef")

(t/deftest mac-tests
  (t/is (re-matches wp/mac-dash-re mac-with-dashes))
  (t/is (not (re-matches wp/mac-dash-re mac-with-colons)))
  (t/is (not (re-matches wp/mac-dash-re mac-travesty-with-mixed-delimiters)))

  (t/is (re-matches wp/mac-colon-re mac-with-colons))
  (t/is (not (re-matches wp/mac-colon-re mac-with-dashes)))
  (t/is (not (re-matches wp/mac-colon-re mac-travesty-with-mixed-delimiters)))

  (t/is (re-matches wp/mac-re mac-with-colons))
  (t/is (re-matches wp/mac-re mac-with-dashes))
  (t/is (not (re-matches wp/mac-re mac-travesty-with-mixed-delimiters))))

(def aws-resource-types
  ["i" "sg" "rtb" "subnet" "acl" "aclassoc" "bundle" "conversion-task" "customer-gateway" "dhcp-options" "elastic-ip-allocation" "elastic-ip-association" "export-task" "flow-log" "image" "import-task" "internet-gateway" "network-acl" "network-acl-association" "network-interface" "network-interface-attachment" "prefix-list" "route-table" "route-table-association" "security-group" "subnet" "subnet-cidr-block-association" "vpc" "vpc-cidr-block-association" "vpc-endpoint" "vpc-peering-connection" "vpn-connection" "vpn-gateway"])

(t/deftest aws-resource-type-tests
  (doseq [t aws-resource-types]
    (t/is (re-matches wp/aws-resource-id-re (str t "-12345"))
          "short id works")
    (t/is (re-matches wp/aws-resource-id-re (str t "-0123456789abcdef0"))
          "long id works")))
