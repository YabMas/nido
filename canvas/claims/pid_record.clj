(ns canvas.claims.pid-record
  "The claims the pid-record design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.session.service :refer [start-service! stop-service! stop-recorded!]]
            [canvas.session.provisioning :refer [stop-session!]]
            [canvas.session.lifecycle :refer [destroy!]]
            [canvas.session.state :refer [pid-file pid-files]]))

(Claim pid-recorded-as-it-detaches
  "The :process start-service! has the shell command that detaches the process write that process's pid to the instance's pid file, and takes the pid it uses from that file, so no check of the shell's exit or output stands between the detach and the write."
  {:about [start-service! pid-file] :evidence "test"})

(Claim one-recorded-process-per-service
  "When a :process start-service! spawns, no process its instance's pid file named on entry and that started no later than the file was written is still running; if one cannot be stopped, the start throws without spawning."
  {:about [start-service! pid-file] :evidence "test"})

(Claim ownership-before-signal
  "In the :process service's methods, a process known only through a pid file is signalled only when it started no later than the file's last write; a later-started process is left running and only the stale file is removed; a process whose start instant cannot be read is left running, its file is kept, and the call throws."
  {:about [start-service! stop-service!] :evidence "test"})

(Claim record-goes-with-its-process
  "The :process service's methods delete its pid file only when the process it names is not running or is shown not to be this instance's; a process that survives its stop keeps its file and the stop throws."
  {:about [stop-service! stop-recorded! pid-file] :evidence "test"})

(Claim down-reaches-the-record
  "stop-session! stops each process a declared :process service's pid file names that is shown to be this instance's own, whether or not session.edn exists and whether or not session.edn's saved pid is the one the file names; a named process whose ownership cannot be shown, or that survives its stop, is left running with its pid file kept and the failure reported."
  {:about [stop-session!] :evidence "test"})

(Claim destroy-keeps-a-live-record
  "destroy! deletes an instance's state dir only when its stop left no pid file in it; a pid file left names a process the stop could not show was gone, and the dir stays as its record."
  {:about [destroy! pid-files] :evidence "test"})
