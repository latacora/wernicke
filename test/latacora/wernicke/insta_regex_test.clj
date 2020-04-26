(ns latacora.wernicke.insta-regex-test
  (:require
   [instaparse.core :as i]
   [latacora.wernicke.insta-regex :as ir]
   [clojure.test :as t]
   [latacora.wernicke.core :as wc]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.clojure-test :as tct]
   [com.gfredericks.test.chuck.clojure-test :as tct']
   [com.gfredericks.test.chuck.generators :as gen']
   [com.gfredericks.test.chuck.regexes :as cre]
   [taoensso.nippy :as nippy]))

(def test-patterns
  "Some test patterns we want to turn into instaparse parsers.

  Sorted by increasing (proxy for) complexity so that generator shrinking does
  something useful. The proxy for complexity here is the (EDN) serialization of
  the test.chuck parse tree."
  (->> @#'wc/default-rules
       (map ::wc/pattern)
       (into [#"a" #"a*(b|c){1,10}x+"])
       (sort-by (fn [r] (-> r str cre/parse pr-str count)))))

(tct/defspec instaparse-equivalents-match-regexes
  (tct'/for-all
   [regex (gen/elements test-patterns)
    s (gen'/string-from-regex regex)
    :let [grammar-map (ir/grammar-map regex)
          parser (i/parser grammar-map :start :root)]]
   (t/is (some? (i/parse parser s)))))
