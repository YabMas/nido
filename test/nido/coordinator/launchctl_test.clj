(ns nido.coordinator.launchctl-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.launchctl :as lc]))

(defn- with-tmp-home [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [lc/launch-agents-dir (constantly (str tmp))]
        (f tmp))
      (finally (fs/delete-tree tmp)))))

(deftest plist-path-lives-under-launch-agents
  (with-tmp-home
    (fn [tmp]
      (is (= (str (fs/path tmp "dev.nido.coordinator.plist"))
             (lc/plist-path))))))

(deftest label-is-stable
  (is (= "dev.nido.coordinator" (lc/label))))

(deftest installed?-false-when-no-plist
  (with-tmp-home
    (fn [_] (is (false? (lc/installed?))))))

(deftest installed?-true-when-plist-file-exists
  (with-tmp-home
    (fn [_]
      (spit (lc/plist-path) "stub")
      (is (true? (lc/installed?))))))

(def ^:private expected-plist
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
       "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
       "<plist version=\"1.0\">\n"
       "<dict>\n"
       "    <key>Label</key>\n"
       "    <string>dev.nido.coordinator</string>\n"
       "    <key>ProgramArguments</key>\n"
       "    <array>\n"
       "        <string>/opt/homebrew/bin/bb</string>\n"
       "        <string>nido:coordinator:run</string>\n"
       "    </array>\n"
       "    <key>WorkingDirectory</key>\n"
       "    <string>/Users/yabmas/Code/nido</string>\n"
       "    <key>RunAtLoad</key>\n"
       "    <true/>\n"
       "    <key>KeepAlive</key>\n"
       "    <true/>\n"
       "    <key>ThrottleInterval</key>\n"
       "    <integer>10</integer>\n"
       "    <key>StandardOutPath</key>\n"
       "    <string>/Users/yabmas/.nido/coordinator/coordinator.log</string>\n"
       "    <key>StandardErrorPath</key>\n"
       "    <string>/Users/yabmas/.nido/coordinator/coordinator.log</string>\n"
       "    <key>EnvironmentVariables</key>\n"
       "    <dict>\n"
       "        <key>PATH</key>\n"
       "        <string>/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin</string>\n"
       "    </dict>\n"
       "</dict>\n"
       "</plist>\n"))

(deftest render-plist-matches-golden
  (is (= expected-plist
         (lc/render-plist {:bb-path  "/opt/homebrew/bin/bb"
                           :nido-dir "/Users/yabmas/Code/nido"
                           :log-path "/Users/yabmas/.nido/coordinator/coordinator.log"
                           :path-env "/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin"}))))
