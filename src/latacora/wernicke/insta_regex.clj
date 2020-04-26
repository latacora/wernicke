(ns latacora.wernicke.insta-regex
  (:require
   [instaparse.core :as i]
   [instaparse.combinators :as ic]
   [com.gfredericks.test.chuck.regexes :as cre]
   [taoensso.timbre :refer [spy]]
   [com.rpl.specter :as sr]
   [eidolon.core :as ec]
   [clojure.core.match :as m]
   [clojure.string :as str]))

(def pattern? (partial instance? java.util.regex.Pattern))

(def ^:private RE-PARSE-ELEMS-POST
  "A navigator for all the parsed elements in a test.chuck regex parse tree in post-order.

  Stops at each regex part and recursively navigates down into every element."
  ;; TODO: see if the other uses of RE-PARSE-ELEMS still need this and still need it in a specific order
  (sr/recursive-path [] p [(sr/continue-then-stay :elements sr/ALL p)]))

(defn ^:private convert
  [regex]
  (let [regex (str regex) ;; might be a pattern
        parsed (cre/parse regex)]
    (sr/replace-in
     [RE-PARSE-ELEMS-POST]
     (fn [{:keys [type elements] :as re-part}]
       (case type
         :character [(-> re-part :character str ic/string) nil]

         :concatenation [(apply ic/cat elements) nil]

         :alternation [(apply ic/alt elements) nil]

         :repetition
         (let [[min max] (:bounds re-part)
               max (or max ##Inf)]
           [(->> elements (apply ic/cat) (ic/rep min max)) nil])

         :class
         ;; classes are quite complicated and can't themselves be (named)
         ;; groups, which is the entire point of this parser; so we delegate
         ;; them to the actual regex implementation
         [(->> re-part i/span (apply subs regex) re-pattern ic/regexp)
          nil]

         (:class-union :class-intersection :class-base :range)
         [nil nil] ;; handled by :class via regex

         :group
         (if-some [group-name
                   (m/match
                    [(:flag re-part)]
                    [[:GroupFlags [:NamedCapturingGroup [:GroupName n]]]] (keyword n)
                    :else nil)]
           ;; telling instaparse about named groups is the only reason we use replace-in
           [(ic/nt group-name) {group-name (apply ic/cat elements)}]
           [(apply ic/cat elements) nil])))
     parsed
     :merge-fn merge)))

(defn grammar-map
  "Given a regex (string or pattern), parse it into an Instaparse grammar map.

  The root of the grammar map will have the keyword `:root`. Each named group in
  the regex will have a grammar entry with the keyword of the same name."
  [regex]
  (let [[root rules] (convert regex)]
    (assoc rules :root root)))
