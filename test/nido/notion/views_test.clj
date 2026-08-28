(ns nido.notion.views-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.state :as cstate]
   [nido.platform.io :as io]
   [nido.notion.views :as views]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (fs/create-dirs (str (fs/path tmp "projects" "brian")))
        (f tmp))
      (finally (fs/delete-tree tmp)))))

(def sample-registry
  {:database "124fca9f-403c-80d4-896f-fc857e105e35"
   :views {:new-reports {:filter {:property "Status" :status {:equals "Needs verification"}}}
           :bugs        {:filter {:and [{:property "Type" :select {:equals "bug"}}]}}}})

(deftest resolve-view-returns-database-and-filter
  (with-tmp
    (fn [tmp]
      (io/write-edn! (str (fs/path tmp "projects" "brian" "notion-views.edn"))
                     sample-registry)
      (let [r (views/resolve-view :brian :new-reports)]
        (is (= "124fca9f-403c-80d4-896f-fc857e105e35" (:database r)))
        (is (= {:property "Status" :status {:equals "Needs verification"}}
               (:filter r)))))))

(deftest resolve-view-throws-on-unknown-view
  (with-tmp
    (fn [tmp]
      (io/write-edn! (str (fs/path tmp "projects" "brian" "notion-views.edn"))
                     sample-registry)
      (is (thrown? clojure.lang.ExceptionInfo
                   (views/resolve-view :brian :nope))))))

(deftest resolve-view-throws-when-no-registry-file
  (with-tmp
    (fn [_]
      (is (thrown? clojure.lang.ExceptionInfo
                   (views/resolve-view :brian :new-reports))))))

(defn- with-registry [project edn f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (let [path (str (fs/path (str tmp) "projects" (name project) "notion-views.edn"))]
          (fs/create-dirs (fs/parent path))
          (io/write-edn! path edn))
        (f))
      (finally (fs/delete-tree tmp)))))

(deftest facet-properties-reads-config
  (with-registry :brian {:database "d" :facets ["App Domain" "Type"] :views {}}
    (fn [] (is (= ["App Domain" "Type"] (views/facet-properties :brian))))))

(deftest facet-properties-defaults-empty-without-key
  (with-registry :brian {:database "d" :views {}}
    (fn [] (is (= [] (views/facet-properties :brian))))))

(deftest facet-properties-empty-when-registry-absent
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (is (= [] (views/facet-properties :brian))))
      (finally (fs/delete-tree tmp)))))
