(ns nido.core.ask-tool
  "nido's ask, as an MCP tool any executor can offer its agent: a question, what was explored, the
   options with their consequences, and a recommendation.

   The tool itself never decides anything. An executor halts the turn at the call and resumes it
   with the resolution in the call's `answer`, which only nido sets. Called without one it
   refuses, telling the agent to ask again alone, so an executor that failed to halt on it (a
   provider that will not defer a call made alongside others) cannot let the agent read silence
   as an answer.

   Run as `bb --classpath <src> -m nido.core.ask-tool`: MCP over stdio, one JSON-RPC message per
   line. Loads under bb as well as the JVM."
  (:require [clojure.data.json :as json]))

(def ^:private tool
  {:name "ask"
   :description (str "Ask for a decision you cannot make yourself. It MUST be the only tool call in "
                     "its turn. Your turn ends here, and you continue once the decision is made.")
   :inputSchema {:type "object"
                 :required ["question" "explored" "options" "recommendation"]
                 :properties {:question       {:type "string"}
                              :explored       {:type "string" :description "What you looked at and tried."}
                              :options        {:type "array" :items {:type "string"}
                                               :description "Each option with its consequence."}
                              :recommendation {:type "string"}
                              :answer         {:type "string" :description "Set by nido only. Never set it."}}}})

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
