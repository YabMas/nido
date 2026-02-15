(ns nido.merge
  (:require [clojure.string :as str]))

(defn- deep-merge
  "Recursively merge maps. Later values override earlier for non-map values."
  [& maps]
  (apply merge-with
         (fn [a b]
           (if (and (map? a) (map? b))
             (deep-merge a b)
             b))
         maps))

(defn- merge-tools
  "Merge tool definitions. MCP servers merge by key (project overrides global).
   Permissions concatenate. Shell tools merge by key."
  [global-tools project-tools]
  (cond
    (and (nil? global-tools) (nil? project-tools)) nil
    (nil? global-tools) project-tools
    (nil? project-tools) global-tools
    :else
    {:mcp-servers (merge (:mcp-servers global-tools)
                         (:mcp-servers project-tools))
     :shell-tools (merge (:shell-tools global-tools)
                         (:shell-tools project-tools))
     :permissions {:allow (vec (concat (get-in global-tools [:permissions :allow])
                                       (get-in project-tools [:permissions :allow])))
                   :deny (vec (concat (get-in global-tools [:permissions :deny])
                                      (get-in project-tools [:permissions :deny])))}}))

(defn- merge-settings
  "Deep merge settings. Project overrides global."
  [global-settings project-settings]
  (deep-merge global-settings project-settings))

(defn- merge-instructions
  "Concatenate instructions, separated by double newline."
  [global-instructions project-instructions]
  (let [parts (remove nil? [global-instructions project-instructions])]
    (when (seq parts)
      (str/join "\n\n" parts))))

(defn- merge-paired
  "Merge paired definitions (commands, skills, agents) by name. Project overrides global."
  [global-defs project-defs]
  (merge global-defs project-defs))

(defn- merge-rules
  "Concatenate rule lists."
  [global-rules project-rules]
  (vec (concat global-rules project-rules)))

(defn merge-definitions
  "Merge global and project definitions into effective definitions."
  [global-defs project-defs]
  (let [g (or global-defs {})
        p (or project-defs {})]
    {:tools (merge-tools (:tools g) (:tools p))
     :settings (merge-settings (:settings g) (:settings p))
     :instructions (merge-instructions (:instructions g) (:instructions p))
     :commands (merge-paired (:commands g) (:commands p))
     :skills (merge-paired (:skills g) (:skills p))
     :agents (merge-paired (:agents g) (:agents p))
     :rules (merge-rules (:rules g) (:rules p))}))
