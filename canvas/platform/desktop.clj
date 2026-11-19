(ns canvas.platform.desktop
  "Self-spec: `nido.platform.desktop` — this machine's desktop: a terminal tab, a browser, a
   native notification. Reached only through platform-desk, which decides whether the person is
   at this machine at all.

   Generic: it opens what it is given and posts a title and a line, and knows nothing about what
   is being opened or announced. Terminal tabs are Warp's, the one terminal whose URI scheme opens
   a tab in the current window. Notifications are macOS only, and a no-op elsewhere, so no caller
   branches on the platform. The icon on a notification
   is the sending app's, so nido's icon needs a sending app of nido's own: a terminal-notifier
   copy it builds, with osascript as the fallback when it is absent."
  (:require [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.vocab.code.module :refer [Module]]))

(Module platform-desktop
  "Opening a terminal tab or a URL, and posting a native notification, on this machine."
  (Operation notification-script
    "The AppleScript that posts one notification, its strings escaped. Separate from posting so
     the escaping — the part that can go wrong silently — is checkable without a desktop."
    {:signature [:=> [:catn [:n :map]] :string]})
  (Operation notifier-args
    "The same notification as terminal-notifier arguments."
    {:signature [:=> [:catn [:n :map]] [:vector :string]]})
  (Operation notifier-app "Where the nido notifier app is installed."
    {:signature [:=> [:catn] :string]})
  (Operation notify!
    "Post one notification, from the nido notifier when installed and osascript otherwise;
     whether it was accepted. Never throws: a notification is never worth failing the caller
     over."
    {:signature [:=> [:catn [:n :map]] :boolean]
     :delegates [notification-script notifier-args notifier-app]})
  (Operation install-notifier!
    "Build the nido notifier app from the installed terminal-notifier and an icon."
    {:signature [:=> [:catn [:icon-png :string]] :map]
     :delegates [notifier-app]})
  (Operation open-terminal!
    "Open a Warp tab in the current window at `:dir`, running `:command` as though typed at its
     prompt when given — through a one-use tab config, since Warp's new-tab URI cannot carry a
     command. Whether `open` accepted it."
    {:signature [:=> [:catn [:terminal :map]] :boolean]})
  (Operation open-url!
    "Open a URL in the default browser without waiting for it; whether an opener was found."
    {:signature [:=> [:catn [:url :string]] :boolean]}))
