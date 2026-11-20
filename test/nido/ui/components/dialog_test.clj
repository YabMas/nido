(ns nido.ui.components.dialog-test
  "The dialog's scenes, in a real browser.

   What the dialog promises is the platform's behaviour — the top layer, focus moving in and back
   out, Escape — and none of that exists in hiccup, so these run the catalog's own dialog page in
   headless Chrome through agent-browser. Where agent-browser is not installed they say so and
   assert nothing about the browser; the markup tests still run."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hiccup2.core :as h]
            [nido.libraries.ui.block :as block]
            [nido.ui.catalog :as catalog]
            [nido.ui.components.dialog :as dialog]))

(defn- router [& _] (throw (ex-info "the dialog routes no action" {})))

(defn- html [props] (str (h/html (block/render dialog/dialog "d" props {} router))))

(def ^:private props (:props (first (:scenes dialog/dialog))))

(deftest the-dialog-is-native-and-labelled-by-its-title
  (let [out (html props)]
    (is (str/includes? out "<dialog "))
    (is (str/includes? out "aria-labelledby=\"dialog:d:title\""))
    (is (str/includes? out "id=\"dialog:d:title\""))
    (is (str/includes? out "commandfor=\"dialog:d:root\" data-part=\"trigger\"")
        "the trigger names the dialog it opens")
    (is (str/includes? out "command=\"show-modal\""))
    (is (not (re-find #"<script|showModal|keydown" out))
        "no script opens, closes or traps it: the platform does")
    (is (not (re-find #"dialog\.d\.open" out)) "whether it is open is not a signal")))

(deftest every-slot-spreads-the-attrs-it-was-handed
  (let [out (html props)]
    (doseq [part ["trigger" "body" "footer" "title" "close"]]
      (is (str/includes? out (str "data-part=\"" part "\"")) part))))

;; ---------------------------------------------------------------------------
;; In a browser

(defn- browser? [] (some? (fs/which "agent-browser")))

(defn- ab [session & args]
  (let [{:keys [out err exit]} (apply p/shell {:out :string :err :string :continue true}
                                      "agent-browser" "--session" session args)]
    (when-not (zero? exit)
      (throw (ex-info (str "agent-browser " (str/join " " args) " failed: " err out) {})))
    (str/trim out)))

(defn- trigger-label [scene] (last ((get-in scene [:props :trigger]) {})))

(deftest each-scene-opens-by-its-trigger-and-closes-on-escape
  (if-not (browser?)
    (println "nido.ui.components.dialog-test: agent-browser is not installed; the browser run is skipped")
    (let [dir     (fs/create-temp-dir)
          page    (fs/path dir "dialog.html")
          session (str "nido-dialog-test-" (System/nanoTime))]
      (spit (str page) (catalog/catalog-page "block=dialog"))
      (try
        (ab session "--args" "--no-sandbox" "open" (str "file://" page))
        (doseq [[i scene] (map-indexed vector (:scenes dialog/dialog))
                :let [title (get-in scene [:props :title])
                      id    (str "dialog:scene-" i ":root")]]
          (testing (:title scene)
            (ab session "find" "role" "button" "click" "--name" (trigger-label scene))
            (is (str/includes? (ab session "snapshot") (str "dialog \"" title "\""))
                "found by its role and its title")
            (is (str/includes? (ab session "eval" (str "document.getElementById('" id "').open")) "true"))
            (ab session "press" "Escape")
            (is (str/includes? (ab session "eval" (str "document.getElementById('" id "').open")) "false")
                "Escape closes it")
            (is (str/includes? (ab session "eval" "document.activeElement.id")
                               (str "dialog:scene-" i ":trigger"))
                "and focus is back on the trigger")))
        (finally
          (p/shell {:continue true :out :string :err :string} "agent-browser" "--session" session "close")
          (fs/delete-tree dir))))))
