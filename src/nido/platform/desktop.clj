(ns nido.platform.desktop
  "Native desktop notifications. macOS only: elsewhere `notify!` posts nothing
   and returns false, so a caller never branches on the platform.

   Two senders, because macOS takes a notification's icon from the app that
   posts it and offers no way to override it:

     nido-notifier.app  a copy of terminal-notifier carrying nido's icon and its
                        own bundle id, built by `install-notifier!`. Used
                        whenever it is installed.
     osascript          the fallback. Always present, and its notifications
                        appear as Script Editor's.

   A copy rather than a sender written here: macOS gave an AppleScript applet
   no notification permission at all, and a sender of our own needs a compiler
   this code cannot count on. The copy gets its own entry in System Settings ›
   Notifications, so its permission is asked for, and granted, separately."
  (:require
   [babashka.fs :as fs]
   [babashka.process :refer [shell]]
   [clojure.string :as str]))

(defn- mac? []
  (str/starts-with? (str/lower-case (System/getProperty "os.name" "")) "mac"))

(def ^:private bundle-id "fr.julienxx.oss.terminal-notifier.nido")

(defn ^{:malli/schema [:=> [:cat] :string]}
  notifier-app
  "Where the nido notifier app lives. In ~/Applications because LaunchServices,
   and so System Settings and `tccutil`, only resolve a bundle id to an app it
   finds in an Applications folder."
  []
  (str (fs/path (fs/home) "Applications" "nido-notifier.app")))

(defn- notifier-bin []
  (str (fs/path (notifier-app) "Contents" "MacOS" "terminal-notifier")))

(defn- applescript-string
  "`s` as an AppleScript string literal. Backslash and double quote are the only
   characters a literal escapes; newlines are folded to spaces because a
   notification shows one line of each field anyway."
  [s]
  (str "\""
       (-> (str s)
           (str/replace "\\" "\\\\")
           (str/replace "\"" "\\\"")
           (str/replace #"[\r\n]+" " "))
       "\""))

(defn ^{:malli/schema [:=> [:cat :map] :string]}
  notification-script
  "The AppleScript that posts `n`: {:message <s> :title <s?> :subtitle <s?>
   :sound <s?>}. `:sound` names a file in /System/Library/Sounds (\"Glass\");
   absent, the notification is silent."
  [{:keys [title subtitle message sound]}]
  (str "display notification " (applescript-string message)
       (when title    (str " with title "    (applescript-string title)))
       (when subtitle (str " subtitle "      (applescript-string subtitle)))
       (when sound    (str " sound name "    (applescript-string sound)))))

(defn ^{:malli/schema [:=> [:cat :map] [:vector :string]]}
  notifier-args
  "The terminal-notifier arguments that post `n` (see `notification-script`).
   Arguments, not a script, so nothing needs escaping — except a leading `-` or
   `[`, which terminal-notifier would read as an option, hence the `\\` it
   documents for exactly that."
  [{:keys [title subtitle message sound]}]
  (let [arg #(cond-> (str %) (re-find #"^[-\[]" (str %)) (->> (str "\\")))]
    (cond-> ["-message" (arg message)]
      title    (into ["-title" (arg title)])
      subtitle (into ["-subtitle" (arg subtitle)])
      sound    (into ["-sound" sound]))))

(defn- ok? [& cmd]
  (zero? (:exit (apply shell {:continue true :out :string :err :string} cmd))))

(defn ^{:malli/schema [:=> [:cat :map] :boolean]}
  notify!
  "Post `n` (see `notification-script`) as a native notification, from the nido
   notifier when it is installed and from osascript otherwise. Returns whether
   the sender accepted it — false too when the person has turned the nido
   notifier off, which is their call and not a reason to fall back. Never
   throws: a notification is never worth failing the caller over."
  [n]
  (boolean
   (when (mac?)
     (try
       (if (fs/executable? (notifier-bin))
         (apply ok? (notifier-bin) (notifier-args n))
         (ok? "osascript" "-e" (notification-script n)))
       (catch Exception _ false)))))

(defn- terminal-notifier-app
  "The terminal-notifier.app behind the `terminal-notifier` on PATH, or nil.
   Homebrew links the binary out of the bundle, so the bundle is the link's
   target's grandparent."
  []
  (when-let [bin (fs/which "terminal-notifier")]
    (let [app (fs/path (fs/parent (fs/parent (fs/real-path bin))) "terminal-notifier.app")]
      (when (fs/directory? app) (str app)))))

(def ^:private lsregister
  "/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister")

(defn ^{:malli/schema [:=> [:cat :string] :map]}
  install-notifier!
  "Build `notifier-app` from the installed terminal-notifier, with `icon-png`
   (a square PNG, at least 512px, already shaped as a macOS icon) as its icon.
   Replaces any previous build. Returns {:app <path>} or {:error <string>}.

   macOS asks the person once, on the first notification it sends, whether
   nido may post; until they allow it in System Settings › Notifications ›
   nido, `notify!` returns false."
  [icon-png]
  (let [src (terminal-notifier-app)
        app (notifier-app)]
    (cond
      (not (mac?))           {:error "the nido notifier is macOS-only"}
      (nil? src)             {:error "terminal-notifier is not installed — brew install terminal-notifier"}
      (not (fs/exists? icon-png)) {:error (str "no icon at " icon-png)}
      :else
      (fs/with-temp-dir [tmp {}]
        (let [iconset (fs/path tmp "nido.iconset")
              plist   (str (fs/path app "Contents" "Info.plist"))
              pb      #(ok? "/usr/libexec/PlistBuddy" "-c" % plist)]
          (fs/create-dirs iconset)
          (doseq [s [16 32 128 256 512]
                  [px suffix] [[s ""] [(* 2 s) "@2x"]]
                  :when (<= px 512)]
            (ok? "sips" "-z" (str px) (str px) icon-png
                 "--out" (str (fs/path iconset (str "icon_" s "x" s suffix ".png")))))
          (fs/delete-tree app)
          (fs/create-dirs (fs/parent app))
          (fs/copy-tree src app)
          (fs/delete-if-exists (fs/path app "Contents" "Resources" "en.lproj" "InfoPlist.strings"))
          (cond
            (not (ok? "iconutil" "-c" "icns" (str iconset)
                      "-o" (str (fs/path app "Contents" "Resources" "Terminal.icns"))))
            {:error "iconutil could not build the icon"}

            (not (and (pb (str "Set :CFBundleIdentifier " bundle-id))
                      (pb "Set :CFBundleName nido")
                      (or (pb "Set :CFBundleDisplayName nido")
                          (pb "Add :CFBundleDisplayName string nido"))))
            {:error "could not rewrite the app's Info.plist"}

            ;; The edits above break terminal-notifier's own signature; an app
            ;; whose signature does not verify is not launched at all.
            (not (ok? "codesign" "--force" "--deep" "-s" "-" app))
            {:error "codesign could not re-sign the app"}

            :else
            (do (ok? lsregister "-f" app)
                {:app app})))))))
