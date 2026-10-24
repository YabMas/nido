(ns nido.platform.task-args-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [nido.platform.task-args :as task-args]))

(deftest every-known-key-passes
  (is (nil? (task-args/unknown-opts-refusal {:cwd "/w" :dry-run? true} #{:cwd :dry-run? :seq})))
  (is (nil? (task-args/unknown-opts-refusal {} #{:cwd}))))

(deftest an-unknown-key-is-named-with-its-nearest-known-one
  (let [why (task-args/unknown-opts-refusal {:dry-run true :cwd "/w"} #{:cwd :dry-run? :budget})]
    (is (str/includes? why ":dry-run (closest: :dry-run?)"))
    (is (str/includes? why "this task takes :budget :cwd :dry-run?"))))

(deftest a-key-near-nothing-is-named-alone
  (let [why (task-args/unknown-opts-refusal {:frobnicate 1} #{:cwd})]
    (is (str/starts-with? why "unknown option :frobnicate — "))))
