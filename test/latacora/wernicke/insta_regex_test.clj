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
   [com.rpl.specter :as sr]
   [clojure.core.match :as m]
   [eidolon.core :as ec]
   [latacora.wernicke.patterns :as p]
   [clojure.string :as str]))

(def test-patterns
  "Some test patterns we want to turn into instaparse parsers.

  Sorted by increasing (proxy for) complexity so that generator shrinking does
  something useful. The proxy for complexity here is the (EDN) serialization of
  the test.chuck parse tree."
  (->> @#'wc/default-rules
       (map ::wc/pattern)
       (into [#"a" #"a*(b|c){1,10}x+"])
       (sort-by (fn [r] (-> r str cre/parse pr-str count)))))

(def ^:private GROUP-NAMES
  "Gets all of the group names in a parsed regex tree."
  [@#'wc/RE-PARSE-ELEMS
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

(tct/defspec instaparse-equivalents-match-regexes
  (tct'/for-all
   [regex (gen/elements test-patterns)
    s (gen'/string-from-regex regex)
    :let [grammar-map (ir/grammar-map regex)
          parse (ir/parser-fn grammar-map)
          result (parse s)
          expected-keys (->> regex
                             str
                             cre/parse
                             (sr/select [GROUP-NAMES (sr/view keyword)])
                             (into #{:root}))]]
   (t/is (re-matches regex s)
         "the original regex parses the string")
   (t/is (not (i/failure? result))
         "the instaparse version of the regex also parses the string")
   (t/is (= (-> grammar-map keys set) expected-keys)
         "each named group in the regex is a toplevel parser key")
   (t/is (->>
          result
          (sr/select [ec/NESTED seq? (sr/continuous-subseqs string?)])
          (into #{} (map count))
          (= #{1}))
         "every seq of strings in the parse tree has 1 string in it")
   (t/is (->>
          result
          (sr/select [ec/TREE-LEAVES string?])
          (str/join)
          (= s))
         "parsed parts are in the correct order")))

(defn ^:private nonterminals
  [grammar-map]
  (->> grammar-map
       (sr/select [@#'ir/GRAMMAR-MAP-PARSERS (comp #{:nt} :tag) (sr/view :keyword)])
       set))

(tct/defspec add-ns-tests
  (tct'/for-all
   [regex (gen/elements test-patterns)
    sym gen/symbol
    :let [orig-map (ir/grammar-map regex)
          groups (->> regex
                      str
                      cre/parse
                      (sr/select [GROUP-NAMES (sr/view #(keyword (name sym) %))])
                      (into #{(keyword (name sym) "root")}))
          named-map (ir/add-ns orig-map sym)
          nt-nses (->> named-map nonterminals (map namespace) (into #{}))
          unexpected-nt-nses (disj nt-nses (name sym))]]
   (t/is (= (-> named-map keys set) groups))
   (t/is (empty? unexpected-nt-nses))))
