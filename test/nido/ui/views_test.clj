(ns nido.ui.views-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [nido.ui.views :as views]))

(deftest sessions-table-renders-friendly-host-link
  ;; The registry persists the friendly-host app URL under :url; the table
  ;; renders it as a clickable link.
  (let [html (views/sessions-table-fragment
              "brian"
              [{:name "fix-login" :live? true
                :entry {:url "http://fix-login.brian.localhost:3142" :app-port 3142}}])]
    (is (str/includes? html "href=\"http://fix-login.brian.localhost:3142\""))))

(deftest live-board-fragment-links-live-and-starts-down
  (let [html (views/live-board-fragment
              [{:project "brian" :name "fix-login" :live? true
                :entry {:url "http://fix-login.brian.localhost:3142"}}
               {:project "brian" :name "doc-room" :live? false :entry nil}])]
    ;; live row: clickable friendly-host link (registry :url) opening a new tab
    (is (str/includes? html "href=\"http://fix-login.brian.localhost:3142\""))
    (is (str/includes? html "target=\"_blank\""))
    ;; both sessions listed
    (is (str/includes? html "fix-login"))
    (is (str/includes? html "doc-room"))
    ;; down row: a real start button POSTing the lifecycle action (hiccup2
    ;; escapes the quotes to &apos; — the browser decodes them back)
    (is (str/includes? html "data-on:click"))
    (is (str/includes? html "/brian/sessions/doc-room/start"))))

(deftest live-board-page-renders-header-and-poll
  (let [html (views/live-board-page [])]
    (is (str/includes? html "live sessions"))
    ;; auto-refresh against the board SSE fragment
    (is (str/includes? html "/_fragment/live"))))
