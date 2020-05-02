(ns latacora.wernicke.insta-regex
  (:require
   [instaparse.core :as i]
   [instaparse.combinators :as ic]
   [com.gfredericks.test.chuck.regexes :as cre]
   [com.rpl.specter :as sr]
   [clojure.core.match :as m]
   [clojure.string :as str]))

(def pattern? (partial instance? java.util.regex.Pattern))

(def ^:private RE-PARSE-ELEMS-POST
  "A navigator for all the parsed elements in a test.chuck regex parse tree in post-order.

  Stops at each regex part and recursively navigates down into every element."
  ;; TODO: see if the other uses of RE-PARSE-ELEMS still need this and still
  ;; need it in a specific order. My guess is no; we just need to find groups
  ;; now for adjusting them but we don't need them in any particular order. The
  ;; reason we need order is that re-seq and friends will only return groups in
  ;; a particular order, and we no longer use that (and instaparse returns named
  ;; matches).
  (sr/recursive-path [] p [(sr/continue-then-stay :elements sr/ALL p)]))

(defn ^:private convert
  "Given a regex Pattern object (or string representing a regex), convert it into
  [root, rules] where root is a root rule and rules are a grammar map of named
  rules."
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
         ;; groups, which are the entire point of this parser; so we delegate
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

(def ^:private GRAMMAR-MAP-PARSERS
  "A navigator for all the parsed elements in an Instaparse grammar map in
  post-order."
  [sr/MAP-VALS ;; top level is {:name PARSER}
   (sr/recursive-path
    [] p
    [sr/STAY
     (sr/multi-path
      [(sr/continue-then-stay [(sr/must :parsers) sr/ALL p])]
      [(sr/continue-then-stay [(sr/must :parser) p])])])])

(defn ^:private optimize
  "Optimizes a grammar map, e.g. merges cat'd regexes, turns repetitions of
  regexes into regexes with repetitions...

  The resulting grammar map will _not_ produce the same parse! In the input
  grammar map, the concatenation of 3 regexes `[abc]`, `[pqr]`, `[xyz]` matching
  `apx` will map to `[a, p, x]`, after optimization, it will map to the single
  string `apx`. So arguably this function should really be called `reasonablify`
  or something."
  [grammar-map]
  (sr/transform
   [GRAMMAR-MAP-PARSERS]
   (fn [parser]
     (m/match
      [parser]
      ;; consecutive regexes/strings in a :cat can be merged
      [{:tag :cat}]
      (sr/transform
       [:parsers (sr/continuous-subseqs (comp #{:regexp :string} :tag))]
       #(let [merged (->> % (map (some-fn :regexp :string)) (str/join ""))]
          [{:tag :regexp :regexp (re-pattern merged)}])
       parser)

      ;; rep of regexes -> regex with embedded repetition
      [{:tag :rep :min min :max max
        :parser {:tag :regexp :regexp regex}}]
      {:tag :regexp
       :regexp (let [max (if (#{##Inf} max) "" max)]
                 (re-pattern (format "%s{%s,%s}" regex min max)))}

      :else parser))
   grammar-map))

(defn grammar-map
  "Given a regex (string or pattern), parse it into an Instaparse grammar map.

  The root of the grammar map will have the keyword `:root`. Each named group in
  the regex will have a grammar entry with the keyword of the same name."
  [regex]
  (let [[root rules] (convert regex)]
    (-> rules
        (assoc :root root)
        optimize)))

(defn add-ns
  "Adds a namespace to the keywords in a grammar map.

  This is useful for composing parsers. Instaparse requires a single top-level
  map, so we can use namespaces to allow a parser to compose multiple other
  parsers."
  [grammar-map ns-sym]
  (sr/transform
   [(sr/multi-path
     sr/MAP-KEYS
     [GRAMMAR-MAP-PARSERS (comp #{:nt} :tag) :keyword])]
   (fn [bare] (keyword (name ns-sym) (name bare)))
   grammar-map))

(def ^:private CONTENT
  "A nagivator to the content sections of enlive-formatted instaparse results."
  (sr/recursive-path
   [] p
   (sr/cond-path
    map? [:content p]
    seq? (sr/continue-then-stay sr/ALL p))))

(defn ^:private de-afs
  "Instaparse has a weird custom seq that confuses specter, reversing results.

  See [[instaparse.auto-flatten-seq]]."
  [parse-result]
  (sr/transform [CONTENT] reverse parse-result))

(defn ^:private merge-strings
  "Merges contiguous subsequences of strings in an Enlive-style parse result from
  Instaparse.

  [[optimize]] aims to remove many, but not all, of the cases this function
  would be necessary. For example, the regex #\"a*(b|c){1,10}x+\" has a
  repetition of a group followed by a repetition of a character; we can't merge
  the two, so the string \"bc\" gets parsed as b followed by c and not a single
  string."
  [parse-result]
  (sr/transform
   [CONTENT (sr/continuous-subseqs string?)]
   (comp vector str/join)
   parse-result))

(defn parser-fn
  "Creates a function that parses strings, given a grammar map."
  [grammar-map]
  (let [parser (i/parser grammar-map :start :root :output-format :enlive)]
    (fn [s] (->> s (i/parse parser) de-afs merge-strings))))
