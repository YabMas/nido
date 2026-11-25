(ns nido.core.ask-tool
  "nido's ask, as an MCP tool any executor can offer its agent: a question, what was explored, the
   options with their consequences, and a recommendation — or, for several decisions, one
   question per decision, each with its own options.

   The tool itself never decides anything. An executor halts the turn at the call and resumes it
   with the resolution in the call's `answer`, which only nido sets. Called without one it
   refuses, telling the agent to ask again alone, so an executor that failed to halt on it (a
   provider that will not defer a call made alongside others) cannot let the agent read silence
   as an answer.

   Run as `bb --classpath <src> -m nido.core.ask-tool`: MCP over stdio, one JSON-RPC message per
   line. Loads under bb as well as the JVM."
  (:require [clojure.data.json :as json]))

(def ^:private option-schema
  {:type "object"
   :required ["option"]
   :properties {:option      {:type "string" :description "The branch, in a few words."}
                :consequence {:type "string" :description "What taking it means, and what it costs or forecloses."}
                :recommended {:type "boolean" :description "True on the one your reasoning supports."}}})

(def ^{:malli/schema :map} properties
  "An ask's content as a tool takes it, in JSON Schema properties: the ask tool's and the
   conductor's raise_operator_ask alike, so a unit's ask reaches the person in the shape it was
   written in."
  {:question       {:type "string"
                    :description "One sentence ending in `?`. With `questions`, the headline over them."}
   :explored       {:type "string"
                    :description (str "What the answer depends on, self-contained for a reader coming to it cold: "
                                      "what you looked at and found. Never cite ledger seqs or internal names alone.")}
   :options        {:type "array" :items option-schema
                    :description "The branches of a single decision. Leave it empty when you give `questions`."}
   :questions      {:type "array"
                    :description "Several decisions: one entry per decision, never packed into one question."
                    :items {:type "object"
                            :required ["question" "options"]
                            :properties {:question       {:type "string" :description "One sentence ending in `?`."}
                                         :context        {:type "string" :description "What this decision alone depends on."}
                                         :options        {:type "array" :items option-schema}
                                         :recommendation {:type "string" :description "Your answer to this one, and why."}}}}
   :recommendation {:type "string" :description "The answer your reasoning supports, and why."}
   :after          {:type "string" :description "What runs once the person answers, and what waits."}})

(def ^{:malli/schema :string} answer-guidance
  "How the person's answer to an ask is read, as the tools describing it and the conductor's
   brief say it."
  (str "The answer may carry the person's comment on a question, marked as theirs and placed under "
       "that question, with or without an option chosen for it. A comment may dispute the question "
       "itself or the options offered: answer such a comment by re-asking or reframing the question, "
       "never by proceeding on an option nobody chose."))

(def ^{:malli/schema :string} guidance
  "How an ask is written, as the tools describing it say it."
  (str "The person reads it cold, on a card, often hours later: write it to be answered from the card alone. "
       "Ask each decision as its own question. One decision: `question` plus its `options`. "
       "Several decisions: one entry per decision in `questions`, each with its own options and recommendation, "
       "and `question` as the headline over them; never pack several into one question, one options list, "
       "or lettered prose like \"1a, 1b, 2a\". Each option says what taking it means and what it costs; mark the "
       "one you support `recommended`. " answer-guidance))

(def ^{:malli/schema [:vector :keyword]} content-keys
  "The keys of an ask's content."
  [:question :explored :options :questions :recommendation :after])

(defn ^{:malli/schema [:=> [:cat [:maybe :map]] :map]} content
  "An ask's content out of a tool call's `input`: its content keys, the empty ones left out."
  [input]
  (into {} (remove (fn [[_ v]] (or (nil? v) (and (coll? v) (empty? v)) (and (string? v) (= "" v)))))
        (select-keys input content-keys)))

(def ^:private tool
  {:name "ask"
   :description (str "Ask for a decision you cannot make yourself. It MUST be the only tool call in "
                     "its turn. Your turn ends here, and you continue once the decision is made. "
                     guidance)
   :inputSchema {:type "object"
                 :required ["question" "explored" "recommendation"]
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
      "tools/list" (ok {:tools [tool]})
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
