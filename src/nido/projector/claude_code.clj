(ns nido.projector.claude-code
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [nido.io :as io]))

;; --- CLAUDE.md generation ---

(defn- shell-tools-section
  "Generate a markdown section documenting available shell tools."
  [shell-tools]
  (when (seq shell-tools)
    (str "## Shell Tools\n\n"
         (str/join "\n\n"
                   (for [[tool-name tool-def] (sort-by key shell-tools)]
                     (str "### " (name tool-name) "\n"
                          (when (:description tool-def)
                            (str (:description tool-def) "\n"))
                          (when (:command tool-def)
                            (str "\nCommand: `" (:command tool-def) "`\n"))))))))

(defn- generate-claude-md
  "Generate CLAUDE.md content with target-dir preamble + instructions + shell tools."
  [target-dir effective-defs]
  (let [preamble (str "# Project\n\n"
                      "Target directory: " target-dir "\n\n"
                      "All file operations should target the project directory above.\n")
        instructions (:instructions effective-defs)
        shell-tools (get-in effective-defs [:tools :shell-tools])
        shell-docs (shell-tools-section shell-tools)
        parts (remove nil? [preamble instructions shell-docs])]
    (str/join "\n\n---\n\n" parts)))

;; --- .mcp.json generation ---

(defn- mcp-server-entry
  "Convert an MCP server definition to the .mcp.json format."
  [server-def]
  (let [base {:type (or (some-> (:type server-def) name) "stdio")
              :command (:command server-def)
              :args (vec (:args server-def))}]
    (cond-> base
      (seq (:env server-def)) (assoc :env (:env server-def)))))

(defn- generate-mcp-json
  "Generate .mcp.json data from MCP server definitions."
  [mcp-servers]
  (when (seq mcp-servers)
    {"mcpServers"
     (into {}
           (for [[server-name server-def] mcp-servers]
             [(name server-name) (mcp-server-entry server-def)]))}))

;; --- .claude/settings.json generation ---

(defn- collect-permission-patterns
  "Collect permission patterns from shell tools."
  [shell-tools]
  (->> shell-tools
       vals
       (keep :permission-pattern)
       vec))

(defn- generate-settings-json
  "Generate .claude/settings.json from settings and tools."
  [effective-defs]
  (let [settings (:settings effective-defs)
        tools (:tools effective-defs)
        shell-tool-patterns (collect-permission-patterns (:shell-tools tools))
        allow-perms (vec (concat (get-in tools [:permissions :allow])
                                 shell-tool-patterns))
        deny-perms (get-in tools [:permissions :deny])
        result {}
        result (if (seq allow-perms)
                 (assoc result "permissions" {"allow" allow-perms})
                 result)
        result (if (seq deny-perms)
                 (assoc-in result ["permissions" "deny"] deny-perms)
                 result)
        result (if (:model settings)
                 (assoc result "model" (:model settings))
                 result)
        result (if (seq (:env settings))
                 (assoc result "env" (:env settings))
                 result)]
    result))

(defn- generate-settings-local-json
  "Generate .claude/settings.local.json from provider-specific settings."
  [effective-defs]
  (let [claude-settings (get-in effective-defs [:settings :claude-code])]
    (when (seq claude-settings)
      (into {} (map (fn [[k v]] [(name k) v]) claude-settings)))))

;; --- Commands generation ---

(defn- yaml-frontmatter
  "Generate YAML frontmatter from a config map."
  [config]
  (let [lines (for [[k v] config
                    :when (some? v)]
                (cond
                  (string? v) (str (name k) ": " (pr-str v))
                  (keyword? v) (str (name k) ": " (name v))
                  (sequential? v) (str (name k) ": "
                                       "[" (str/join ", " (map pr-str v)) "]")
                  :else (str (name k) ": " v)))]
    (when (seq lines)
      (str "---\n" (str/join "\n" lines) "\n---\n"))))

(defn- generate-command-md
  "Generate a command .md file with YAML frontmatter."
  [{:keys [config body]}]
  (let [frontmatter (yaml-frontmatter
                     (select-keys config [:description :argument-hint
                                          :allowed-tools :model]))]
    (str (or frontmatter "") "\n" (or body ""))))

;; --- Skills generation ---

(defn- generate-skill-md
  "Generate a skill SKILL.md file with YAML frontmatter."
  [{:keys [config body]}]
  (let [fm-data (cond-> (select-keys config [:name :description :allowed-tools :model])
                  (get-in config [:claude-code :user-invocable])
                  (assoc :user-invocable true))
        frontmatter (yaml-frontmatter fm-data)]
    (str (or frontmatter "") "\n" (or body ""))))

;; --- Agents generation ---

(defn- generate-agent-md
  "Generate an agent .md file with YAML frontmatter."
  [{:keys [config body]}]
  (let [frontmatter (yaml-frontmatter
                     (select-keys config [:name :description :tools :model]))]
    (str (or frontmatter "") "\n" (or body ""))))

;; --- Main projection ---

(defn project!
  "Generate all Claude Code workspace files in workspace-dir."
  [workspace-dir target-dir effective-defs]
  (let [claude-dir (str (fs/path workspace-dir ".claude"))]
    ;; Clean workspace
    (when (fs/exists? workspace-dir)
      (fs/delete-tree workspace-dir))
    (fs/create-dirs claude-dir)

    ;; CLAUDE.md
    (io/write-text! (str (fs/path workspace-dir "CLAUDE.md"))
                    (generate-claude-md target-dir effective-defs))

    ;; .mcp.json
    (when-let [mcp-data (generate-mcp-json (get-in effective-defs [:tools :mcp-servers]))]
      (io/write-json! (str (fs/path workspace-dir ".mcp.json")) mcp-data))

    ;; .claude/settings.json
    (let [settings (generate-settings-json effective-defs)]
      (when (seq settings)
        (io/write-json! (str (fs/path claude-dir "settings.json")) settings)))

    ;; .claude/settings.local.json
    (when-let [local-settings (generate-settings-local-json effective-defs)]
      (io/write-json! (str (fs/path claude-dir "settings.local.json")) local-settings))

    ;; Commands
    (doseq [[cmd-name cmd-def] (:commands effective-defs)]
      (let [cmd-dir (str (fs/path claude-dir "commands"))]
        (fs/create-dirs cmd-dir)
        (io/write-text! (str (fs/path cmd-dir (str cmd-name ".md")))
                        (generate-command-md cmd-def))))

    ;; Skills
    (doseq [[skill-name skill-def] (:skills effective-defs)]
      (let [skill-dir (str (fs/path claude-dir "skills" skill-name))]
        (fs/create-dirs skill-dir)
        (io/write-text! (str (fs/path skill-dir "SKILL.md"))
                        (generate-skill-md skill-def))))

    ;; Agents
    (doseq [[agent-name agent-def] (:agents effective-defs)]
      (let [agents-dir (str (fs/path claude-dir "agents"))]
        (fs/create-dirs agents-dir)
        (io/write-text! (str (fs/path agents-dir (str agent-name ".md")))
                        (generate-agent-md agent-def))))

    ;; Rules
    (doseq [{:keys [name body]} (:rules effective-defs)]
      (let [rules-dir (str (fs/path claude-dir "rules"))]
        (fs/create-dirs rules-dir)
        (io/write-text! (str (fs/path rules-dir (str name ".md"))) body)))

    workspace-dir))
