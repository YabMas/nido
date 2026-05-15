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

(deftest write-plist!-creates-the-file
  (with-tmp-home
    (fn [_]
      (lc/write-plist! "stub-contents")
      (is (lc/installed?))
      (is (= "stub-contents" (slurp (lc/plist-path)))))))

(deftest write-plist!-creates-parent-dir-if-missing
  (with-tmp-home
    (fn [tmp]
      (fs/delete-tree tmp)
      (lc/write-plist! "stub")
      (is (fs/exists? (lc/plist-path))))))

(deftest write-plist!-overwrites-existing
  (with-tmp-home
    (fn [_]
      (lc/write-plist! "first")
      (lc/write-plist! "second")
      (is (= "second" (slurp (lc/plist-path)))))))

(deftest remove-plist!-noop-when-absent
  (with-tmp-home
    (fn [_]
      (lc/remove-plist!) ; must not throw
      (is (false? (lc/installed?))))))

(deftest remove-plist!-deletes-existing
  (with-tmp-home
    (fn [_]
      (lc/write-plist! "stub")
      (lc/remove-plist!)
      (is (false? (lc/installed?))))))

(defn- stub-sh
  "Return a fake sh! that records its calls and returns the given result."
  [calls result]
  (fn [args]
    (swap! calls conj args)
    result))

(deftest target-uses-current-uid
  (is (re-matches #"gui/\d+/dev\.nido\.coordinator" (lc/target))))

(deftest loaded?-true-when-launchctl-print-exits-0
  (with-redefs [lc/sh! (stub-sh (atom []) {:exit 0 :out "" :err ""})]
    (is (true? (lc/loaded?)))))

(deftest loaded?-false-when-launchctl-print-exits-nonzero
  (with-redefs [lc/sh! (stub-sh (atom []) {:exit 113 :out "" :err "Could not find service"})]
    (is (false? (lc/loaded?)))))

(deftest bootstrap!-shells-launchctl-bootstrap
  (let [calls (atom [])]
    (with-redefs [lc/sh! (stub-sh calls {:exit 0 :out "" :err ""})]
      (lc/bootstrap!)
      (is (= 1 (count @calls)))
      (let [[args] @calls]
        (is (= "launchctl" (first args)))
        (is (= "bootstrap" (second args)))
        (is (re-matches #"gui/\d+" (nth args 2)))
        (is (= (lc/plist-path) (nth args 3)))))))

(deftest bootout!-shells-launchctl-bootout
  (let [calls (atom [])]
    (with-redefs [lc/sh! (stub-sh calls {:exit 0 :out "" :err ""})]
      (lc/bootout!)
      (let [[args] @calls]
        (is (= ["launchctl" "bootout"] (take 2 args)))
        (is (= (lc/target) (nth args 2)))))))

(deftest kickstart!-shells-launchctl-kickstart-with--k
  (let [calls (atom [])]
    (with-redefs [lc/sh! (stub-sh calls {:exit 0 :out "" :err ""})]
      (lc/kickstart!)
      (let [[args] @calls]
        (is (= ["launchctl" "kickstart" "-k"] (take 3 args)))
        (is (= (lc/target) (nth args 3)))))))
