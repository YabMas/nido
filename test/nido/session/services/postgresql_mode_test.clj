(ns nido.session.services.postgresql-mode-test
  (:require
   [clojure.test :refer [deftest is]]
   [nido.session.services.postgresql :as pg]))

(deftest resolve-pg-mode-handles-explicit-and-alias-and-default
  (is (= :shared   (pg/resolve-pg-mode {:mode :shared})))
  (is (= :isolated (pg/resolve-pg-mode {:mode :isolated})))
  (is (= :clone    (pg/resolve-pg-mode {:mode :clone})))
  ;; back-compat: :clone-from-template true → :clone
  (is (= :clone    (pg/resolve-pg-mode {:clone-from-template true})))
  ;; nothing set → :clone (today's default: a private PGDATA per session)
  (is (= :clone    (pg/resolve-pg-mode {}))))
