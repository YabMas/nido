(ns canvas.platform.desktop
  "Self-spec: `nido.platform.desktop` — native desktop notifications.

   Generic: it posts a title and a line, and knows nothing about what is being announced. macOS
   only, and a no-op elsewhere, so no caller branches on the platform. The icon on a notification
   is the sending app's, so nido's icon needs a sending app of nido's own: a terminal-notifier
   copy it builds, with osascript as the fallback when it is absent."
  (:require [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.vocab.code.module :refer [Module]]))

(Module platform-desktop
  "Posting a native notification."
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
     :delegates [notifier-app]}))
