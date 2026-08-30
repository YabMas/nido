(ns tasks.nido-session-link
  "Bb task entry points for per-session link tracking — notion tickets,
   GitHub PRs, slack threads, and any other context-worth URLs the agent
   should carry across sessions.

   Surface:
     add     append/replace by :url
     remove  drop by :url
     list    print all links grouped by type

   project + session may be passed explicitly with :project <p> <session>,
   or auto-resolved from cwd when invoked from a session home
   (~/.nido/sessions/<project>/<session>/).

   Examples:
     bb nido:session:link:add :type :pr :url https://github.com/foo/bar/pull/1
     bb nido:session:link:add :type :notion-ticket :url https://notion.so/... :title \"Onboarding\"
     bb nido:session:link:add :project brian feat-auth :type :slack-thread :url https://slack.com/...
     bb nido:session:link:remove :url https://github.com/foo/bar/pull/1
     bb nido:session:link:list   :project brian feat-auth"
  (:require
   [nido.session.lifecycle :as lifecycle]
   [nido.platform.task-args :as task-args]))

(def ^:private raw-string-keys
  "Kwarg keys whose values must be passed through verbatim — EDN-parsing
   loses information for URLs (with `/digits` int suffix) and multi-word
   titles (read-string consumes only the first form)."
  #{:url :title})

(defn- session-positional [positionals]
  (case (count positionals)
    0 nil
    1 (str (first positionals))
    (throw (ex-info "Too many positional args; expected at most one session name"
                    {:positionals positionals}))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  add
  "Append/replace a link by :url on the resolved session."
  [& args]
  (let [[pos opts] (task-args/split-args args raw-string-keys)
        session    (session-positional pos)]
    (lifecycle/link-add! session opts)))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  remove-cmd
  "Drop a link by :url from the resolved session."
  [& args]
  (let [[pos opts] (task-args/split-args args raw-string-keys)
        session    (session-positional pos)]
    (lifecycle/link-remove! session opts)))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  list-cmd
  "Print the resolved session's links."
  [& args]
  (let [[pos opts] (task-args/split-args args raw-string-keys)
        session    (session-positional pos)]
    (lifecycle/link-list session opts)))
