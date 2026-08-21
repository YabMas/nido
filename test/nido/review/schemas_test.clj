(ns nido.review.schemas-test
  "The JSON output schemas a round hands to codex. They are not Clojure and no
   compiler sees them, so nothing here is caught by the rest of the suite — and
   they fail at the API rather than locally: an inconsistent one comes back as a
   400 after the round has already been dispatched, which reads as a round that
   could not run."
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as jio]
   [clojure.test :refer [deftest is testing]]))

(def ^:private schemas
  ["review/findings_schema.json"
   "review/baseline_review_schema.json"
   "review/design_decision_schema.json"])

(defn- object-nodes
  "Every object node in the schema tree that declares :properties."
  [node]
  (cond
    (map? node)  (concat (when (:properties node) [node])
                         (mapcat object-nodes (vals node)))
    (coll? node) (mapcat object-nodes node)
    :else        nil))

(deftest every-object-requires-exactly-what-it-declares
  ;; The provider rejects a schema whose `required` is not every key in
  ;; `properties`. Editing one half and missing the other is the actual failure
  ;; this pins: it happened, and it surfaced only as a 400 from a live round.
  (doseq [path schemas]
    (testing path
      (let [schema (json/parse-string (slurp (jio/resource path)) true)]
        (doseq [node (object-nodes schema)]
          (is (= (set (map name (keys (:properties node))))
                 (set (:required node)))
              (str path " — properties and required disagree at "
                   (pr-str (sort (map name (keys (:properties node)))))))))))) 

(deftest every-schema-parses-and-is-an-object
  (doseq [path schemas]
    (testing path
      (let [schema (json/parse-string (slurp (jio/resource path)) true)]
        (is (= "object" (:type schema)))
        (is (false? (:additionalProperties schema))
            "a round's answer is a closed shape, like every record it becomes")))))
