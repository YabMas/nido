(ns nido.session.run-link
  "The link from a Run-owned session's home to its Run: <session-home>/run-link → <run-dir>.

   The session's skills read and write the Run's status and artifacts through it, the briefing
   reads the Run's workstream through it, and a restart reads it back to carry the Run across the
   stop that removes the home."
  (:require
   [babashka.fs :as fs]))

(defn ^{:malli/schema [:=> [:cat :Path :Path] :any]}
  write!
  "Point `session-home`'s run-link at `run-dir`, replacing any link there. Removes the bin/claude
   a session home once carried, which resumed the Run's conversation with --print-era flags; a
   person enters a background session by attaching to it."
  [session-home run-dir]
  (let [link-path (fs/path session-home "run-link")
        old-shim  (fs/path session-home "bin" "claude")]
    (when (fs/exists? old-shim) (fs/delete old-shim))
    (when (or (fs/exists? link-path) (fs/sym-link? link-path)) (fs/delete link-path))
    (fs/create-sym-link link-path run-dir)))

(defn ^{:malli/schema [:=> [:cat :Path] [:maybe :string]]}
  run-dir
  "The Run directory the session home `session-home` is linked to, or nil when it has no run
   link. Read before anything removes the home, so a restart can write the link back."
  [session-home]
  (let [link-path (fs/path session-home "run-link")]
    (when (fs/sym-link? link-path)
      (str (fs/read-link link-path)))))
