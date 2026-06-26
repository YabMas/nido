(ns nido.review.codex
  "Git-free codex review driver: jj diff --git -> codex exec --output-schema
   -> normalized findings. nido worktrees are non-colocated jj workspaces, so
   git-coupled `codex review` cannot run there."
  (:require
   [cheshire.core :as json]))

(defn normalize-finding
  "Codex native finding (keyword keys) -> normalized finding."
  [raw]
  (let [loc (:code_location raw)
        lr  (:line_range loc)]
    {:title      (:title raw)
     :body       (:body raw)
     :priority   (:priority raw)
     :confidence (:confidence_score raw)
     :file       (:absolute_file_path loc)
     :line-start (:start lr)
     :line-end   (:end lr)}))

(defn parse-output
  "Parse codex --output-schema JSON string into
   {:findings [...] :overall-correctness <str>}."
  [json-str]
  (let [m (json/parse-string json-str true)]
    {:findings            (mapv normalize-finding (:findings m))
     :overall-correctness (:overall_correctness m)}))
