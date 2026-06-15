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

(deftest live-board-fragment-links-live-and-routes-down
  (let [html (views/live-board-fragment
              [{:project "brian" :name "fix-login" :live? true
                :entry {:app-url "http://fix-login.brian.localhost:3142"}}
               {:project "brian" :name "doc-room" :live? false :entry nil}])]
    ;; live row: clickable friendly-host link opening a new tab
    (is (str/includes? html "href=\"http://fix-login.brian.localhost:3142\""))
    (is (str/includes? html "target=\"_blank\""))
    ;; both sessions listed
    (is (str/includes? html "fix-login"))
    (is (str/includes? html "doc-room"))
    ;; down row: routes to the per-project page (where start/stop live)
    (is (str/includes? html "/brian/sessions"))
    (is (str/includes? html "start"))))

(deftest live-board-page-renders-header-and-poll
  (let [html (views/live-board-page [])]
    (is (str/includes? html "live sessions"))
    ;; auto-refresh against the board SSE fragment
    (is (str/includes? html "/_fragment/live"))))
