(ns nido.coordinator.shim
  "Resume shim + run-link symlink for Run-owned sessions.

   Writes <session-home>/bin/claude (executable) and
   <session-home>/run-link symlink → <run-dir>.

   The shim reads run-link/run.edn at invocation time to discover the
   claude session-id, so typing `claude` from the session-home resumes
   the autonomous conversation. Falls through to a normal `claude` if
   the id is absent. See spec §Agent launch / Resume from the
   session-home."
  (:require
   [babashka.fs :as fs]))

(def ^:private shim-script
  "#!/usr/bin/env bash
set -euo pipefail
RUN_EDN=\"$(dirname \"$0\")/../run-link/run.edn\"
SESSION_ID=\"\"
if [ -f \"$RUN_EDN\" ]; then
  SESSION_ID=$(bb -e \"(-> (slurp \\\"$RUN_EDN\\\") clojure.edn/read-string :claude-session-id)\" 2>/dev/null || true)
fi
if [ -n \"$SESSION_ID\" ] && [ \"$SESSION_ID\" != \"nil\" ]; then
  exec command claude --resume \"$SESSION_ID\" \"$@\"
fi
exec command claude \"$@\"
")

(defn write!
  "Write the shim + run-link in the given session-home pointing at run-dir."
  [session-home run-dir]
  (let [bin-dir   (fs/path session-home "bin")
        shim-path (fs/path bin-dir "claude")
        link-path (fs/path session-home "run-link")]
    (fs/create-dirs bin-dir)
    (spit (str shim-path) shim-script)
    (fs/set-posix-file-permissions shim-path "rwxr-xr-x")
    (when (fs/exists? link-path) (fs/delete link-path))
    (fs/create-sym-link link-path run-dir)))
