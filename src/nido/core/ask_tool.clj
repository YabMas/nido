(ns nido.core.ask-tool
  "nido's ask, as an MCP tool any executor can offer its agent: a question, the context its answer
   depends on, the options with their consequences, and a recommendation when there is a genuine
   one — or, for several decisions, one question per decision, each with its own options.

   How an ask is written is not said here: `briefing` reads it from resources/asking/ — ask.md,
   the rules every question for the person is written to, then core-ask.md, what only a core ask
   has — and it is what this tool's description, raise_operator_ask's and the conductor's brief
   carry. A field's description states only that field's own form.

   The tool itself never decides anything. An executor halts the turn at the call and resumes it
   with the resolution in the call's `answer`, which only nido sets. Called without one it
   refuses, telling the agent to ask again alone, so an executor that failed to halt on it (a
   provider that will not defer a call made alongside others) cannot let the agent read silence
   as an answer.

   Run as `bb --classpath <src> -m nido.core.ask-tool`: MCP over stdio, one JSON-RPC message per
   line. Loads under bb as well as the JVM."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [nido.core.domain :as d]))

(def ^:private option-schema
  {:type "object"
   :required ["option"]
   :properties {:option      {:type "string" :description "The branch, in a few words."}
                :consequence {:type "string" :description "What taking it means, and what it costs or forecloses."}
                :recommended {:type "boolean" :description "True on the one your reasoning supports."}}})

(def ^:private one-question
  (str "One sentence on one line, at most " d/question-max " characters, ending in `?`."))

(def ^{:malli/schema :map} properties
  "An ask's content as a tool takes it, in JSON Schema properties: the ask tool's and the
   conductor's raise_operator_ask alike, so a unit's ask reaches the person in the shape it was
   written in."
  {:question       {:type "string"
                    :description (str one-question " With `questions`, the headline over them.")}
   :explored       {:type "string"
                    :description (str "Only the context the answer depends on — what the work is, where it stands, "
                                      "what makes this a question — self-contained for a reader coming to it cold. "
                                      "Never how the question reached the person: no review-loop history, no round "
                                      "counts, not who escalated or stopped.")}
   :options        {:type "array" :items option-schema
                    :description "The branches of a single decision. Leave it empty when you give `questions`."}
   :questions      {:type "array"
                    :description "Several decisions: one entry per decision, never packed into one question."
                    :items {:type "object"
                            :required ["question" "options"]
                            :properties {:question       {:type "string" :description one-question}
                                         :context        {:type "string" :description "What this decision alone depends on."}
                                         :options        {:type "array" :items option-schema}
                                         :recommendation {:type "string" :description "Your answer to this one, and why — only when you hold one."}}}}
   :recommendation {:type "string" :description "The answer your reasoning supports, and why — only when you hold one; leave it out otherwise."}
   :after          {:type "string" :description "What runs once the person answers, and what waits."}})

(defn- asking-dir
  "resources/asking/ of the checkout this namespace loaded from. Found from this file's own place
   rather than through nido.platform.core/nido-source-dir, which needs babashka.fs, and the JVM
   core's classpath has none; nor as a classpath resource, since the tool servers run with src/
   alone on theirs."
  []
  (-> (io/resource "nido/core/ask_tool.clj") io/file
      .getParentFile .getParentFile .getParentFile .getParentFile
      (io/file "resources" "asking")))

(defn- asking-file [n]
  (io/file (asking-dir) (str n ".md")))

(defn ^{:malli/schema [:=> [:cat] :string]} briefing
  "How a core ask is written and read, as the tools describing it and the conductor's brief say
   it: resources/asking/ask.md, then the core addendum resources/asking/core-ask.md. Read each
   time it is asked for, so an edit to either is what the next description and brief carry."
  []
  (str/join "\n\n" (map #(str/trim (slurp (asking-file %))) ["ask" "core-ask"])))

(def ^{:malli/schema [:vector :keyword]} content-keys
  "The keys of an ask's content."
  [:question :explored :options :questions :recommendation :after])

(defn ^{:malli/schema [:=> [:cat [:maybe :map]] :map]} content
  "An ask's content out of a tool call's `input`: its content keys, the empty ones left out."
  [input]
  (into {} (remove (fn [[_ v]] (or (nil? v) (and (coll? v) (empty? v)) (and (string? v) (= "" v)))))
        (select-keys input content-keys)))

(defn- tool []
  {:name "ask"
   :description (str "Ask the person for a decision you cannot make yourself. It MUST be the only tool "
                     "call in its turn.\n\n" (briefing))
   :inputSchema {:type "object"
                 :required ["question" "explored"]
                 :properties (assoc properties
                                    :answer {:type "string" :description "Set by nido only. Never set it."})}})

(defn ^{:malli/schema [:=> [:cat :map] [:maybe :map]]} respond
  "The JSON-RPC response to `msg`, or nil for a notification."
  [{:keys [id method params]}]
  (let [ok #(hash-map :jsonrpc "2.0" :id id :result %)]
    (case method
      "initialize" (ok {:protocolVersion (get params :protocolVersion "2025-06-18")
                        :capabilities {:tools {}}
                        :serverInfo {:name "nido" :version "1"}})
      "tools/list" (ok {:tools [(tool)]})
      "tools/call" (let [answer (get-in params [:arguments :answer])]
                     (ok (if (seq answer)
                           {:content [{:type "text" :text answer}]}
                           {:isError true
                            :content [{:type "text"
                                       :text (str "REFUSED: this ask was not halted on, so it has no answer. "
                                                  "Call ask again as the ONLY tool call in its turn.")}]})))
      (when id (ok {})))))

(defn ^:export -main [& _]
  (doseq [line (line-seq (java.io.BufferedReader. *in*))
          :when (seq (.trim ^String line))]
    (when-let [r (respond (json/read-str line :key-fn keyword))]
      (println (json/write-str r))
      (flush))))
