(ns tasks.nido-notify
  "Bb task entry point for nido's desktop notifications."
  (:require
   [clojure.java.io :as io]
   [nido.platform.desktop :as desktop]))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  install
  "Build the nido notifier app, then send one test notification through it —
   the first one is what makes macOS ask whether nido may post."
  [& _args]
  (let [icon (some-> (io/resource "nido-app-icon.png") io/file str)
        {:keys [app error]} (desktop/install-notifier! (or icon "resources/nido-app-icon.png"))]
    (if error
      (do (println (str "nido notifier: " error)) (System/exit 1))
      (do (println (str "nido notifier: installed " app))
          (if (desktop/notify! {:title   "nido"
                                :message "Desktop notifications will come from here"
                                :sound   "Glass"})
            (println "nido notifier: test notification sent")
            (println (str "nido notifier: macOS did not accept the test notification — allow it in"
                          " System Settings › Notifications › nido, then run this again")))))))
