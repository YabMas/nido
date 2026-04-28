(ns nido.ci.isolated-app
  "Per-step app port allocation for CI steps that need to bring up
   their own application server (typically e2e tests driving an
   in-process brian/whatever HTTP app).

   Mechanism is intentionally narrow: nido reserves a free port from
   the project's app-port range, rewrites the work-dir's local.edn so
   the spawned app picks it up, and surfaces the chosen port to the
   step substitution context (`{{step.app-port}}`) and env
   (`NIDO_APP_PORT`). The JVM that actually serves the app is started
   via the existing ci-step `:services` machinery — that side handles
   process-group spawn, healthcheck, and teardown.

   Why a separate ns from `nido.ci.isolated-pg`: the two are
   independent (you can opt into either, neither, or both), and
   keeping their wiring local makes the lifecycle integration easy
   to read. If a project genuinely needs more than one app port per
   step, that's a follow-up — single-app coverage solves the
   integration ↔ e2e isolation problem brian asked for."
  (:require
   [nido.core :as core]
   [nido.process :as proc]))

(def ^:private default-port-range
  "App-port band shared with `nido.ci.session` and the session layer
   (3100–5100). Free-port scanning starts at a deterministic offset
   inside the range so concurrent step-runs in the same matrix don't
   collide on first probe."
  [3100 5100])

(defn start!
  "Reserve a free app port for the step. Returns
   `{:app-port <port>}` — the actual JVM that binds the port is
   spawned by the step's `:services` (or by the step's `:command`
   itself, for projects whose app boots inline).

   `work-dir` is used only to seed the deterministic-port hash so the
   same step in the same run lands on the same preferred port across
   re-runs (helpful when debugging)."
  [{:keys [work-dir port-range]
    :or {port-range default-port-range}}]
  (let [[low high] port-range
        preferred (proc/deterministic-port (str work-dir) low high)
        app-port (proc/find-available-port preferred (- high low))]
    (core/log-step (str "Reserved isolated app port " app-port))
    {:app-port app-port}))

(defn stop!
  "No-op. Port allocation is a pure reservation; the JVM that bound
   the port is owned by ci :services teardown."
  [_]
  nil)
