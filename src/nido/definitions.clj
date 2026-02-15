(ns nido.definitions
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [nido.io :as io]))

(defn- load-paired-definitions
  "Load name.edn + name.md pairs from a directory.
   Returns {name {:config {...} :body \"...\"}}"
  [dir]
  (when (fs/exists? dir)
    (let [edn-files (->> (fs/list-dir dir)
                         (filter #(str/ends-with? (str %) ".edn"))
                         (map str))]
      (into {}
            (for [edn-file edn-files
                  :let [name (str/replace (str (fs/file-name edn-file)) #"\.edn$" "")
                        md-file (str (fs/path dir (str name ".md")))
                        config (io/read-edn edn-file)
                        body (io/read-text md-file)]]
              [name {:config config :body body}])))))

(defn- load-skill-definitions
  "Load skills/<name>/skill.edn + skill.md pairs from a directory.
   Returns {name {:config {...} :body \"...\"}}"
  [dir]
  (when (fs/exists? dir)
    (let [subdirs (->> (fs/list-dir dir)
                       (filter fs/directory?)
                       (map str))]
      (into {}
            (for [subdir subdirs
                  :let [name (str (fs/file-name subdir))
                        edn-file (str (fs/path subdir "skill.edn"))
                        md-file (str (fs/path subdir "skill.md"))]
                  :when (fs/exists? edn-file)]
              [name {:config (io/read-edn edn-file)
                     :body (io/read-text md-file)}])))))

(defn- load-rules
  "Load all .md files from a rules directory.
   Returns [{:name \"rule-name\" :body \"...\"}]"
  [dir]
  (when (fs/exists? dir)
    (->> (fs/list-dir dir)
         (filter #(str/ends-with? (str %) ".md"))
         (map (fn [f]
                {:name (str/replace (str (fs/file-name f)) #"\.md$" "")
                 :body (slurp (str f))}))
         (sort-by :name)
         vec)))

(defn load-definitions
  "Load all definition types from a directory.
   Returns {:tools {...} :settings {...} :instructions \"...\"
            :commands {...} :skills {...} :agents {...} :rules [...]}"
  [dir]
  (when (fs/exists? dir)
    (let [tools-file (str (fs/path dir "tools.edn"))
          settings-file (str (fs/path dir "settings.edn"))
          instructions-file (str (fs/path dir "instructions.md"))
          commands-dir (str (fs/path dir "commands"))
          skills-dir (str (fs/path dir "skills"))
          agents-dir (str (fs/path dir "agents"))
          rules-dir (str (fs/path dir "rules"))]
      {:tools (io/read-edn tools-file)
       :settings (io/read-edn settings-file)
       :instructions (io/read-text instructions-file)
       :commands (load-paired-definitions commands-dir)
       :skills (load-skill-definitions skills-dir)
       :agents (load-paired-definitions agents-dir)
       :rules (load-rules rules-dir)})))
