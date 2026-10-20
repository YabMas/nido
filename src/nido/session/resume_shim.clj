(ns nido.session.resume-shim
  "Resume shim + run-link symlink for Run-owned sessions.

   Writes <session-home>/bin/claude (executable) and
   <session-home>/run-link symlink → <run-dir>.

   The shim reads run-link/run.edn at invocation time to discover the
   claude session-id, so typing `claude` from the session-home resumes
   the autonomous conversation. Falls through to a normal `claude` if
   the id is absent. Which way it continues is read off the run's session
   record: one with a :claude facet is a background session, attached to — the
   session keeps running when the person detaches. Only one whose record has no
   facet is a conversation from before the substrate, resumed with
   --dangerously-skip-permissions to match how the coordinator launched it (so
   a human picking up the parked session to apply/skip isn't prompted on every
   tool call).

   This covers promote → :full impl sessions too: the coordinator starts
   /continue-ticket as a background session at provision time (so the session is
   oriented and clear work is underway before the human arrives), and the
   human's first `claude` here attaches to that same session."
  (:require
   [babashka.fs :as fs]))

(def ^:private shim-script
  ;; The run's session record decides how its conversation is continued: one with a :claude
  ;; facet is a background session, attached to through `bb nido:session:attach`; only one positively read WITHOUT a facet is a
  ;; conversation from before the substrate, resumed with the permission skip it was launched
  ;; with. A record that cannot be read, or a listing that cannot show the session, ends here
  ;; naming why rather than guessing. With no conversation it is a plain `claude`, the person's.
  "#!/usr/bin/env bash
set -euo pipefail
HOME_DIR=\"$(dirname \"$0\")/..\"
RUN_EDN=\"$HOME_DIR/run-link/run.edn\"
SESSION_ID=\"\"
KIND=\"\"
if [ -f \"$RUN_EDN\" ]; then
  # run-link points at <nido root>/runs/<run-id>; the run's session record lives under that root.
  ROOT=$(cd -P \"$HOME_DIR/run-link\" 2>/dev/null && cd ../.. && pwd || true)
  read -r SESSION_ID KIND < <(RUN_EDN=\"$RUN_EDN\" ROOT=\"$ROOT\" bb -e \"$(cat <<'CLJ'
(let [run (clojure.edn/read-string (slurp (System/getenv \"RUN_EDN\")))
      k   (-> (str (:session-name run))
              (clojure.string/replace \"%\" \"%25\")
              (clojure.string/replace \"/\" \"%2F\")
              (clojure.string/replace \"\\\\\" \"%5C\"))
      p   (str (System/getenv \"ROOT\") \"/projects/\" (name (:project run)) \"/workstreams/\"
               (:workstream-id run) \"/sessions/\" k \"/session.edn\")
      rec (try (clojure.edn/read-string (slurp p)) (catch Exception _ nil))]
  (println (or (:claude-session-id run) \"-\")
           (cond (nil? rec) \"unknown\" (:claude rec) \"facet\" :else \"legacy\")))
CLJ
)\" 2>/dev/null || true)
  [ \"$SESSION_ID\" = \"-\" ] && SESSION_ID=\"\"
fi
if [ -n \"$SESSION_ID\" ]; then
  case \"$KIND\" in
    facet)
      # Through nido's substrate adapter, the one place that talks to Claude Code about a
      # background session; it says so when the session is not listed.
      exec bb --config \"$HOME_DIR/bb.edn\" nido:session:attach :session-id \"$SESSION_ID\"
      ;;
    legacy)
      exec claude --resume \"$SESSION_ID\" --dangerously-skip-permissions \"$@\"
      ;;
    *)
      echo \"nido: this run's session record could not be read, so how to resume its conversation is unknown\" >&2
      exit 1
      ;;
  esac
fi
exec claude \"$@\"
")

(defn ^{:malli/schema [:=> [:cat :Path :Path] :any]}
  write!
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

(defn ^{:malli/schema [:=> [:cat :Path] [:maybe :string]]}
  run-dir
  "The Run directory the session home `session-home` is linked to, or nil when it has no run
   link. Read before anything removes the home, so a restart can write the link back."
  [session-home]
  (let [link-path (fs/path session-home "run-link")]
    (when (fs/sym-link? link-path)
      (str (fs/read-link link-path)))))
