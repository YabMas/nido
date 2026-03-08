(ns nido.vsdd.prompts
  "Loads built-in VSDD agent prompts from nido resources."
  (:require [clojure.java.io :as jio]))

(def ^:private role->file
  {:critic       "vsdd/agents/critic.md"
   :implementer  "vsdd/agents/implementer.md"
   :architect    "vsdd/agents/architect.md"
   :analyst      "vsdd/agents/analyst.md"})

(def ^:private default-tools
  "Default tool restrictions per role."
  {:critic       ["Read" "Glob" "Grep" "Bash" "Write"]
   :implementer  ["Read" "Edit" "Write" "Glob" "Grep" "Bash"]
   :architect    ["Read" "Edit" "Write" "Glob" "Grep"]
   :analyst      []})

(defn load-agent-prompt
  "Load a built-in VSDD agent prompt by role keyword.
   Returns the markdown content string, or nil if not found."
  [role]
  (when-let [file (role->file role)]
    (when-let [resource (jio/resource file)]
      (slurp resource))))

(defn tools-for-role
  "Return the default tool list for a VSDD role."
  [role]
  (get default-tools role []))
