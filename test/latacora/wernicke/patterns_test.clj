(ns latacora.wernicke.patterns-test
  (:require [latacora.wernicke.patterns :as wp]
            [clojure.test :as t]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :as tct]
            [com.gfredericks.test.chuck.generators :as gen']))

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
  ["i" "sg" "rtb" "subnet" "acl" "aclassoc" "bun" "import-i" "cgw" "dopt" "eipalloc" "eassoc" "export-i" "fl" "ami" "import-ami" "igw" "acl" "aclassoc" "eni" "eni-attach" "pl" "rtbassoc" "subnet" "subnet-cidr-assoc" "vpc" "vpc-cidr-assoc" "vpce" "pcx" "vpn" "vgw"])

(t/deftest aws-resource-type-tests
  (doseq [t aws-resource-types]
    (t/is (re-matches wp/aws-resource-id-re (str t "-12345"))
          "short id works")
    (t/is (re-matches wp/aws-resource-id-re (str t "-0123456789abcdef0"))
          "long id works")))

(t/deftest aws-iam-unqiue-id-test
  (t/are [id] (re-matches wp/aws-iam-unique-id-re id)
    "AIDAJQABLZS4A3QDU576Q"
    "AROAJ3UQRY75GDAKWTAA2")

  (t/are [s] (not (re-matches wp/aws-iam-unique-id-re s))
    "iddqd"
    "XYZZY"))

(tct/defspec timestamp-digit-boundaries-test
  (prop/for-all [output (gen'/string-from-regex wp/timestamp-re)]
                (let [[full year month day hour minute second rest] (re-find wp/timestamp-re output)]
                  (and (<= (Integer/parseInt month) 12)
                       (<= (Integer/parseInt day) 31)
                       (<= (Integer/parseInt hour) 23)
                       (<= (Integer/parseInt minute) 59)
                       (<= (Integer/parseInt second) 59)))))


(tct/defspec ipv4-re-leading-zero-test
  (prop/for-all [output (gen'/string-from-regex wp/ipv4-re)]
                (every? true? (map #(if (not= (count %) 1)
                                      (not= (str (first %)) "0")
                                      true) (str/split output #"\.")))))

(tct/defspec ip-octet-leading-zero-test
  (prop/for-all [output (gen'/string-from-regex wp/ipv4-octet-re-1)]
  (prop/for-all [output (gen'/string-from-regex wp/ipv4-octet-re)]
                (if (not= (count output) 1)
                  (not= (str (first output)) "0")
                  true)
                ))

