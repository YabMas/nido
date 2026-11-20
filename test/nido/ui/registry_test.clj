(ns nido.ui.registry-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nido.ui.registry :as registry]))

(def ^:private component-namespaces
  ['nido.ui.components.state 'nido.ui.components.row 'nido.ui.components.ask
   'nido.ui.components.chat 'nido.ui.components.dialog])

(defn- registration? [v] (and (map? v) (fn? (:render v)) (vector? (:scenes v))))

(defn- published-registrations []
  (for [ns-sym component-namespaces
        :let [_ (require ns-sym)]
        [sym v] (ns-publics ns-sym)
        :when (registration? @v)]
    [(str ns-sym "/" sym) @v]))

(deftest the-registry-holds-every-published-registration
  ;; A registration missing from the registry renders unstyled on every page: its rules are
  ;; assembled from the registry and nowhere else.
  (let [held (set (map :id (:blocks (registry/registry))))]
    (doseq [[n blk] (published-registrations)]
      (testing n
        (is (contains? held (:id blk)))))))

(deftest every-registrations-rules-are-in-the-assembled-stylesheet
  (let [sheet (registry/assembled-stylesheet)]
    (doseq [{:keys [id css]} (:blocks (registry/registry))
            :when (seq (str/trim css))]
      (testing (str id)
        (is (str/includes? sheet (str/trim css)))))))
