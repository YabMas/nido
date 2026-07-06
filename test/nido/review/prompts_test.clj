(ns nido.review.prompts-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [nido.review.prompts :as prompts]))

(deftest judge-prompt-inlines-design-doc-content
  (let [out (prompts/judge-prompt {:findings [{:priority 1 :title "t" :body "b"}]
                                   :history []
                                   :design-doc-content "SPEC BODY LINE ONE"})]
    (is (str/includes? out "SPEC BODY LINE ONE") "inlines the design-doc content")
    (is (not (str/includes? out "Design doc for context: ")) "no path-handoff line")))

(deftest judge-prompt-omits-design-block-when-no-content
  (let [out (prompts/judge-prompt {:findings [{:priority 1 :title "t" :body "b"}]
                                   :history []
                                   :design-doc-content nil})]
    (is (not (str/includes? out "Design doc")) "no design-doc block when content is nil")))
