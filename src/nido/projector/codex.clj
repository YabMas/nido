(ns nido.projector.codex
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [nido.io :as io]))

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

(defn- generate-agents-md
  "Generate AGENTS.md content with target-dir preamble + instructions."
  [target-dir effective-defs]
  (let [preamble (str "# Project\n\n"
                      "Target directory: " target-dir "\n\n"
                      "All file operations should target the project directory above.\n")
        instructions (:instructions effective-defs)
        shell-tools (get-in effective-defs [:tools :shell-tools])
        shell-docs (shell-tools-section shell-tools)
        parts (remove nil? [preamble instructions shell-docs])]
    (str/join "\n\n---\n\n" parts)))

(defn- edn->toml-value
  "Convert an EDN value to a TOML string representation."
  [v]
  (cond
    (string? v) (str "\"" (str/replace v "\"" "\\\"") "\"")
    (keyword? v) (str "\"" (name v) "\"")
    (boolean? v) (str v)
    (number? v) (str v)
    :else (str "\"" v "\"")))

(defn- generate-config-toml
  "Generate .codex/config.toml from codex-specific settings."
  [effective-defs]
  (let [codex-settings (get-in effective-defs [:settings :codex])]
    (when (seq codex-settings)
      (str/join "\n"
                (for [[k v] codex-settings]
                  (str (name k) " = " (edn->toml-value v)))))))

(defn project!
  "Generate all Codex workspace files in workspace-dir."
  [workspace-dir target-dir effective-defs]
  (let [codex-dir (str (fs/path workspace-dir ".codex"))]
    ;; Clean workspace
    (when (fs/exists? workspace-dir)
      (fs/delete-tree workspace-dir))
    (fs/create-dirs codex-dir)

    ;; AGENTS.md
    (io/write-text! (str (fs/path workspace-dir "AGENTS.md"))
                    (generate-agents-md target-dir effective-defs))

    ;; .codex/config.toml
    (when-let [toml (generate-config-toml effective-defs)]
      (io/write-text! (str (fs/path codex-dir "config.toml")) toml))

    workspace-dir))
