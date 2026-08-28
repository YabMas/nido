(ns canvas.platform.core
  "Self-spec: `nido.platform.core` — the nido home, and the seam tests redirect.

   The floor's floor: everything that needs to know where `~/.nido` is asks here, and nothing
   here asks anything else in the project."
  (:require [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.vocab.code.module :refer [Module]]))

(Module platform-core
  "Where nido lives on disk, and the single redirect seam for pointing a test run elsewhere."
  (Operation nido-home
    "The nido home directory — ~/.nido, or $NIDO_HOME."
    {:signature [:=> [:catn] :string]})
  (Operation nido-source-dir
    "The directory holding nido's bb.edn, derived from where this namespace was loaded so it is
     right regardless of the caller's cwd."
    {:signature [:=> [:catn] :string]})
  (Operation nido-root
    "The nido home as the single redirect seam. Deliberately distinct from nido-home: the
     session band addresses nido-home directly and is NOT redirected by it, which is what keeps
     a redirected test looking at real session state."
    {:signature [:=> [:catn] :string]
     :delegates [nido-home]})
  (Operation project-file
    "Path to a per-project definition file under ~/.nido/projects/<project>/."
    {:signature [:=> [:catn [:project [:or :string :keyword]] [:filename :string]] :string]
     :delegates [nido-root]})
  (Operation log-step
    "Print one nido status line, synchronised so concurrent futures cannot interleave."
    {:signature [:=> [:catn [:message :string]] :nil]})
  (Operation now-iso
    "The current instant as an ISO-8601 string."
    {:signature [:=> [:catn] :string]})
  (Operation ensure-nido-home!
    "Create the ~/.nido skeleton, returning the home path."
    {:signature [:=> [:catn] :string]
     :delegates [nido-home log-step]}))
