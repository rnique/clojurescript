;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns cljs.core
  (:refer-clojure :exclude [-> ->> .. amap and areduce alength aclone assert binding bound-fn case comment cond condp
                            declare definline definterface defmethod defmulti defn defn- defonce
                            defprotocol defrecord defstruct deftype delay destructure doseq dosync dotimes doto
                            extend-protocol extend-type fn for future gen-class gen-interface
                            if-let if-not import io! lazy-cat lazy-seq let letfn locking loop
                            memfn ns or proxy proxy-super pvalues refer-clojure reify sync time
                            when when-first when-let when-not while with-bindings with-in-str
                            with-loading-context with-local-vars with-open with-out-str with-precision with-redefs
                            satisfies? identical? true? false? number? nil? instance? symbol? keyword? string? str get
                            make-array vector list hash-map array-map hash-set

                            aget aset
                            + - * / < <= > >= == zero? pos? neg? inc dec max min mod
                            byte char short int long float double
                            unchecked-byte unchecked-char unchecked-short unchecked-int
                            unchecked-long unchecked-float unchecked-double
                            unchecked-add unchecked-add-int unchecked-dec unchecked-dec-int
                            unchecked-divide unchecked-divide-int unchecked-inc unchecked-inc-int
                            unchecked-multiply unchecked-multiply-int unchecked-negate unchecked-negate-int
                            unchecked-subtract unchecked-subtract-int unchecked-remainder-int
                            unsigned-bit-shift-right

                            bit-and bit-and-not bit-clear bit-flip bit-not bit-or bit-set
                            bit-test bit-shift-left bit-shift-right bit-xor

                            cond-> cond->> as-> some-> some->>

                            if-some when-some test ns-interns ns-unmap var vswap! macroexpand-1 macroexpand])
  (:require clojure.walk
            clojure.set
            cljs.compiler
            [cljs.util :as util]
            [cljs.env :as env])
  (:import [java.io File]))

(alias 'core 'clojure.core)
(alias 'ana 'cljs.analyzer)

(defmacro import-macros [ns [& vars]]
  (core/let [ns (find-ns ns)
             vars (map #(ns-resolve ns %) vars)
             syms (map
                    (core/fn [^clojure.lang.Var v]
                      (core/-> v .sym
                        (with-meta
                          (merge
                            {:macro true}
                            (update-in (select-keys (meta v) [:arglists :doc :file :line])
                              [:arglists] (core/fn [arglists] `(quote ~arglists)))))))
                    vars)
             defs (map
                    (core/fn [sym var]
                      (core/let [{:keys [arglists doc file line]} (meta sym)]
                        `(do
                           (def ~sym (deref ~var))
                           ;for AOT compilation
                           (alter-meta! (var ~sym) assoc
                             :macro true
                             :arglists ~arglists
                             :doc ~doc
                             :file ~file
                             :line ~line))))
                    syms vars)]
    `(do ~@defs
         :imported)))

(import-macros clojure.core
 [-> ->> .. assert comment cond
  declare defn-
  doto
  extend-protocol fn for
  if-let if-not letfn
  memfn
  when when-first when-let when-not while
  cond-> cond->> as-> some-> some->>
  if-some when-some])

(defn- ^{:dynamic true} assert-valid-fdecl
  "A good fdecl looks like (([a] ...) ([a b] ...)) near the end of defn."
  [fdecl]
  (when (empty? fdecl) (throw (IllegalArgumentException.
                                "Parameter declaration missing")))
  (core/let [argdecls (map
                   #(if (seq? %)
                     (first %)
                     (throw (IllegalArgumentException.
                              (if (seq? (first fdecl))
                                (core/str "Invalid signature \""
                                  %
                                  "\" should be a list")
                                (core/str "Parameter declaration \""
                                  %
                                  "\" should be a vector")))))
                   fdecl)
        bad-args (seq (remove #(vector? %) argdecls))]
    (when bad-args
      (throw (IllegalArgumentException.
               (core/str "Parameter declaration \"" (first bad-args)
                         "\" should be a vector"))))))

(def
  ^{:private true}
  sigs
  (fn [fdecl]
    (assert-valid-fdecl fdecl)
    (core/let [asig
          (fn [fdecl]
            (core/let [arglist (first fdecl)
                  ;elide implicit macro args
                  arglist (if (clojure.lang.Util/equals '&form (first arglist))
                            (clojure.lang.RT/subvec arglist 2 (clojure.lang.RT/count arglist))
                            arglist)
                  body (next fdecl)]
              (if (map? (first body))
                (if (next body)
                  (with-meta arglist (conj (if (meta arglist) (meta arglist) {}) (first body)))
                  arglist)
                arglist)))]
      (if (seq? (first fdecl))
        (core/loop [ret [] fdecls fdecl]
          (if fdecls
            (recur (conj ret (asig (first fdecls))) (next fdecls))
            (seq ret)))
        (core/list (asig fdecl))))))

(defmacro defonce [x init]
  `(when-not (exists? ~x)
     (def ~x ~init)))

(defmacro ^{:private true} assert-args [fnname & pairs]
  `(do (when-not ~(first pairs)
         (throw (IllegalArgumentException.
                  ~(core/str fnname " requires " (second pairs)))))
     ~(core/let [more (nnext pairs)]
        (when more
          (list* `assert-args fnname more)))))

(core/defn destructure [bindings]
  (core/let [bents (partition 2 bindings)
         pb (fn pb [bvec b v]
              (core/let [pvec
                     (fn [bvec b val]
                       (core/let [gvec (gensym "vec__")]
                         (core/loop [ret (-> bvec (conj gvec) (conj val))
                                     n 0
                                     bs b
                                     seen-rest? false]
                           (if (seq bs)
                             (core/let [firstb (first bs)]
                               (core/cond
                                 (= firstb '&) (recur (pb ret (second bs) (core/list `nthnext gvec n))
                                                      n
                                                      (nnext bs)
                                                      true)
                                 (= firstb :as) (pb ret (second bs) gvec)
                                 :else (if seen-rest?
                                         (throw (new Exception "Unsupported binding form, only :as can follow & parameter"))
                                         (recur (pb ret firstb (core/list `nth gvec n nil))
                                                (core/inc n)
                                                (next bs)
                                                seen-rest?))))
                             ret))))
                     pmap
                     (fn [bvec b v]
                       (core/let [gmap (gensym "map__")
                                  defaults (:or b)]
                         (core/loop [ret (-> bvec (conj gmap) (conj v)
                                             (conj gmap) (conj `(if (seq? ~gmap) (apply core/hash-map ~gmap) ~gmap))
                                             ((fn [ret]
                                                (if (:as b)
                                                  (conj ret (:as b) gmap)
                                                  ret))))
                                     bes (reduce
                                          (fn [bes entry]
                                            (reduce #(assoc %1 %2 ((val entry) %2))
                                                    (dissoc bes (key entry))
                                                    ((key entry) bes)))
                                          (dissoc b :as :or)
                                          {:keys #(if (core/keyword? %) % (keyword (core/str %))),
                                           :strs core/str, :syms #(core/list `quote %)})]
                           (if (seq bes)
                             (core/let [bb (key (first bes))
                                        bk (val (first bes))
                                        has-default (contains? defaults bb)]
                               (recur (pb ret bb (if has-default
                                                   (core/list `get gmap bk (defaults bb))
                                                   (core/list `get gmap bk)))
                                      (next bes)))
                             ret))))]
                    (core/cond
                      (core/symbol? b) (-> bvec (conj (if (namespace b) (symbol (name b)) b)) (conj v))
                      (core/keyword? b) (-> bvec (conj (symbol (name b))) (conj v))
                      (vector? b) (pvec bvec b v)
                      (map? b) (pmap bvec b v)
                      :else (throw (new Exception (core/str "Unsupported binding form: " b))))))
         process-entry (fn [bvec b] (pb bvec (first b) (second b)))]
        (if (every? core/symbol? (map first bents))
          bindings
          (if-let [kwbs (seq (filter #(core/keyword? (first %)) bents))]
            (throw (new Exception (core/str "Unsupported binding key: " (ffirst kwbs))))
            (reduce process-entry [] bents)))))

(defmacro let
  "binding => binding-form init-expr

  Evaluates the exprs in a lexical context in which the symbols in
  the binding-forms are bound to their respective init-exprs or parts
  therein."
  [bindings & body]
  (assert-args
     (vector? bindings) "a vector for its binding"
     (even? (count bindings)) "an even number of forms in binding vector")
  `(let* ~(destructure bindings) ~@body))

(defmacro loop
  "Evaluates the exprs in a lexical context in which the symbols in
  the binding-forms are bound to their respective init-exprs or parts
  therein. Acts as a recur target."
  [bindings & body]
    (assert-args
      (vector? bindings) "a vector for its binding"
      (even? (count bindings)) "an even number of forms in binding vector")
    (let [db (destructure bindings)]
      (if (= db bindings)
        `(loop* ~bindings ~@body)
        (let [vs (take-nth 2 (drop 1 bindings))
              bs (take-nth 2 bindings)
              gs (map (fn [b] (if (core/symbol? b) b (gensym))) bs)
              bfs (reduce (fn [ret [b v g]]
                            (if (core/symbol? b)
                              (conj ret g v)
                              (conj ret g v b g)))
                          [] (map core/vector bs vs gs))]
          `(let ~bfs
             (loop* ~(vec (interleave gs gs))
               (let ~(vec (interleave bs gs))
                 ~@body)))))))

(def fast-path-protocols
  "protocol fqn -> [partition number, bit]"
  (zipmap (map #(symbol "cljs.core" (core/str %))
               '[IFn ICounted IEmptyableCollection ICollection IIndexed ASeq ISeq INext
                 ILookup IAssociative IMap IMapEntry ISet IStack IVector IDeref
                 IDerefWithTimeout IMeta IWithMeta IReduce IKVReduce IEquiv IHash
                 ISeqable ISequential IList IRecord IReversible ISorted IPrintWithWriter IWriter
                 IPrintWithWriter IPending IWatchable IEditableCollection ITransientCollection
                 ITransientAssociative ITransientMap ITransientVector ITransientSet
                 IMultiFn IChunkedSeq IChunkedNext IComparable INamed ICloneable IAtom
                 IReset ISwap])
          (iterate (fn [[p b]]
                     (if (core/== 2147483648 b)
                       [(core/inc p) 1]
                       [p (core/bit-shift-left b 1)]))
                   [0 1])))

(def fast-path-protocol-partitions-count
  "total number of partitions"
  (let [c (count fast-path-protocols)
        m (core/mod c 32)]
    (if (core/zero? m)
      (core/quot c 32)
      (core/inc (core/quot c 32)))))

(defmacro str [& xs]
  (let [strs (->> (repeat (count xs) "cljs.core.str(~{})")
               (interpose ",")
               (apply core/str))]
    (list* 'js* (core/str "[" strs "].join('')") xs)))

(defn- bool-expr [e]
  (vary-meta e assoc :tag 'boolean))

(defn- simple-test-expr? [env ast]
  (core/and
    (#{:var :invoke :constant :dot :js} (:op ast))
    ('#{boolean seq} (cljs.analyzer/infer-tag env ast))))

(defmacro and
  "Evaluates exprs one at a time, from left to right. If a form
  returns logical false (nil or false), and returns that value and
  doesn't evaluate any of the other expressions, otherwise it returns
  the value of the last expr. (and) returns true."
  ([] true)
  ([x] x)
  ([x & next]
    (let [forms (concat [x] next)]
      (if (every? #(simple-test-expr? &env %)
            (map #(cljs.analyzer/analyze &env %) forms))
        (let [and-str (->> (repeat (count forms) "(~{})")
                        (interpose " && ")
                        (apply core/str))]
          (bool-expr `(~'js* ~and-str ~@forms)))
        `(let [and# ~x]
           (if and# (and ~@next) and#))))))

(defmacro or
  "Evaluates exprs one at a time, from left to right. If a form
  returns a logical true value, or returns that value and doesn't
  evaluate any of the other expressions, otherwise it returns the
  value of the last expression. (or) returns nil."
  ([] nil)
  ([x] x)
  ([x & next]
    (let [forms (concat [x] next)]
      (if (every? #(simple-test-expr? &env %)
            (map #(cljs.analyzer/analyze &env %) forms))
        (let [or-str (->> (repeat (count forms) "(~{})")
                        (interpose " || ")
                        (apply core/str))]
          (bool-expr `(~'js* ~or-str ~@forms)))
        `(let [or# ~x]
           (if or# or# (or ~@next)))))))

(defmacro nil? [x]
  `(coercive-= ~x nil))

;; internal - do not use.
(defmacro coercive-not [x]
  (bool-expr (core/list 'js* "(!~{})" x)))

;; internal - do not use.
(defmacro coercive-not= [x y]
  (bool-expr (core/list 'js* "(~{} != ~{})" x y)))

;; internal - do not use.
(defmacro coercive-= [x y]
  (bool-expr (core/list 'js* "(~{} == ~{})" x y)))

;; internal - do not use.
(defmacro coercive-boolean [x]
  (with-meta (core/list 'js* "~{}" x)
    {:tag 'boolean}))

;; internal - do not use.
(defmacro truth_ [x]
  (assert (clojure.core/symbol? x) "x is substituted twice")
  (core/list 'js* "(~{} != null && ~{} !== false)" x x))

;; internal - do not use
(defmacro js-arguments []
  (core/list 'js* "arguments"))

(defmacro js-delete [obj key]
  (core/list 'js* "delete ~{}[~{}]" obj key))

(defmacro js-in [key obj]
  (core/list 'js* "~{} in ~{}" key obj))

(defmacro js-debugger
  "Emit JavaScript \"debugger;\" statement."
  []
  (core/list 'js* "debugger;"))

(defmacro true? [x]
  (bool-expr (core/list 'js* "~{} === true" x)))

(defmacro false? [x]
  (bool-expr (core/list 'js* "~{} === false" x)))

(defmacro string? [x]
  (bool-expr (core/list 'js* "typeof ~{} === 'string'" x)))

;; TODO: x must be a symbol, not an arbitrary expression
(defmacro exists?
  "Return true if argument exists, analogous to usage of typeof operator
   in JavaScript."
  [x]
  (bool-expr
    (core/list 'js* "typeof ~{} !== 'undefined'"
      (vary-meta x assoc :cljs.analyzer/no-resolve true))))

(defmacro undefined?
  "Return true if argument is identical to the JavaScript undefined value."
  [x]
  (bool-expr (core/list 'js* "(void 0 === ~{})" x)))

(defmacro identical? [a b]
  (bool-expr (core/list 'js* "(~{} === ~{})" a b)))

(defmacro instance? [t o]
  ;; Google Closure warns about some references to RegExp, so
  ;; (instance? RegExp ...) needs to be inlined, but the expansion
  ;; should preserve the order of argument evaluation.
  (bool-expr (if (clojure.core/symbol? t)
               (core/list 'js* "(~{} instanceof ~{})" o t)
               `(let [t# ~t o# ~o]
                  (~'js* "(~{} instanceof ~{})" o# t#)))))

(defmacro number? [x]
  (bool-expr (core/list 'js* "typeof ~{} === 'number'" x)))

(defmacro symbol? [x]
  (bool-expr `(instance? Symbol ~x)))

(defmacro keyword? [x]
  (bool-expr `(instance? Keyword ~x)))

(defmacro aget
  ([a i]
     (core/list 'js* "(~{}[~{}])" a i))
  ([a i & idxs]
     (let [astr (apply core/str (repeat (count idxs) "[~{}]"))]
      `(~'js* ~(core/str "(~{}[~{}]" astr ")") ~a ~i ~@idxs))))

(defmacro aset
  ([a i v]
    (core/list 'js* "(~{}[~{}] = ~{})" a i v))
  ([a idx idx2 & idxv]
    (let [n    (core/dec (count idxv))
          astr (apply core/str (repeat n "[~{}]"))]
      `(~'js* ~(core/str "(~{}[~{}][~{}]" astr " = ~{})") ~a ~idx ~idx2 ~@idxv))))

(defmacro ^::ana/numeric +
  ([] 0)
  ([x] x)
  ([x y] (core/list 'js* "(~{} + ~{})" x y))
  ([x y & more] `(+ (+ ~x ~y) ~@more)))

(defmacro byte [x] x)
(defmacro short [x] x)
(defmacro float [x] x)
(defmacro double [x] x)

(defmacro unchecked-byte [x] x)
(defmacro unchecked-char [x] x)
(defmacro unchecked-short [x] x)
(defmacro unchecked-float [x] x)
(defmacro unchecked-double [x] x)

(defmacro ^::ana/numeric unchecked-add
  ([& xs] `(+ ~@xs)))

(defmacro ^::ana/numeric unchecked-add-int
  ([& xs] `(+ ~@xs)))

(defmacro ^::ana/numeric unchecked-dec
  ([x] `(dec ~x)))

(defmacro ^::ana/numeric unchecked-dec-int
  ([x] `(dec ~x)))

(defmacro ^::ana/numeric unchecked-divide-int
  ([& xs] `(/ ~@xs)))

(defmacro ^::ana/numeric unchecked-inc
  ([x] `(inc ~x)))

(defmacro ^::ana/numeric unchecked-inc-int
  ([x] `(inc ~x)))

(defmacro ^::ana/numeric unchecked-multiply
  ([& xs] `(* ~@xs)))

(defmacro ^::ana/numeric unchecked-multiply-int
  ([& xs] `(* ~@xs)))

(defmacro ^::ana/numeric unchecked-negate
  ([x] `(- ~x)))

(defmacro ^::ana/numeric unchecked-negate-int
  ([x] `(- ~x)))

(defmacro ^::ana/numeric unchecked-remainder-int
  ([x n] `(mod ~x ~n)))

(defmacro ^::ana/numeric unchecked-subtract
  ([& xs] `(- ~@xs)))

(defmacro ^::ana/numeric unchecked-subtract-int
  ([& xs] `(- ~@xs)))

(defmacro ^::ana/numeric -
  ([x] (core/list 'js* "(- ~{})" x))
  ([x y] (core/list 'js* "(~{} - ~{})" x y))
  ([x y & more] `(- (- ~x ~y) ~@more)))

(defmacro ^::ana/numeric *
  ([] 1)
  ([x] x)
  ([x y] (core/list 'js* "(~{} * ~{})" x y))
  ([x y & more] `(* (* ~x ~y) ~@more)))

(defmacro ^::ana/numeric /
  ([x] `(/ 1 ~x))
  ([x y] (core/list 'js* "(~{} / ~{})" x y))
  ([x y & more] `(/ (/ ~x ~y) ~@more)))

(defmacro ^::ana/numeric divide
  ([x] `(/ 1 ~x))
  ([x y] (core/list 'js* "(~{} / ~{})" x y))
  ([x y & more] `(/ (/ ~x ~y) ~@more)))

(defmacro ^::ana/numeric <
  ([x] true)
  ([x y] (bool-expr (core/list 'js* "(~{} < ~{})" x y)))
  ([x y & more] `(and (< ~x ~y) (< ~y ~@more))))

(defmacro ^::ana/numeric <=
  ([x] true)
  ([x y] (bool-expr (core/list 'js* "(~{} <= ~{})" x y)))
  ([x y & more] `(and (<= ~x ~y) (<= ~y ~@more))))

(defmacro ^::ana/numeric >
  ([x] true)
  ([x y] (bool-expr (core/list 'js* "(~{} > ~{})" x y)))
  ([x y & more] `(and (> ~x ~y) (> ~y ~@more))))

(defmacro ^::ana/numeric >=
  ([x] true)
  ([x y] (bool-expr (core/list 'js* "(~{} >= ~{})" x y)))
  ([x y & more] `(and (>= ~x ~y) (>= ~y ~@more))))

(defmacro ^::ana/numeric ==
  ([x] true)
  ([x y] (bool-expr (core/list 'js* "(~{} === ~{})" x y)))
  ([x y & more] `(and (== ~x ~y) (== ~y ~@more))))

(defmacro ^::ana/numeric dec [x]
  `(- ~x 1))

(defmacro ^::ana/numeric inc [x]
  `(+ ~x 1))

(defmacro ^::ana/numeric zero? [x]
  `(== ~x 0))

(defmacro ^::ana/numeric pos? [x]
  `(> ~x 0))

(defmacro ^::ana/numeric neg? [x]
  `(< ~x 0))

(defmacro ^::ana/numeric max
  ([x] x)
  ([x y] `(let [x# ~x, y# ~y]
            (~'js* "((~{} > ~{}) ? ~{} : ~{})" x# y# x# y#)))
  ([x y & more] `(max (max ~x ~y) ~@more)))

(defmacro ^::ana/numeric min
  ([x] x)
  ([x y] `(let [x# ~x, y# ~y]
            (~'js* "((~{} < ~{}) ? ~{} : ~{})" x# y# x# y#)))
  ([x y & more] `(min (min ~x ~y) ~@more)))

(defmacro ^::ana/numeric js-mod [num div]
  (core/list 'js* "(~{} % ~{})" num div))

(defmacro ^::ana/numeric bit-not [x]
  (core/list 'js* "(~ ~{})" x))

(defmacro ^::ana/numeric bit-and
  ([x y] (core/list 'js* "(~{} & ~{})" x y))
  ([x y & more] `(bit-and (bit-and ~x ~y) ~@more)))

;; internal do not use
(defmacro ^::ana/numeric unsafe-bit-and
  ([x y] (bool-expr (core/list 'js* "(~{} & ~{})" x y)))
  ([x y & more] `(unsafe-bit-and (unsafe-bit-and ~x ~y) ~@more)))

(defmacro ^::ana/numeric bit-or
  ([x y] (core/list 'js* "(~{} | ~{})" x y))
  ([x y & more] `(bit-or (bit-or ~x ~y) ~@more)))

(defmacro ^::ana/numeric int [x]
  `(bit-or ~x 0))

(defmacro ^::ana/numeric bit-xor
  ([x y] (core/list 'js* "(~{} ^ ~{})" x y))
  ([x y & more] `(bit-xor (bit-xor ~x ~y) ~@more)))

(defmacro ^::ana/numeric bit-and-not
  ([x y] (core/list 'js* "(~{} & ~~{})" x y))
  ([x y & more] `(bit-and-not (bit-and-not ~x ~y) ~@more)))

(defmacro ^::ana/numeric bit-clear [x n]
  (core/list 'js* "(~{} & ~(1 << ~{}))" x n))

(defmacro ^::ana/numeric bit-flip [x n]
  (core/list 'js* "(~{} ^ (1 << ~{}))" x n))

(defmacro bit-test [x n]
  (bool-expr (core/list 'js* "((~{} & (1 << ~{})) != 0)" x n)))

(defmacro ^::ana/numeric bit-shift-left [x n]
  (core/list 'js* "(~{} << ~{})" x n))

(defmacro ^::ana/numeric bit-shift-right [x n]
  (core/list 'js* "(~{} >> ~{})" x n))

(defmacro ^::ana/numeric bit-shift-right-zero-fill [x n]
  (core/list 'js* "(~{} >>> ~{})" x n))

(defmacro ^::ana/numeric unsigned-bit-shift-right [x n]
  (core/list 'js* "(~{} >>> ~{})" x n))

(defmacro ^::ana/numeric bit-set [x n]
  (core/list 'js* "(~{} | (1 << ~{}))" x n))

;; internal
(defmacro mask [hash shift]
  (core/list 'js* "((~{} >>> ~{}) & 0x01f)" hash shift))

;; internal
(defmacro bitpos [hash shift]
  (core/list 'js* "(1 << ~{})" `(mask ~hash ~shift)))

;; internal
(defmacro caching-hash [coll hash-fn hash-key]
  (assert (clojure.core/symbol? hash-key) "hash-key is substituted twice")
  `(let [h# ~hash-key]
     (if-not (nil? h#)
       h#
       (let [h# (~hash-fn ~coll)]
         (set! ~hash-key h#)
         h#))))

;;; internal -- reducers-related macros

(defn- do-curried
  [name doc meta args body]
  (let [cargs (vec (butlast args))]
    `(defn ~name ~doc ~meta
       (~cargs (fn [x#] (~name ~@cargs x#)))
       (~args ~@body))))

(defmacro ^:private defcurried
  "Builds another arity of the fn that returns a fn awaiting the last
  param"
  [name doc meta args & body]
  (do-curried name doc meta args body))

(defn- do-rfn [f1 k fkv]
  `(fn
     ([] (~f1))
     ~(clojure.walk/postwalk
       #(if (sequential? %)
          ((if (vector? %) vec identity)
           (core/remove #{k} %))
          %)
       fkv)
     ~fkv))

(defmacro ^:private rfn
  "Builds 3-arity reducing fn given names of wrapped fn and key, and k/v impl."
  [[f1 k] fkv]
  (do-rfn f1 k fkv))

;;; end of reducers macros

(defn- protocol-prefix [psym]
  (core/str (-> (core/str psym) (.replace \. \$) (.replace \/ \$)) "$"))

(def #^:private base-type
     {nil "null"
      'object "object"
      'string "string"
      'number "number"
      'array "array"
      'function "function"
      'boolean "boolean"
      'default "_"})

(def #^:private js-base-type
     {'js/Boolean "boolean"
      'js/String "string"
      'js/Array "array"
      'js/Object "object"
      'js/Number "number"
      'js/Function "function"})

(defmacro reify
  "reify is a macro with the following structure:

 (reify options* specs*)

  Currently there are no options.

  Each spec consists of the protocol name followed by zero
  or more method bodies:

  protocol
  (methodName [args+] body)*

  Methods should be supplied for all methods of the desired
  protocol(s). You can also define overrides for Object methods. Note that
  the first parameter must be supplied to correspond to the target object
  ('this' in JavaScript parlance). Note also that recur calls
  to the method head should *not* pass the target object, it will be supplied
  automatically and can not be substituted.

  recur works to method heads The method bodies of reify are lexical
  closures, and can refer to the surrounding local scope:

  (str (let [f \"foo\"]
       (reify Object
         (toString [this] f))))
  == \"foo\"

  (seq (let [f \"foo\"]
       (reify ISeqable
         (-seq [this] (-seq f)))))
  == (\\f \\o \\o))

  reify always implements IMeta and IWithMeta and transfers meta
  data of the form to the created object.

  (meta ^{:k :v} (reify Object (toString [this] \"foo\")))
  == {:k :v}"
  [& impls]
  (let [t        (with-meta (gensym "t") {:anonymous true})
        meta-sym (gensym "meta")
        this-sym (gensym "_")
        locals   (keys (:locals &env))
        ns       (-> &env :ns :name)
        munge    cljs.compiler/munge]
    `(do
       (when-not (exists? ~(symbol (core/str ns) (core/str t)))
         (deftype ~t [~@locals ~meta-sym]
           IWithMeta
           (~'-with-meta [~this-sym ~meta-sym]
             (new ~t ~@locals ~meta-sym))
           IMeta
           (~'-meta [~this-sym] ~meta-sym)
           ~@impls))
       (new ~t ~@locals ~(ana/elide-reader-meta (meta &form))))))

(defmacro specify!
  "Identical to reify but mutates its first argument."
  [expr & impls]
  (let [x (with-meta (gensym "x") {:extend :instance})]
    `(let [~x ~expr]
       (extend-type ~x ~@impls)
       ~x)))

(defmacro specify
  "Identical to specify but does not mutate its first argument. The first
  argument must be an ICloneable instance."
  [expr & impls]
  `(cljs.core/specify! (cljs.core/clone ~expr)
     ~@impls))

(defmacro ^:private js-this []
  (core/list 'js* "this"))

(defmacro this-as
  "Defines a scope where JavaScript's implicit \"this\" is bound to the name provided."
  [name & body]
  `(let [~name (js-this)]
     ~@body))

(defn- to-property [sym]
  (symbol (core/str "-" sym)))

(defn- warn-and-update-protocol [p type env]
  (when-not (= 'Object p)
    (if-let [var (cljs.analyzer/resolve-existing-var (dissoc env :locals) p)]
      (do
        (when-not (:protocol-symbol var)
          (cljs.analyzer/warning :invalid-protocol-symbol env {:protocol p}))
        (when (core/and (:protocol-deprecated cljs.analyzer/*cljs-warnings*)
                (-> var :deprecated)
                (not (-> p meta :deprecation-nowarn)))
          (cljs.analyzer/warning :protocol-deprecated env {:protocol p}))
        (when (:protocol-symbol var)
          (swap! env/*compiler* update-in [:cljs.analyzer/namespaces]
            (fn [ns]
              (update-in ns [(:ns var) :defs (symbol (name p)) :impls]
                conj type)))))
      (when (:undeclared cljs.analyzer/*cljs-warnings*)
        (cljs.analyzer/warning :undeclared-protocol-symbol env {:protocol p})))))

(defn- resolve-var [env sym]
  (let [ret (-> (dissoc env :locals)
              (cljs.analyzer/resolve-var sym)
              :name)]
    (assert ret (core/str "Can't resolve: " sym))
    ret))

(defn- ->impl-map [impls]
  (loop [ret {} s impls]
    (if (seq s)
      (recur (assoc ret (first s) (take-while seq? (next s)))
        (drop-while seq? (next s)))
      ret)))

(defn- base-assign-impls [env resolve tsym type [p sigs]]
  (warn-and-update-protocol p tsym env)
  (let [psym       (resolve p)
        pfn-prefix (subs (core/str psym) 0
                     (clojure.core/inc (.indexOf (core/str psym) "/")))]
    (cons `(aset ~psym ~type true)
      (map (fn [[f & meths :as form]]
             `(aset ~(symbol (core/str pfn-prefix f))
                ~type ~(with-meta `(fn ~@meths) (meta form))))
        sigs))))

(core/defmulti extend-prefix (fn [tsym sym] (-> tsym meta :extend)))

(core/defmethod extend-prefix :instance
  [tsym sym] `(.. ~tsym ~(to-property sym)))

(core/defmethod extend-prefix :default
  [tsym sym] `(.. ~tsym -prototype ~(to-property sym)))

(defn- adapt-obj-params [type [[this & args :as sig] & body]]
  (core/list (vec args)
    (list* 'this-as (vary-meta this assoc :tag type) body)))

(defn- adapt-ifn-params [type [[this & args :as sig] & body]]
  (let [self-sym (with-meta 'self__ {:tag type})]
    `(~(vec (cons self-sym args))
       (this-as ~self-sym
         (let [~this ~self-sym]
           ~@body)))))

;; for IFn invoke implementations, we need to drop first arg
(defn- adapt-ifn-invoke-params [type [[this & args :as sig] & body]]
  `(~(vec args)
     (this-as ~(vary-meta this assoc :tag type)
       ~@body)))

(defn- adapt-proto-params [type [[this & args :as sig] & body]]
  `(~(vec (cons (vary-meta this assoc :tag type) args))
     (this-as ~this
       ~@body)))

(defn- add-obj-methods [type type-sym sigs]
  (map (fn [[f & meths :as form]]
         (let [[f meths] (if (vector? (first meths))
                           [f [(rest form)]]
                           [f meths])]
           `(set! ~(extend-prefix type-sym f)
             ~(with-meta `(fn ~@(map #(adapt-obj-params type %) meths)) (meta form)))))
    sigs))

(defn- ifn-invoke-methods [type type-sym [f & meths :as form]]
  (map
    (fn [meth]
      (let [arity (count (first meth))]
        `(set! ~(extend-prefix type-sym (symbol (core/str "cljs$core$IFn$_invoke$arity$" arity)))
           ~(with-meta `(fn ~meth) (meta form)))))
    (map #(adapt-ifn-invoke-params type %) meths)))

(defn- add-ifn-methods [type type-sym [f & meths :as form]]
  (let [meths    (map #(adapt-ifn-params type %) meths)
        this-sym (with-meta 'self__ {:tag type})
        argsym   (gensym "args")]
    (concat
      [`(set! ~(extend-prefix type-sym 'call) ~(with-meta `(fn ~@meths) (meta form)))
       `(set! ~(extend-prefix type-sym 'apply)
          ~(with-meta
             `(fn ~[this-sym argsym]
                (this-as ~this-sym
                  (.apply (.-call ~this-sym) ~this-sym
                    (.concat (array ~this-sym) (aclone ~argsym)))))
             (meta form)))]
      (ifn-invoke-methods type type-sym form))))

(defn- add-proto-methods* [pprefix type type-sym [f & meths :as form]]
  (let [pf (core/str pprefix f)]
    (if (vector? (first meths))
      ;; single method case
      (let [meth meths]
        [`(set! ~(extend-prefix type-sym (core/str pf "$arity$" (count (first meth))))
            ~(with-meta `(fn ~@(adapt-proto-params type meth)) (meta form)))])
      (map (fn [[sig & body :as meth]]
             `(set! ~(extend-prefix type-sym (core/str pf "$arity$" (count sig)))
                ~(with-meta `(fn ~(adapt-proto-params type meth)) (meta form))))
        meths))))

(defn- proto-assign-impls [env resolve type-sym type [p sigs]]
  (warn-and-update-protocol p type env)
  (let [psym      (resolve p)
        pprefix   (protocol-prefix psym)
        skip-flag (set (-> type-sym meta :skip-protocol-flag))]
    (if (= p 'Object)
      (add-obj-methods type type-sym sigs)
      (concat
        (when-not (skip-flag psym)
          [`(set! ~(extend-prefix type-sym pprefix) true)])
        (mapcat
          (fn [sig]
            (if (= psym 'cljs.core/IFn)
              (add-ifn-methods type type-sym sig)
              (add-proto-methods* pprefix type type-sym sig)))
          sigs)))))

(defn- validate-impl-sigs [env p method]
  (when-not (= p 'Object)
    (let [var (ana/resolve-var (dissoc env :locals) p)
          minfo (-> var :protocol-info :methods)
          [fname sigs] (if (core/vector? (second method))
                         [(first method) [(second method)]]
                         [(first method) (map first (rest method))])
          decmeths (core/get minfo fname ::not-found)]
      (when (= decmeths ::not-found)
        (ana/warning :protocol-invalid-method env {:protocol p :fname fname :no-such-method true}))
      (loop [sigs sigs seen #{}]
        (when (seq sigs)
          (let [sig (first sigs)
                c   (count sig)]
            (when (contains? seen c)
              (ana/warning :protocol-duped-method env {:protocol p :fname fname}))
            (when (core/and (not= decmeths ::not-found) (not (some #{c} (map count decmeths))))
              (ana/warning :protocol-invalid-method env {:protocol p :fname fname :invalid-arity c}))
            (recur (next sigs) (conj seen c))))))))

(defn- validate-impls [env impls]
  (loop [protos #{} impls impls]
    (when (seq impls)
      (let [proto   (first impls)
            methods (take-while seq? (next impls))
            impls   (drop-while seq? (next impls))]
        (when (contains? protos proto)
          (ana/warning :protocol-multiple-impls env {:protocol proto}))
        (loop [seen #{} methods methods]
          (when (seq methods)
            (let [[fname :as method] (first methods)]
              (when (contains? seen fname)
                (ana/warning :extend-type-invalid-method-shape env
                  {:protocol proto :method fname}))
              (validate-impl-sigs env proto method)
              (recur (conj seen fname) (next methods)))))
        (recur (conj protos proto) impls)))))

(defmacro extend-type
  "Extend a type to a series of protocols. Useful when you are
   supplying the definitions explicitly inline. Propagates the
   type as a type hint on the first argument of all fns.

  (extend-type MyType
    ICounted
    (-count [c] ...)
    Foo
    (bar [x y] ...)
    (baz ([x] ...) ([x y & zs] ...))"
  [type-sym & impls]
  (let [env &env
        _ (validate-impls env impls)
        resolve (partial resolve-var env)
        impl-map (->impl-map impls)
        [type assign-impls] (if-let [type (base-type type-sym)]
                              [type base-assign-impls]
                              [(resolve type-sym) proto-assign-impls])]
    (when (core/and (:extending-base-js-type cljs.analyzer/*cljs-warnings*)
                    (js-base-type type-sym))
      (cljs.analyzer/warning :extending-base-js-type env
          {:current-symbol type-sym :suggested-symbol (js-base-type type-sym)}))
    `(do ~@(mapcat #(assign-impls env resolve type-sym type %) impl-map))))

(defn- prepare-protocol-masks [env impls]
  (let [resolve  (partial resolve-var env)
        impl-map (->impl-map impls)
        fpp-pbs  (seq
                   (keep fast-path-protocols
                     (map resolve
                       (keys impl-map))))]
    (if fpp-pbs
      (let [fpps  (into #{}
                    (filter (partial contains? fast-path-protocols)
                      (map resolve (keys impl-map))))
            parts (as-> (group-by first fpp-pbs) parts
                    (into {}
                      (map (juxt key (comp (partial map peek) val))
                        parts))
                    (into {}
                      (map (juxt key (comp (partial reduce core/bit-or) val))
                        parts)))]
        [fpps (reduce (fn [ps p] (update-in ps [p] (fnil identity 0)))
                parts
                (range fast-path-protocol-partitions-count))]))))

(defn- annotate-specs [annots v [f sigs]]
  (conj v
    (vary-meta (cons f (map #(cons (second %) (nnext %)) sigs))
      merge annots)))

(core/defn dt->et
  ([type specs fields]
    (dt->et type specs fields false))
  ([type specs fields inline]
    (let [annots {:cljs.analyzer/type type
                  :cljs.analyzer/protocol-impl true
                  :cljs.analyzer/protocol-inline inline}]
      (loop [ret [] specs specs]
        (if (seq specs)
          (let [p     (first specs)
                ret   (-> (conj ret p)
                        (into (reduce (partial annotate-specs annots) []
                                (group-by first (take-while seq? (next specs))))))
                specs (drop-while seq? (next specs))]
            (recur ret specs))
          ret)))))

(defn- collect-protocols [impls env]
  (->> impls
      (filter core/symbol?)
      (map #(:name (cljs.analyzer/resolve-var (dissoc env :locals) %)))
      (into #{})))

(defn- build-positional-factory
  [rsym rname fields]
  (let [fn-name (with-meta (symbol (core/str '-> rsym))
                  (assoc (meta rsym) :factory :positional))
        field-values (if (-> rsym meta :internal-ctor) (conj fields nil nil nil) fields)]
    `(defn ~fn-name
       [~@fields]
       (new ~rname ~@field-values))))

(defn- validate-fields
  [case name fields]
  (when-not (vector? fields)
    (throw (AssertionError. (core/str case " " name ", no fields vector given.")))))

(defmacro deftype
  "(deftype name [fields*]  options* specs*)

  Currently there are no options.

  Each spec consists of a protocol or interface name followed by zero
  or more method bodies:

  protocol-or-Object
  (methodName [args*] body)*

  The type will have the (by default, immutable) fields named by
  fields, which can have type hints. Protocols and methods
  are optional. The only methods that can be supplied are those
  declared in the protocols/interfaces.  Note that method bodies are
  not closures, the local environment includes only the named fields,
  and those fields can be accessed directly. Fields can be qualified
  with the metadata :mutable true at which point (set! afield aval) will be
  supported in method bodies. Note well that mutable fields are extremely
  difficult to use correctly, and are present only to facilitate the building
  of higherlevel constructs, such as ClojureScript's reference types, in
  ClojureScript itself. They are for experts only - if the semantics and
  implications of :mutable are not immediately apparent to you, you should not
  be using them.

  Method definitions take the form:

  (methodname [args*] body)

  The argument and return types can be hinted on the arg and
  methodname symbols. If not supplied, they will be inferred, so type
  hints should be reserved for disambiguation.

  Methods should be supplied for all methods of the desired
  protocol(s). You can also define overrides for methods of Object. Note that
  a parameter must be supplied to correspond to the target object
  ('this' in JavaScript parlance). Note also that recur calls to the method
  head should *not* pass the target object, it will be supplied
  automatically and can not be substituted.

  In the method bodies, the (unqualified) name can be used to name the
  class (for calls to new, instance? etc).

  One constructor will be defined, taking the designated fields.  Note
  that the field names __meta and __extmap are currently reserved and
  should not be used when defining your own types.

  Given (deftype TypeName ...), a factory function called ->TypeName
  will be defined, taking positional parameters for the fields"
  [t fields & impls]
  (validate-fields "deftype" t fields)
  (let [env &env
        r (:name (cljs.analyzer/resolve-var (dissoc env :locals) t))
        [fpps pmasks] (prepare-protocol-masks env impls)
        protocols (collect-protocols impls env)
        t (vary-meta t assoc
            :protocols protocols
            :skip-protocol-flag fpps) ]
    `(do
       (deftype* ~t ~fields ~pmasks
         ~(if (seq impls)
            `(extend-type ~t ~@(dt->et t impls fields))))
       (set! (.-cljs$lang$type ~t) true)
       (set! (.-cljs$lang$ctorStr ~t) ~(core/str r))
       (set! (.-cljs$lang$ctorPrWriter ~t) (fn [this# writer# opt#] (-write writer# ~(core/str r))))

       ~(build-positional-factory t r fields)
       ~t)))

(defn- emit-defrecord
  "Do not use this directly - use defrecord"
  [env tagname rname fields impls]
  (let [hinted-fields fields
        fields (vec (map #(with-meta % nil) fields))
        base-fields fields
        pr-open (core/str "#" (.getNamespace rname) "." (.getName rname) "{")
        fields (conj fields '__meta '__extmap (with-meta '__hash {:mutable true}))]
    (let [gs (gensym)
          ksym (gensym "k")
          impls (concat
                 impls
                 ['IRecord
                  'ICloneable
                  `(~'-clone [this#] (new ~tagname ~@fields))
                  'IHash
                  `(~'-hash [this#] (caching-hash this# ~'hash-imap ~'__hash))
                  'IEquiv
                  `(~'-equiv [this# other#]
                             (if (and other#
                                      (identical? (.-constructor this#)
                                                  (.-constructor other#))
                                      (equiv-map this# other#))
                               true
                               false))
                  'IMeta
                  `(~'-meta [this#] ~'__meta)
                  'IWithMeta
                  `(~'-with-meta [this# ~gs] (new ~tagname ~@(replace {'__meta gs} fields)))
                  'ILookup
                  `(~'-lookup [this# k#] (-lookup this# k# nil))
                  `(~'-lookup [this# ~ksym else#]
                              (case ~ksym
                                ~@(mapcat (fn [f] [(keyword f) f]) base-fields)
                                (get ~'__extmap ~ksym else#)))
                  'ICounted
                  `(~'-count [this#] (+ ~(count base-fields) (count ~'__extmap)))
                  'ICollection
                  `(~'-conj [this# entry#]
                            (if (vector? entry#)
                              (-assoc this# (-nth entry# 0) (-nth entry# 1))
                              (reduce -conj
                                      this#
                                      entry#)))
                  'IAssociative
                  `(~'-assoc [this# k# ~gs]
                             (condp keyword-identical? k#
                               ~@(mapcat (fn [fld]
                                           [(keyword fld) (list* `new tagname (replace {fld gs '__hash nil} fields))])
                                         base-fields)
                               (new ~tagname ~@(remove #{'__extmap '__hash} fields) (assoc ~'__extmap k# ~gs) nil)))
                  'IMap
                  `(~'-dissoc [this# k#] (if (contains? #{~@(map keyword base-fields)} k#)
                                           (dissoc (with-meta (into {} this#) ~'__meta) k#)
                                           (new ~tagname ~@(remove #{'__extmap '__hash} fields)
                                                (not-empty (dissoc ~'__extmap k#))
                                                nil)))
                  'ISeqable
                  `(~'-seq [this#] (seq (concat [~@(map #(core/list `vector (keyword %) %) base-fields)]
                                                ~'__extmap)))

                  'IPrintWithWriter
                  `(~'-pr-writer [this# writer# opts#]
                                 (let [pr-pair# (fn [keyval#] (pr-sequential-writer writer# pr-writer "" " " "" opts# keyval#))]
                                   (pr-sequential-writer
                                    writer# pr-pair# ~pr-open ", " "}" opts#
                                    (concat [~@(map #(core/list `vector (keyword %) %) base-fields)]
                                            ~'__extmap))))
                  ])
          [fpps pmasks] (prepare-protocol-masks env impls)
          protocols (collect-protocols impls env)
          tagname (vary-meta tagname assoc
                    :protocols protocols
                    :skip-protocol-flag fpps)]
      `(do
         (~'defrecord* ~tagname ~hinted-fields ~pmasks
           (extend-type ~tagname ~@(dt->et tagname impls fields true)))))))

(defn- build-map-factory [rsym rname fields]
  (let [fn-name (with-meta (symbol (core/str 'map-> rsym))
                  (assoc (meta rsym) :factory :map))
        ms (gensym)
        ks (map keyword fields)
        getters (map (fn [k] `(~k ~ms)) ks)]
    `(defn ~fn-name [~ms]
       (new ~rname ~@getters nil (dissoc ~ms ~@ks) nil))))

(defmacro defrecord
  "(defrecord name [fields*]  options* specs*)

  Currently there are no options.

  Each spec consists of a protocol or interface name followed by zero
  or more method bodies:

  protocol-or-Object
  (methodName [args*] body)*

  The record will have the (immutable) fields named by
  fields, which can have type hints. Protocols and methods
  are optional. The only methods that can be supplied are those
  declared in the protocols.  Note that method bodies are
  not closures, the local environment includes only the named fields,
  and those fields can be accessed directly.

  Method definitions take the form:

  (methodname [args*] body)

  The argument and return types can be hinted on the arg and
  methodname symbols. If not supplied, they will be inferred, so type
  hints should be reserved for disambiguation.

  Methods should be supplied for all methods of the desired
  protocol(s). You can also define overrides for
  methods of Object. Note that a parameter must be supplied to
  correspond to the target object ('this' in JavaScript parlance). Note also
  that recur calls to the method head should *not* pass the target object, it
  will be supplied automatically and can not be substituted.

  In the method bodies, the (unqualified) name can be used to name the
  class (for calls to new, instance? etc).

  The type will have implementations of several ClojureScript
  protocol generated automatically: IMeta/IWithMeta (metadata support) and
  IMap, etc.

  In addition, defrecord will define type-and-value-based =,
  and will define ClojureScript IHash and IEquiv.

  Two constructors will be defined, one taking the designated fields
  followed by a metadata map (nil for none) and an extension field
  map (nil for none), and one taking only the fields (using nil for
  meta and extension fields). Note that the field names __meta
  and __extmap are currently reserved and should not be used when
  defining your own records.

  Given (defrecord TypeName ...), two factory functions will be
  defined: ->TypeName, taking positional parameters for the fields,
  and map->TypeName, taking a map of keywords to field values."
  [rsym fields & impls]
  (validate-fields "defrecord" rsym fields)
  (let [rsym (vary-meta rsym assoc :internal-ctor true)
        r    (vary-meta
               (:name (cljs.analyzer/resolve-var (dissoc &env :locals) rsym))
               assoc :internal-ctor true)]
    `(let []
       ~(emit-defrecord &env rsym r fields impls)
       (set! (.-cljs$lang$type ~r) true)
       (set! (.-cljs$lang$ctorPrSeq ~r) (fn [this#] (core/list ~(core/str r))))
       (set! (.-cljs$lang$ctorPrWriter ~r) (fn [this# writer#] (-write writer# ~(core/str r))))
       ~(build-positional-factory rsym r fields)
       ~(build-map-factory rsym r fields)
       ~r)))

(defmacro defprotocol
  "A protocol is a named set of named methods and their signatures:

  (defprotocol AProtocolName
    ;optional doc string
    \"A doc string for AProtocol abstraction\"

  ;method signatures
    (bar [this a b] \"bar docs\")
    (baz [this a] [this a b] [this a b c] \"baz docs\"))

  No implementations are provided. Docs can be specified for the
  protocol overall and for each method. The above yields a set of
  polymorphic functions and a protocol object. All are
  namespace-qualified by the ns enclosing the definition The resulting
  functions dispatch on the type of their first argument, which is
  required and corresponds to the implicit target object ('this' in
  JavaScript parlance). defprotocol is dynamic, has no special compile-time
  effect, and defines no new types.

  (defprotocol P
    (foo [this])
    (bar-me [this] [this y]))

  (deftype Foo [a b c]
    P
    (foo [this] a)
    (bar-me [this] b)
    (bar-me [this y] (+ c y)))

  (bar-me (Foo. 1 2 3) 42)
  => 45

  (foo
    (let [x 42]
      (reify P
        (foo [this] 17)
        (bar-me [this] x)
        (bar-me [this y] x))))
  => 17"
  [psym & doc+methods]
  (let [p (:name (cljs.analyzer/resolve-var (dissoc &env :locals) psym))
        [doc methods] (if (core/string? (first doc+methods))
                        [(first doc+methods) (next doc+methods)]
                        [nil doc+methods])
        psym (vary-meta psym assoc
               :doc doc
               :protocol-symbol true)
        ns-name (-> &env :ns :name)
        fqn (fn [n] (symbol (core/str ns-name "." n)))
        prefix (protocol-prefix p)
        _ (core/doseq [[mname & arities] methods]
            (when (some #{0} (map count (filter vector? arities)))
              (throw (Exception.
                       (core/str "Invalid protocol, " psym
                                 " defines method " mname " with arity 0")))))
        expand-sig (fn [fname slot sig]
                     `(~sig
                       (if (and ~(first sig) (. ~(first sig) ~(symbol (core/str "-" slot)))) ;; Property access needed here.
                         (. ~(first sig) ~slot ~@sig)
                         (let [x# (if (nil? ~(first sig)) nil ~(first sig))]
                           ((or
                             (aget ~(fqn fname) (goog/typeOf x#))
                             (aget ~(fqn fname) "_")
                             (throw (missing-protocol
                                     ~(core/str psym "." fname) ~(first sig))))
                            ~@sig)))))
        psym   (vary-meta psym assoc-in [:protocol-info :methods]
                 (into {}
                   (map
                     (fn [[fname & sigs]]
                       (let [doc (as-> (last sigs) doc
                                   (when (core/string? doc) doc))
                             sigs (take-while vector? sigs)]
                         [(vary-meta fname assoc :doc doc)
                          (vec sigs)]))
                     methods)))
        method (fn [[fname & sigs]]
                 (let [doc (as-> (last sigs) doc
                             (when (core/string? doc) doc))
                       sigs (take-while vector? sigs)
                       slot (symbol (core/str prefix (name fname)))
                       fname (vary-meta fname assoc
                               :protocol p
                               :doc doc)]
                   `(defn ~fname
                      ~@(map (fn [sig]
                               (expand-sig fname
                                 (symbol (core/str slot "$arity$" (count sig)))
                                 sig))
                          sigs))))]
    `(do
       (set! ~'*unchecked-if* true)
       (def ~psym (js-obj))
       ~@(map method methods)
       (set! ~'*unchecked-if* false))))

(defmacro implements?
  "EXPERIMENTAL"
  [psym x]
  (let [p          (:name
                    (cljs.analyzer/resolve-var
                      (dissoc &env :locals) psym))
        prefix     (protocol-prefix p)
        xsym       (bool-expr (gensym))
        [part bit] (fast-path-protocols p)
        msym       (symbol
                      (core/str "-cljs$lang$protocol_mask$partition" part "$"))]
    `(let [~xsym ~x]
       (if ~xsym
         (let [bit# ~(if bit `(unsafe-bit-and (. ~xsym ~msym) ~bit))]
           (if (or bit#
                 ~(bool-expr `(. ~xsym ~(symbol (core/str "-" prefix)))))
             true
             false))
         false))))

(defmacro satisfies?
  "Returns true if x satisfies the protocol"
  [psym x]
  (let [p          (:name
                     (cljs.analyzer/resolve-var
                       (dissoc &env :locals) psym))
         prefix     (protocol-prefix p)
         xsym       (bool-expr (gensym))
         [part bit] (fast-path-protocols p)
         msym       (symbol
                      (core/str "-cljs$lang$protocol_mask$partition" part "$"))]
    `(let [~xsym ~x]
       (if ~xsym
         (let [bit# ~(if bit `(unsafe-bit-and (. ~xsym ~msym) ~bit))]
           (if (or bit#
                 ~(bool-expr `(. ~xsym ~(symbol (core/str "-" prefix)))))
             true
             (if (coercive-not (. ~xsym ~msym))
               (cljs.core/native-satisfies? ~psym ~xsym)
               false)))
         (cljs.core/native-satisfies? ~psym ~xsym)))))

(defmacro lazy-seq
  "Takes a body of expressions that returns an ISeq or nil, and yields
  a ISeqable object that will invoke the body only the first time seq
  is called, and will cache the result and return it on all subsequent
  seq calls."
  [& body]
  `(new cljs.core/LazySeq nil (fn [] ~@body) nil nil))

(defmacro delay [& body]
  "Takes a body of expressions and yields a Delay object that will
  invoke the body only the first time it is forced (with force or deref/@), and
  will cache the result and return it on all subsequent force
  calls."
  `(new cljs.core/Delay (fn [] ~@body) nil))

(defmacro with-redefs
  "binding => var-symbol temp-value-expr

  Temporarily redefines vars while executing the body.  The
  temp-value-exprs will be evaluated and each resulting value will
  replace in parallel the root value of its var.  After the body is
  executed, the root values of all the vars will be set back to their
  old values. Useful for mocking out functions during testing."
  [bindings & body]
  (let [names (take-nth 2 bindings)
        vals (take-nth 2 (drop 1 bindings))
        tempnames (map (comp gensym name) names)
        binds (map core/vector names vals)
        resets (reverse (map core/vector names tempnames))
        bind-value (fn [[k v]] (core/list 'set! k v))]
    `(let [~@(interleave tempnames names)]
       ~@(map bind-value binds)
       (try
         ~@body
        (finally
          ~@(map bind-value resets))))))

(defmacro binding
  "binding => var-symbol init-expr

  Creates new bindings for the (already-existing) vars, with the
  supplied initial values, executes the exprs in an implicit do, then
  re-establishes the bindings that existed before.  The new bindings
  are made in parallel (unlike let); all init-exprs are evaluated
  before the vars are bound to their new values."
  [bindings & body]
  (let [names (take-nth 2 bindings)]
    (cljs.analyzer/confirm-bindings &env names)
    `(with-redefs ~bindings ~@body)))

(defmacro condp
  "Takes a binary predicate, an expression, and a set of clauses.
  Each clause can take the form of either:

  test-expr result-expr

  test-expr :>> result-fn

  Note :>> is an ordinary keyword.

  For each clause, (pred test-expr expr) is evaluated. If it returns
  logical true, the clause is a match. If a binary clause matches, the
  result-expr is returned, if a ternary clause matches, its result-fn,
  which must be a unary function, is called with the result of the
  predicate as its argument, the result of that call being the return
  value of condp. A single default expression can follow the clauses,
  and its value will be returned if no clause matches. If no default
  expression is provided and no clause matches, an
  IllegalArgumentException is thrown."
  {:added "1.0"}

  [pred expr & clauses]
  (let [gpred (gensym "pred__")
        gexpr (gensym "expr__")
        emit (fn emit [pred expr args]
               (let [[[a b c :as clause] more]
                       (split-at (if (= :>> (second args)) 3 2) args)
                       n (count clause)]
                 (core/cond
                  (= 0 n) `(throw (js/Error. (core/str "No matching clause: " ~expr)))
                  (= 1 n) a
                  (= 2 n) `(if (~pred ~a ~expr)
                             ~b
                             ~(emit pred expr more))
                  :else `(if-let [p# (~pred ~a ~expr)]
                           (~c p#)
                           ~(emit pred expr more)))))
        gres (gensym "res__")]
    `(let [~gpred ~pred
           ~gexpr ~expr]
       ~(emit gpred gexpr clauses))))

(defn- assoc-test [m test expr env]
  (if (contains? m test)
    (throw
      (clojure.core/IllegalArgumentException.
        (core/str "Duplicate case test constant '"
          test "'"
          (when (:line env)
            (core/str " on line " (:line env) " "
              cljs.analyzer/*cljs-file*)))))
    (assoc m test expr)))

(defn- const? [env x]
  (core/let [m (core/and (core/list? x)
                         (ana/resolve-var env (last x)))]
    (core/when m (core/get m :const))))

(defmacro case
  "Takes an expression, and a set of clauses.

  Each clause can take the form of either:

  test-constant result-expr

  (test-constant1 ... test-constantN)  result-expr

  The test-constants are not evaluated. They must be compile-time
  literals, and need not be quoted.  If the expression is equal to a
  test-constant, the corresponding result-expr is returned. A single
  default expression can follow the clauses, and its value will be
  returned if no clause matches. If no default expression is provided
  and no clause matches, an Error is thrown.

  Unlike cond and condp, case does a constant-time dispatch, the
  clauses are not considered sequentially.  All manner of constant
  expressions are acceptable in case, including numbers, strings,
  symbols, keywords, and (ClojureScript) composites thereof. Note that since
  lists are used to group multiple constants that map to the same
  expression, a vector can be used to match a list if needed. The
  test-constants need not be all of the same type."
  [e & clauses]
  (core/let [default (if (odd? (count clauses))
                       (last clauses)
                       `(throw
                          (js/Error.
                            (core/str "No matching clause: " ~e))))
             env     &env
             pairs   (reduce
                       (fn [m [test expr]]
                         (core/cond
                           (seq? test)
                           (reduce
                             (fn [m test]
                               (let [test (if (core/symbol? test)
                                            (core/list 'quote test)
                                            test)]
                                 (assoc-test m test expr env)))
                             m test)
                           (core/symbol? test)
                           (assoc-test m (core/list 'quote test) expr env)
                           :else
                           (assoc-test m test expr env)))
                     {} (partition 2 clauses))
             esym    (gensym)
             tests   (keys pairs)]
    (cond
      (every? (some-fn core/number? core/string? core/char? #(const? env %)) tests)
      (core/let [no-default (if (odd? (count clauses)) (butlast clauses) clauses)
                 tests      (mapv #(if (seq? %) (vec %) [%]) (take-nth 2 no-default))
                 thens      (vec (take-nth 2 (drop 1 no-default)))]
        `(let [~esym ~e] (case* ~esym ~tests ~thens ~default)))

      (every? core/keyword? tests)
      (let [tests (->> tests
                       (map #(.substring (core/str %) 1))
                       vec
                       (mapv #(if (seq? %) (vec %) [%])))
            thens (vec (vals pairs))]
        `(let [~esym (if (keyword? ~e) (.-fqn ~e) nil)]
           (case* ~esym ~tests ~thens ~default)))
      
      ;; equality
      :else
      `(let [~esym ~e]
         (cond
           ~@(mapcat (fn [[m c]] `((cljs.core/= ~m ~esym) ~c)) pairs)
           :else ~default)))))

(defmacro assert
  "Evaluates expr and throws an exception if it does not evaluate to
  logical true."
  ([x]
     (when *assert*
       `(when-not ~x
          (throw (js/Error.
                  (cljs.core/str "Assert failed: " (cljs.core/pr-str '~x)))))))
  ([x message]
     (when *assert*
       `(when-not ~x
          (throw (js/Error.
                  (cljs.core/str "Assert failed: " ~message "\n" (cljs.core/pr-str '~x))))))))

(defmacro for
  "List comprehension. Takes a vector of one or more
   binding-form/collection-expr pairs, each followed by zero or more
   modifiers, and yields a lazy sequence of evaluations of expr.
   Collections are iterated in a nested fashion, rightmost fastest,
   and nested coll-exprs can refer to bindings created in prior
   binding-forms.  Supported modifiers are: :let [binding-form expr ...],
   :while test, :when test.

  (take 100 (for [x (range 100000000) y (range 1000000) :while (< y x)]  [x y]))"
  [seq-exprs body-expr]
  (assert-args for
     (vector? seq-exprs) "a vector for its binding"
     (even? (count seq-exprs)) "an even number of forms in binding vector")
  (let [to-groups (fn [seq-exprs]
                    (reduce (fn [groups [k v]]
                              (if (core/keyword? k)
                                (conj (pop groups) (conj (peek groups) [k v]))
                                (conj groups [k v])))
                            [] (partition 2 seq-exprs)))
        err (fn [& msg] (throw (ex-info (apply core/str msg) {})))
        emit-bind (fn emit-bind [[[bind expr & mod-pairs]
                                  & [[_ next-expr] :as next-groups]]]
                    (let [giter (gensym "iter__")
                          gxs (gensym "s__")
                          do-mod (fn do-mod [[[k v :as pair] & etc]]
                                   (core/cond
                                     (= k :let) `(let ~v ~(do-mod etc))
                                     (= k :while) `(when ~v ~(do-mod etc))
                                     (= k :when) `(if ~v
                                                    ~(do-mod etc)
                                                    (recur (rest ~gxs)))
                                     (core/keyword? k) (err "Invalid 'for' keyword " k)
                                     next-groups
                                      `(let [iterys# ~(emit-bind next-groups)
                                             fs# (seq (iterys# ~next-expr))]
                                         (if fs#
                                           (concat fs# (~giter (rest ~gxs)))
                                           (recur (rest ~gxs))))
                                     :else `(cons ~body-expr
                                                  (~giter (rest ~gxs)))))]
                      (if next-groups
                        #_ "not the inner-most loop"
                        `(fn ~giter [~gxs]
                           (lazy-seq
                            (loop [~gxs ~gxs]
                              (when-first [~bind ~gxs]
                                ~(do-mod mod-pairs)))))
                        #_"inner-most loop"
                        (let [gi (gensym "i__")
                              gb (gensym "b__")
                              do-cmod (fn do-cmod [[[k v :as pair] & etc]]
                                        (core/cond
                                          (= k :let) `(let ~v ~(do-cmod etc))
                                          (= k :while) `(when ~v ~(do-cmod etc))
                                          (= k :when) `(if ~v
                                                         ~(do-cmod etc)
                                                         (recur
                                                           (unchecked-inc ~gi)))
                                          (core/keyword? k)
                                            (err "Invalid 'for' keyword " k)
                                          :else
                                            `(do (chunk-append ~gb ~body-expr)
                                                 (recur (unchecked-inc ~gi)))))]
                          `(fn ~giter [~gxs]
                             (lazy-seq
                               (loop [~gxs ~gxs]
                                 (when-let [~gxs (seq ~gxs)]
                                   (if (chunked-seq? ~gxs)
                                     (let [c# ^not-native (chunk-first ~gxs)
                                           size# (count c#)
                                           ~gb (chunk-buffer size#)]
                                       (if (coercive-boolean
                                            (loop [~gi 0]
                                              (if (< ~gi size#)
                                                (let [~bind (-nth c# ~gi)]
                                                  ~(do-cmod mod-pairs))
                                                true)))
                                         (chunk-cons
                                           (chunk ~gb)
                                           (~giter (chunk-rest ~gxs)))
                                         (chunk-cons (chunk ~gb) nil)))
                                     (let [~bind (first ~gxs)]
                                       ~(do-mod mod-pairs)))))))))))]
    `(let [iter# ~(emit-bind (to-groups seq-exprs))]
       (iter# ~(second seq-exprs)))))

(defmacro doseq
  "Repeatedly executes body (presumably for side-effects) with
  bindings and filtering as provided by \"for\".  Does not retain
  the head of the sequence. Returns nil."
  [seq-exprs & body]
  (assert-args doseq
     (vector? seq-exprs) "a vector for its binding"
     (even? (count seq-exprs)) "an even number of forms in binding vector")
  (let [err (fn [& msg] (throw (ex-info (apply core/str msg) {})))
        step (fn step [recform exprs]
               (if-not exprs
                 [true `(do ~@body)]
                 (let [k (first exprs)
                       v (second exprs)

                       seqsym (gensym "seq__")
                       recform (if (core/keyword? k) recform `(recur (next ~seqsym) nil 0 0))
                       steppair (step recform (nnext exprs))
                       needrec (steppair 0)
                       subform (steppair 1)]
                   (core/cond
                     (= k :let) [needrec `(let ~v ~subform)]
                     (= k :while) [false `(when ~v
                                            ~subform
                                            ~@(when needrec [recform]))]
                     (= k :when) [false `(if ~v
                                           (do
                                             ~subform
                                             ~@(when needrec [recform]))
                                           ~recform)]
                     (core/keyword? k) (err "Invalid 'doseq' keyword" k)
                     :else (let [chunksym (with-meta (gensym "chunk__")
                                            {:tag 'not-native})
                                 countsym (gensym "count__")
                                 isym     (gensym "i__")
                                 recform-chunk  `(recur ~seqsym ~chunksym ~countsym (unchecked-inc ~isym))
                                 steppair-chunk (step recform-chunk (nnext exprs))
                                 subform-chunk  (steppair-chunk 1)]
                             [true `(loop [~seqsym   (seq ~v)
                                           ~chunksym nil
                                           ~countsym 0
                                           ~isym     0]
                                      (if (coercive-boolean (< ~isym ~countsym))
                                        (let [~k (-nth ~chunksym ~isym)]
                                          ~subform-chunk
                                          ~@(when needrec [recform-chunk]))
                                        (when-let [~seqsym (seq ~seqsym)]
                                          (if (chunked-seq? ~seqsym)
                                            (let [c# (chunk-first ~seqsym)]
                                              (recur (chunk-rest ~seqsym) c#
                                                     (count c#) 0))
                                            (let [~k (first ~seqsym)]
                                              ~subform
                                              ~@(when needrec [recform]))))))])))))]
    (nth (step nil (seq seq-exprs)) 1)))

(defmacro array [& rest]
  (let [xs-str (->> (repeat "~{}")
                    (take (count rest))
                    (interpose ",")
                    (apply core/str))]
    (vary-meta
      (list* 'js* (core/str "[" xs-str "]") rest)
      assoc :tag 'array)))

(defmacro make-array
  [size]
  (vary-meta
    (if (core/number? size)
      `(array ~@(take size (repeat nil)))
      `(js/Array. ~size))
    assoc :tag 'array))

(defmacro list
  ([] '(.-EMPTY cljs.core/List))
  ([x & xs]
    `(-conj (list ~@xs) ~x)))

(defmacro vector
  ([] '(.-EMPTY cljs.core/PersistentVector))
  ([& xs]
    (let [cnt (count xs)]
      (if (core/< cnt 32)
        `(cljs.core/PersistentVector. nil ~cnt 5
           (.-EMPTY-NODE cljs.core/PersistentVector) (array ~@xs) nil)
        (vary-meta
          `(.fromArray cljs.core/PersistentVector (array ~@xs) true)
          assoc :tag 'cljs.core/PersistentVector)))))

(defmacro array-map
  ([] '(.-EMPTY cljs.core/PersistentArrayMap))
  ([& kvs]
     (let [keys (map first (partition 2 kvs))]
       (if (core/and (every? #(= (:op %) :constant)
                       (map #(cljs.analyzer/analyze &env %) keys))
                     (= (count (into #{} keys)) (count keys)))
         `(cljs.core/PersistentArrayMap. nil ~(clojure.core// (count kvs) 2) (array ~@kvs) nil)
         `(.fromArray cljs.core/PersistentArrayMap (array ~@kvs) true false)))))

(defmacro hash-map
  ([] `(.-EMPTY cljs.core/PersistentHashMap))
  ([& kvs]
    (let [pairs (partition 2 kvs)
          ks    (map first pairs)
          vs    (map second pairs)]
      (vary-meta
        `(.fromArrays cljs.core/PersistentHashMap (array ~@ks) (array ~@vs))
        assoc :tag 'cljs.core/PersistentHashMap))))

(defmacro hash-set
  ([] `(.-EMPTY cljs.core/PersistentHashSet))
  ([& xs]
    (if (core/and (core/<= (count xs) 8)
                  (every? #(= (:op %) :constant)
                    (map #(cljs.analyzer/analyze &env %) xs))
                  (= (count (into #{} xs)) (count xs)))
      `(cljs.core/PersistentHashSet. nil
         (cljs.core/PersistentArrayMap. nil ~(count xs) (array ~@(interleave xs (repeat nil))) nil)
         nil)
      (vary-meta
        `(.fromArray cljs.core/PersistentHashSet (array ~@xs) true)
        assoc :tag 'cljs.core/PersistentHashSet))))

(defn- js-obj* [kvs]
  (let [kvs-str (->> (repeat "~{}:~{}")
                     (take (count kvs))
                     (interpose ",")
                     (apply core/str))]
    (vary-meta
      (list* 'js* (core/str "{" kvs-str "}") (apply concat kvs))
      assoc :tag 'object)))

(defmacro js-obj [& rest]
  (let [sym-or-str? (fn [x] (core/or (core/symbol? x) (core/string? x)))
        filter-on-keys (fn [f coll]
                         (->> coll
                              (filter (fn [[k _]] (f k)))
                              (into {})))
        kvs (into {} (map vec (partition 2 rest)))
        sym-pairs (filter-on-keys core/symbol? kvs)
        expr->local (zipmap
                     (filter (complement sym-or-str?) (keys kvs))
                     (repeatedly gensym))
        obj (gensym "obj")]
    `(let [~@(apply concat (clojure.set/map-invert expr->local))
           ~obj ~(js-obj* (filter-on-keys core/string? kvs))]
       ~@(map (fn [[k v]] `(aset ~obj ~k ~v)) sym-pairs)
       ~@(map (fn [[k v]] `(aset ~obj ~v ~(core/get kvs k))) expr->local)
       ~obj)))

(defmacro alength [a]
  (vary-meta
    (core/list 'js* "~{}.length" a)
    assoc :tag 'number))

(defmacro amap
  "Maps an expression across an array a, using an index named idx, and
  return value named ret, initialized to a clone of a, then setting
  each element of ret to the evaluation of expr, returning the new
  array ret."
  [a idx ret expr]
  `(let [a# ~a
         ~ret (aclone a#)]
     (loop  [~idx 0]
       (if (< ~idx  (alength a#))
         (do
           (aset ~ret ~idx ~expr)
           (recur (inc ~idx)))
         ~ret))))

(defmacro areduce
  "Reduces an expression across an array a, using an index named idx,
  and return value named ret, initialized to init, setting ret to the
  evaluation of expr at each step, returning ret."
  [a idx ret init expr]
  `(let [a# ~a]
     (loop  [~idx 0 ~ret ~init]
       (if (< ~idx  (alength a#))
         (recur (inc ~idx) ~expr)
         ~ret))))

(defmacro dotimes
  "bindings => name n

  Repeatedly executes body (presumably for side-effects) with name
  bound to integers from 0 through n-1."
  [bindings & body]
  (let [i (first bindings)
        n (second bindings)]
    `(let [n# ~n]
       (loop [~i 0]
         (when (< ~i n#)
           ~@body
           (recur (inc ~i)))))))

(defn- check-valid-options
  "Throws an exception if the given option map contains keys not listed
  as valid, else returns nil."
  [options & valid-keys]
  (when (seq (apply disj (apply core/hash-set (keys options)) valid-keys))
    (throw
     (apply core/str "Only these options are valid: "
	    (first valid-keys)
	    (map #(core/str ", " %) (rest valid-keys))))))

(defmacro defmulti
  "Creates a new multimethod with the associated dispatch function.
  The docstring and attribute-map are optional.

  Options are key-value pairs and may be one of:
    :default    the default dispatch value, defaults to :default
    :hierarchy  the isa? hierarchy to use for dispatching
                defaults to the global hierarchy"
  [mm-name & options]
  (let [docstring   (if (core/string? (first options))
                      (first options)
                      nil)
        options     (if (core/string? (first options))
                      (next options)
                      options)
        m           (if (map? (first options))
                      (first options)
                      {})
        options     (if (map? (first options))
                      (next options)
                      options)
        dispatch-fn (first options)
        options     (next options)
        m           (if docstring
                      (assoc m :doc docstring)
                      m)
        m           (if (meta mm-name)
                      (conj (meta mm-name) m)
                      m)
        mm-ns (-> &env :ns :name core/str)] 
    (when (= (count options) 1)
      (throw (Exception. "The syntax for defmulti has changed. Example: (defmulti name dispatch-fn :default dispatch-value)")))
    (let [options   (apply core/hash-map options)
          default   (core/get options :default :default)]
      (check-valid-options options :default :hierarchy)
      `(defonce ~(with-meta mm-name m)
         (let [method-table# (atom {})
               prefer-table# (atom {})
               method-cache# (atom {})
               cached-hierarchy# (atom {})
               hierarchy# (get ~options :hierarchy (cljs.core/get-global-hierarchy))]
           (cljs.core/MultiFn. (cljs.core/symbol ~mm-ns ~(name mm-name)) ~dispatch-fn ~default hierarchy#
                               method-table# prefer-table# method-cache# cached-hierarchy#))))))

(defmacro defmethod
  "Creates and installs a new method of multimethod associated with dispatch-value. "
  [multifn dispatch-val & fn-tail]
  `(-add-method ~(with-meta multifn {:tag 'cljs.core/MultiFn}) ~dispatch-val (fn ~@fn-tail)))

(defmacro time
  "Evaluates expr and prints the time it took. Returns the value of expr."
  [expr]
  `(let [start# (.getTime (js/Date.))
         ret# ~expr]
     (prn (core/str "Elapsed time: " (- (.getTime (js/Date.)) start#) " msecs"))
     ret#))

(defmacro simple-benchmark
  "Runs expr iterations times in the context of a let expression with
  the given bindings, then prints out the bindings and the expr
  followed by number of iterations and total time. The optional
  argument print-fn, defaulting to println, sets function used to
  print the result. expr's string representation will be produced
  using pr-str in any case."
  [bindings expr iterations & {:keys [print-fn] :or {print-fn 'println}}]
  (let [bs-str   (pr-str bindings)
        expr-str (pr-str expr)]
    `(let ~bindings
       (let [start#   (.getTime (js/Date.))
             ret#     (dotimes [_# ~iterations] ~expr)
             end#     (.getTime (js/Date.))
             elapsed# (- end# start#)]
         (~print-fn (str ~bs-str ", " ~expr-str ", "
                         ~iterations " runs, " elapsed# " msecs"))))))

(def cs (into [] (map (comp gensym core/str core/char) (range 97 118))))

(defn- gen-apply-to-helper
  ([] (gen-apply-to-helper 1))
  ([n]
     (let [prop (symbol (core/str "-cljs$core$IFn$_invoke$arity$" n))
           f (symbol (core/str "cljs$core$IFn$_invoke$arity$" n))]
       (if (core/<= n 20)
         `(let [~(cs (core/dec n)) (-first ~'args)
                ~'args (-rest ~'args)]
            (if (core/== ~'argc ~n)
              (if (. ~'f ~prop)
                (. ~'f (~f ~@(take n cs)))
                (~'f ~@(take n cs)))
              ~(gen-apply-to-helper (core/inc n))))
         `(throw (js/Error. "Only up to 20 arguments supported on functions"))))))

(defmacro gen-apply-to []
  `(do
     (set! ~'*unchecked-if* true)
     (defn ~'apply-to [~'f ~'argc ~'args]
       (let [~'args (seq ~'args)]
         (if (zero? ~'argc)
           (~'f)
           ~(gen-apply-to-helper))))
     (set! ~'*unchecked-if* false)))

(defmacro with-out-str
  "Evaluates exprs in a context in which *print-fn* is bound to .append
  on a fresh StringBuffer.  Returns the string created by any nested
  printing calls."
  [& body]
  `(let [sb# (goog.string.StringBuffer.)]
     (binding [cljs.core/*print-newline* true
               cljs.core/*print-fn* (fn [x#] (.append sb# x#))]
       ~@body)
     (cljs.core/str sb#)))

(defmacro lazy-cat
  "Expands to code which yields a lazy sequence of the concatenation
  of the supplied colls.  Each coll expr is not evaluated until it is
  needed. 

  (lazy-cat xs ys zs) === (concat (lazy-seq xs) (lazy-seq ys) (lazy-seq zs))"
  [& colls]
  `(concat ~@(map #(core/list `lazy-seq %) colls)))

(defmacro js-str [s]
  (core/list 'js* "''+~{}" s))

(defmacro es6-iterable [ty]
  `(aset (.-prototype ~ty) cljs.core/ITER_SYMBOL
     (fn []
       (this-as this#
         (cljs.core/es6-iterator this#)))))

(defmacro ns-interns
  "Returns a map of the intern mappings for the namespace."
  [[quote ns]]
  (core/assert (core/and (= quote 'quote) (core/symbol? ns))
    "Argument to ns-interns must be a quoted symbol")
  `(into {}
     [~@(map
          (fn [[sym _]]
            `[(symbol ~(name sym)) (var ~(symbol (name ns) (name sym)))])
          (get-in @env/*compiler* [:cljs.analyzer/namespaces ns :defs]))]))

(defmacro ns-unmap
  "Removes the mappings for the symbol from the namespace."
  [[quote0 ns] [quote1 sym]]
  (core/assert (core/and (= quote0 'quote) (core/symbol? ns)
                         (= quote1 'quote) (core/symbol? sym))
    "Arguments to ns-unmap must be quoted symbols")
  (swap! env/*compiler* update-in [::ana/namespaces ns :defs] dissoc sym)
  `(js-delete ~(cljs.compiler/munge ns) ~(cljs.compiler/munge (core/str sym))))

(defmacro vswap!
  "Non-atomically swaps the value of the volatile as if:
   (apply f current-value-of-vol args). Returns the value that
   was swapped in."
  [vol f & args]
  `(-vreset! ~vol (~f (-deref ~vol) ~@args)))

;; INTERNAL - do not use, only for Node.js
(defmacro load-file* [f]
  `(. js/goog (~'nodeGlobalRequire ~f)))

(defmacro macroexpand-1
  "If form represents a macro form, returns its expansion,
  else returns form."
  [quoted]
  (core/assert (core/= (core/first quoted) 'quote)
    "Argument to macroexpand-1 must be quoted")
  (core/let [form (second quoted)]
    `(quote ~(ana/macroexpand-1 &env form))))

(defmacro macroexpand
  "Repeatedly calls macroexpand-1 on form until it no longer
  represents a macro form, then returns it.  Note neither
  macroexpand-1 nor macroexpand expand macros in subforms."
  [quoted]
  (core/assert (core/= (core/first quoted) 'quote)
    "Argument to macroexpand must be quoted")
  (core/let [form (second quoted)
             env &env]
    (core/loop [form form form' (ana/macroexpand-1 env form)]
      (core/if-not (core/identical? form form')
        (recur form' (ana/macroexpand-1 env form'))
        `(quote ~form')))))

(defn- multi-arity-fn? [fdecl]
  (core/< 1 (count fdecl)))

(defn- variadic-fn? [fdecl]
  (core/and (= (count fdecl))
            (some '#{&} (ffirst fdecl))))

;; TODO: variadic-fn

(defn- multi-arity-fn [name meta fdecl]
  (letfn [(dest-args [c]
            (map (fn [n] `(aget (js-arguments) ~n))
              (range c)))
          (fixed-arity [sig]
            (let [c (count sig)]
              [c `(. ~name
                    (~(symbol
                        (core/str "cljs$core$IFn$_invoke$arity$" c))
                      ~@(dest-args c)))]))
          (fn-method [[sig & body :as method]]
            `(set! (. ~name ~(symbol
                               (core/str "-cljs$core$IFn$_invoke$arity$"
                                 (if (some '#{&} sig)
                                   "variadic"
                                   (count sig)))))
               (fn ~method)))]
    (core/let [arglists (map first fdecl)
               variadic (some #(some '#{&} %) arglists)
               sigs     (remove #(some '#{&} %) arglists)
               maxfa    (apply core/max (map count sigs))]
     `(do
        (def ~(with-meta name
                (assoc meta :top-fn
                  {:variadic (boolean variadic)
                   :max-fixed-arity maxfa
                   :method-params sigs
                   :arglists arglists
                   :arglists-meta (doall (map meta arglists))}))
          (fn []
            (case (alength (js-arguments))
              ~@(mapcat #(fixed-arity %) sigs)
              ~(if variadic
                 `(let [argseq# (new ^::ana/no-resolve cljs.core/IndexedSeq
                                  (.call js/Array.prototype.slice
                                    (js-arguments) ~maxfa) 0)]
                    (. ~name
                      (~(symbol
                          (core/str "cljs$core$IFn$_invoke$arity$variadic"))
                        ~@(dest-args maxfa)
                        argseq#)))
                 `(throw (js/Error.
                           (str "Invalid arity: "
                             (alength (js-arguments)))))))))
        (set! (. ~name ~(symbol (core/str "-cljs$lang$maxFixedArity"))) ~maxfa)
        ;; TODO: cljs$lang$applyTo
        ~@(map fn-method fdecl)))))

(comment
  (require '[clojure.pprint :as pp])
  (pp/pprint (multi-arity-fn 'foo {} '(([a]) ([a b]))))
  (pp/pprint (multi-arity-fn 'foo {} '(([a]) ([a b & xs]))))
  )

(def
  ^{:doc "Same as (def name (fn [params* ] exprs*)) or (def
    name (fn ([params* ] exprs*)+)) with any doc-string or attrs added
    to the var metadata. prepost-map defines a map with optional keys
    :pre and :post that contain collections of pre or post conditions."
    :arglists '([name doc-string? attr-map? [params*] prepost-map? body]
                 [name doc-string? attr-map? ([params*] prepost-map? body)+ attr-map?])}
  defn (fn defn [&form &env name & fdecl]
         ;; Note: Cannot delegate this check to def because of the call to (with-meta name ..)
         (if (core/instance? clojure.lang.Symbol name)
           nil
           (throw (IllegalArgumentException. "First argument to defn must be a symbol")))
         (core/let [m (if (core/string? (first fdecl))
                        {:doc (first fdecl)}
                        {})
                    fdecl (if (core/string? (first fdecl))
                            (next fdecl)
                            fdecl)
                    m (if (map? (first fdecl))
                        (conj m (first fdecl))
                        m)
                    fdecl (if (map? (first fdecl))
                            (next fdecl)
                            fdecl)
                    fdecl (if (vector? (first fdecl))
                            (core/list fdecl)
                            fdecl)
                    m (if (map? (last fdecl))
                        (conj m (last fdecl))
                        m)
                    fdecl (if (map? (last fdecl))
                            (butlast fdecl)
                            fdecl)
                    m (conj {:arglists (core/list 'quote (sigs fdecl))} m)
                    m (core/let [inline (:inline m)
                                 ifn (first inline)
                                 iname (second inline)]
                        ;; same as: (if (and (= 'fn ifn) (not (symbol? iname))) ...)
                        (if (if (clojure.lang.Util/equiv 'fn ifn)
                              (if (core/instance? clojure.lang.Symbol iname) false true))
                          ;; inserts the same fn name to the inline fn if it does not have one
                          (assoc m :inline (cons ifn (cons (clojure.lang.Symbol/intern (.concat (.getName ^clojure.lang.Symbol name) "__inliner"))
                                                       (next inline))))
                          m))
                    m (conj (if (meta name) (meta name) {}) m)]
           (core/list 'def (with-meta name m)
             ;;todo - restore propagation of fn name
             ;;must figure out how to convey primitive hints to self calls first
             (cons `fn fdecl) ))))

(. (var defn) (setMacro))
