(ns nido.ui.views-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [nido.ui.views :as views]))

(deftest sessions-table-renders-friendly-host-link
  ;; The registry persists the friendly-host URL under :app-url; the table
  ;; must render it as a clickable link (it long read the wrong key, :url).
  (let [html (views/sessions-table-fragment
              "brian"
              [{:name "fix-login" :live? true
                :entry {:app-url "http://fix-login.brian.localhost:3142" :app-port 3142}}])]
    (is (str/includes? html "href=\"http://fix-login.brian.localhost:3142\""))))
