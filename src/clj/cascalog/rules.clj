 ;    Copyright 2010 Nathan Marz
 ; 
 ;    This program is free software: you can redistribute it and/or modify
 ;    it under the terms of the GNU General Public License as published by
 ;    the Free Software Foundation, either version 3 of the License, or
 ;    (at your option) any later version.
 ; 
 ;    This program is distributed in the hope that it will be useful,
 ;    but WITHOUT ANY WARRANTY; without even the implied warranty of
 ;    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 ;    GNU General Public License for more details.
 ; 
 ;    You should have received a copy of the GNU General Public License
 ;    along with this program.  If not, see <http://www.gnu.org/licenses/>.

(ns cascalog.rules
  (:use [cascalog vars util graph])
  (:use clojure.contrib.set)
  (:use [clojure.set :only [intersection union]])
  (:use [clojure.contrib.set :only [subset?]])
  (:use [clojure.contrib.seq-utils :only [group-by find-first separate]])
  (:require [cascalog [workflow :as w] [predicate :as p]])
  (:import [cascading.tap Tap])
  (:import [cascading.tuple Fields])
  (:import [cascading.flow Flow FlowConnector])
  (:import  [cascading.pipe Pipe]))

;; algorithm here won't work for (gen1 ?p ?a) (gen2 ?p ?b) (gen3 ?p ?c) (func ?a ?b :> ?c)
;; need to do whole equality set thing (and probably not use explicit = for joins to give user control)

;; TODO:
;; 
;; 0. implement aggregators and make sure they work (in conjunction with post-group functions/filters too)
;; 1. thorough tests
;; 2. Enforce !! rules -> only allowed in generators, ungrounds whatever it's in
;; 3. rework joins and var uniquing to create equality sets - filter when possible, otherwise use joins
;; 4. parameterized rules? - how does aggregation & joins work in this context? ->
;;    -> maybe just creates a generator...?
;; 
;; TODO: make it possible to create ungrounded rules that take in input vars (for composition)
;; i.e. (<- [?a ?b :> ?c] (func1 ?a :> ?c) (func2 ?b :> ?c))
;; (<- [?p] (data ?p ?a ?b) (func1 ?a :> ?c1) (func2 ?b :> ?c2) (= ?c1 ?c2))
;; (<- [?p] (age ?p ?a) (friend ?p1 ?p2) (= ?p ?p1))
;; (<- [?num] (nums ?num ?num) (source2 ?num))
;; (<- [?num] (nums ?num1 ?num2) (source2 ?num3) (= ?num1 ?num2 ?num3))
;; -> do filter equalities as you can, then do joins when they're valid
;; (<- [?p1 ?p2] (age ?p1 ?a) (age ?p2 ?a) (friend ?p1 ?p2))
;; (<- [?p1 ?p2] (age ?p1 ?a) (age ?p2 ?a1) (friend ?p1 ?p2) (= ?a ?a1))


;; infields for a join are the names of the join fields
(p/defpredicate join :infields)
(p/defpredicate group :assembly :infields :totaloutfields)

;; returns [generators operations aggregators]
(defn- split-predicates [predicates]
  (let [{ops :operation
         aggs :aggregator
         gens :generator} (merge {:operation [] :aggregator [] :generator []}
                                (group-by :type predicates))]
    (when (and (> (count aggs) 1) (some (complement :composable) aggs))
      (throw (IllegalArgumentException. "Cannot use both aggregators and buffers in same grouping")))
    [gens ops aggs] ))


(defstruct tailstruct :operations :available-fields :node)

(defn- add-op [tail op]
  (let [new-node (connect-value (:node tail) op)
        new-outfields (concat (:available-fields tail) (:outfields op))
        new-ops (remove-first (partial = op) (:operations tail))]
        (struct tailstruct new-ops new-outfields new-node)))

(defn- op-allowed? [available-fields op]
  (let [infields-set (set (:infields op))]
    (subset? infields-set (set available-fields))
    ))

(defn- add-ops-fixed-point
  "Adds operations to tail until can't anymore. Returns new tail"
  [tail]
  (if-let [op (find-first (partial op-allowed? (:available-fields tail)) (:operations tail))]
    (recur (add-op tail op))
    tail ))

(defn- tail-fields-intersection [& tails]
  (intersection (map #(set (:available-fields %)) tails)))

(defn- select-join
  "Splits tails into [{join set} {rest of tails}]
   this is unoptimal. it's better to rewrite this as a search problem to find optimal joins"
  [tails]
  (let [pairs     (all-pairs tails)
        sections  (map (fn [[t1 t2]]
                          (intersection (set (:available-fields t1))
                                        (set (:available-fields t2))))
                      pairs)
        max-join  (last (sort-by count sections))]
      (cons (vec max-join) (separate #(subset? max-join (set (:available-fields %))) tails))
    ))

(defn- merge-tails [graph tails]
  (let [tails (map add-ops-fixed-point tails)]
    (if (= 1 (count tails))
      (first tails)
      (let [[join-fields join-set rest-tails] (select-join tails)
            join-node             (create-node graph (p/predicate join join-fields))
            available-fields      (vec (set (apply concat (map :available-fields join-set))))
            new-ops               (vec (apply intersection (map #(set (:operations %)) join-set)))]
        ; TODO: eventually should specify the join fields and type of join in the edge
        ;;  for ungrounding vars & when move to full variable renaming and equality sets
        (dorun (map #(create-edge (:node %) join-node) join-set))
        (recur graph (cons (struct tailstruct new-ops available-fields join-node) rest-tails))
        ))))

(defn- agg-available-fields [grouping-fields aggs]
  (vec (union (set grouping-fields) (apply union (map :outfields aggs)))))

(defn- agg-infields [aggs]
  (vec (apply union (map :infields aggs))))

(defn- normalize-grouping
  "Returns [new-grouping-fields inserter-assembly]"
  [grouping-fields]
  (if-not (empty? grouping-fields)
          [grouping-fields identity]
          (let [newvar (gen-nullable-var)]
            [[newvar] (w/insert newvar 1)]
            )))

(defn- build-agg-tail [options prev-tail grouping-fields aggs]
  ;; if no aggs -> if options:distinct is false, return prev-tail. otherwise, insert a distinct
  ;; insert arbitrary grouping field if no grouping fields
  (if (and (not (:distinct options)) (empty? aggs))
    prev-tail
    (let [aggs              (if-not (empty? aggs) aggs [p/distinct-aggregator])
          [grouping-fields
           inserter]        (normalize-grouping grouping-fields)
          prev-node    (:node prev-tail)
        assem        (apply w/compose-straight-assemblies (concat
                       [inserter]
                       (map :pregroup-assembly aggs)
                       [(w/group-by grouping-fields)]
                       (map :postgroup-assembly aggs)
                       (map :post-assembly aggs)
                       ))
        total-fields (agg-available-fields grouping-fields aggs)
        node         (create-node (get-graph prev-node)
                        (p/predicate group assem (agg-infields aggs) total-fields))]
     (create-edge prev-node node)
     (struct tailstruct (:operations prev-tail) total-fields node))
    ))

(defn projection-fields [needed-vars allfields]
  (let [needed-set (set needed-vars)
        all-set    (set allfields)
        inter      (intersection needed-set all-set)]
        (cond (= inter needed-set) needed-vars  ; maintain ordering when =, this is for output of generators to match declared ordering
              (empty? inter) [(first allfields)]  ; this happens during global aggregation, need to keep one field in
              true (vec inter)
        )))

(defn- mk-projection-assembly [forceproject projection-fields allfields]
    (if (and (not forceproject) (= (set projection-fields) (set allfields)))
      identity
      (w/select projection-fields)))

(defmulti node->generator (fn [pred & rest] (:type pred)))

(defmethod node->generator :generator [pred prevgens]
  (when (not-empty prevgens)
    (throw (RuntimeException. "Planner exception: Generator has inbound nodes")))
  pred )

(defn- rename-join-fields [join-fields fields]
  (let [join-set (set join-fields)
        updatefn (fn [f] (if (join-set f) (gen-nullable-var) f))]
    (map updatefn fields)))

;; (defpredicate generator :sourcemap :pipe :outfields)
(defmethod node->generator :join [pred prevgens]
  (let [join-fields (:infields pred)
        sourcemap   (apply merge (map :sourcemap prevgens))
        infields    (map :outfields prevgens)
        inpipes     (map (fn [p f] ((w/select f) p)) (map :pipe prevgens) infields)
        rename-fields (apply concat (first infields) (map (partial rename-join-fields join-fields) (rest infields)))
        keep-fields (vec (set (apply concat infields)))
        joined      (w/assemble inpipes (w/inner-join (repeat (count inpipes) join-fields) rename-fields)
                                        (w/select keep-fields))]
        (p/predicate p/generator  sourcemap joined keep-fields)
    ))

(defmethod node->generator :operation [pred prevgens]
  (when-not (= 1 (count prevgens))
    (throw (RuntimeException. "Planner exception: operation has multiple inbound generators")))
  (let [prevpred (first prevgens)]
    (merge prevpred {:outfields (concat (:outfields pred) (:outfields prevpred))
            :pipe ((:assembly pred) (:pipe prevpred))})))

(defmethod node->generator :group [pred prevgens]
  (when-not (= 1 (count prevgens))
    (throw (RuntimeException. "Planner exception: group has multiple inbound generators")))
  (let [prevpred (first prevgens)]
    (merge prevpred {:outfields (:totaloutfields pred)
            :pipe ((:assembly pred) (:pipe prevpred))})))


;; forceproject necessary b/c *must* reorder last set of fields coming out to match declared ordering
(defn build-generator [forceproject needed-vars node]
  (let [pred           (get-value node)
        my-needed      (vec (set (concat (:infields pred) needed-vars)))
        prev-gens      (doall (map (partial build-generator false my-needed) (get-inbound-nodes node)))
        newgen         (node->generator pred prev-gens)
        project-fields (projection-fields needed-vars (:outfields newgen)) ]
        (when (and forceproject (not= project-fields needed-vars))
          (throw (RuntimeException. (str "Only able to build to " project-fields " but need " needed-vars))))
        (merge newgen {:pipe ((mk-projection-assembly forceproject project-fields (:outfields newgen)) (:pipe newgen))
                :outfields project-fields})))

(defn build-rule [options out-vars raw-predicates]
  (let [[out-vars vmap]       (uniquify-vars out-vars {})
        update-fn             (fn [[preds vmap] [op opvar vars]]
                                (let [[newvars vmap] (uniquify-vars vars vmap)]
                                  [(conj preds [op opvar newvars]) vmap] ))
        [raw-predicates _]    (reduce update-fn [[] vmap] raw-predicates)
        [gens ops aggs]       (split-predicates (map (partial apply p/build-predicate) raw-predicates))
        rule-graph            (mk-graph)
        tails                 (map (fn [g] (struct tailstruct ops (:outfields g) (create-node rule-graph g))) gens)
        joined                (merge-tails rule-graph tails)
        grouping-fields       (seq (intersection (set (:available-fields joined)) (set out-vars)))
        agg-tail              (build-agg-tail options joined grouping-fields aggs)
        tail                  (add-ops-fixed-point agg-tail)]
      (when (not-empty (:operations tail))
        (throw (RuntimeException. (str "Could not apply all operations " (:operations tail)))))
      (build-generator true out-vars (:node tail))))

(defn mk-raw-predicate [pred]
  (let [[op-sym & vars] pred
        str-vars (vars2str vars)]
    [op-sym (try-resolve op-sym) str-vars]))

(defn connect-to-sink [gen sink]
    (let [sink-fields (.getSinkFields sink)
          pipe        (:pipe gen)
          pipe        (if-not (.isDefined sink-fields)
                        (if (.isAll sink-fields)
                          pipe
                          (throw (IllegalArgumentException.
                            "Cannot sink to a sink with meta fields defined besides Fields/ALL")))
                        ((w/identity (:outfields gen) :fn> sink-fields) pipe))]
          ((w/pipe-rename (uuid)) pipe)))

