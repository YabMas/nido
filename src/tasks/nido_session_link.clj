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
   [clojure.edn :as edn]
   [nido.session.lifecycle :as lifecycle]))

(defn- parse-token
  "Parse a CLI token as EDN, with one carve-out: top-level symbols stay as
   their original string. `bb nido:session:up :base origin/main` would
   otherwise produce the symbol `origin/main`, which crashes anything that
   runs a regex or string operation on it. Other shapes (keywords, numbers,
   booleans, vectors, maps) parse as usual — including vectors of symbols
   like `[dev cider/nrepl]`, where downstream code expects them."
  [tok]
  (let [parsed (try (edn/read-string tok) (catch Exception _ tok))]
    (if (symbol? parsed) tok parsed)))

(defn- keyword-token? [tok]
  (and (string? tok) (.startsWith ^String tok ":")))

(def ^:private raw-string-keys
  "Kwarg keys whose values must be passed through verbatim — EDN-parsing
   loses information for URLs (with `/digits` int suffix) and multi-word
   titles (read-string consumes only the first form)."
  #{:url :title})

(defn- split-args
  "Split CLI args into [positionals opts-map]. A token starting with ':' is
   a kwarg key and consumes the next token as its value; every other token
   is a positional. Values for `:url` and `:title` are kept as raw strings."
  [args]
  (loop [xs args, pos [], opts {}]
    (if (empty? xs)
      [pos opts]
      (let [x (first xs)]
        (if (keyword-token? x)
          (let [k (parse-token x)
                v (second xs)]
            (when-not (some? v)
              (throw (ex-info (str "Missing value for " x) {:args args})))
            (recur (drop 2 xs) pos
                   (assoc opts k (if (contains? raw-string-keys k)
                                   (str v)
                                   (parse-token v)))))
          (recur (rest xs) (conj pos x) opts))))))

(defn- session-positional [positionals]
  (case (count positionals)
    0 nil
    1 (str (first positionals))
    (throw (ex-info "Too many positional args; expected at most one session name"
                    {:positionals positionals}))))

(defn add
  "Append/replace a link by :url on the resolved session."
  [& args]
  (let [[pos opts] (split-args args)
        session    (session-positional pos)]
    (lifecycle/link-add! session opts)))

(defn remove-cmd
  "Drop a link by :url from the resolved session."
  [& args]
  (let [[pos opts] (split-args args)
        session    (session-positional pos)]
    (lifecycle/link-remove! session opts)))

(defn list-cmd
  "Print the resolved session's links."
  [& args]
  (let [[pos opts] (split-args args)
        session    (session-positional pos)]
    (lifecycle/link-list session opts)))
