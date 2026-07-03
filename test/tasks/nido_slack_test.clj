(ns tasks.nido-slack-test
  (:require
   [clojure.test :refer [deftest is]]
   [nido.slack.client :as slack]
   [tasks.nido-slack :as task]))

(deftest react-passes-slack-ts-through-verbatim
  ;; Regression: :ts looks like a float (e.g. "1718000000.000123"). If it
  ;; goes through edn/read-string (as it did before the raw-string-keys fix)
  ;; it becomes a double, and `(str ...)` on that double renders scientific
  ;; notation and drops trailing-zero microseconds — the corrupted ts never
  ;; matches the real Slack message, so reactions.add fails on every real
  ;; invocation. This test drives the TASK fn (the actual arg-parsing path),
  ;; not the client fn directly — a literal-string `ts` in a client-level
  ;; test would never exercise the corruption.
  (let [captured (atom nil)]
    (with-redefs [slack/keychain-token (fn [] "dummy-token")
                  slack/add-reaction   (fn [_channel ts _token _emoji]
                                         (reset! captured ts)
                                         {:ok true})]
      (task/react ":channel" "C123" ":ts" "1718000000.000123" ":name" "eyes")
      (is (= "1718000000.000123" @captured)
          "ts must survive verbatim, not become a mangled double")))

  (let [captured (atom nil)]
    (with-redefs [slack/keychain-token (fn [] "dummy-token")
                  slack/add-reaction   (fn [_channel ts _token _emoji]
                                         (reset! captured ts)
                                         {:ok true})]
      (task/react ":channel" "C123" ":ts" "1718000000.000100" ":name" "eyes")
      (is (= "1718000000.000100" @captured)
          "trailing-zero microseconds must not be dropped"))))

(deftest reply-passes-thread-ts-through-verbatim
  (let [captured (atom nil)]
    (with-redefs [slack/keychain-token (fn [] "dummy-token")
                  slack/post-message   (fn [_channel _token opts]
                                         (reset! captured (:thread-ts opts))
                                         {:ok true :ts "1718000000.000999"})]
      (task/reply ":channel" "C123" ":thread-ts" "1718000000.000123" ":text" "hi")
      (is (= "1718000000.000123" @captured)
          "thread-ts must survive verbatim, not become a mangled double")))

  (let [captured (atom nil)]
    (with-redefs [slack/keychain-token (fn [] "dummy-token")
                  slack/post-message   (fn [_channel _token opts]
                                         (reset! captured (:thread-ts opts))
                                         {:ok true :ts "1718000000.000999"})]
      (task/reply ":channel" "C123" ":thread-ts" "1718000000.000100" ":text" "hi")
      (is (= "1718000000.000100" @captured)
          "trailing-zero microseconds must not be dropped"))))

(deftest reply-passes-text-through-verbatim
  ;; :text is a raw-string key too — EDN-significant characters (brackets,
  ;; punctuation) would otherwise be mangled by read-string before reaching
  ;; Slack.
  (let [captured (atom nil)]
    (with-redefs [slack/keychain-token (fn [] "dummy-token")
                  slack/post-message   (fn [_channel _token opts]
                                         (reset! captured (:text opts))
                                         {:ok true :ts "1718000000.000999"})]
      (task/reply ":channel" "C123" ":thread-ts" "1718000000.000123"
                  ":text" "pr-ready [#3699]")
      (is (= "pr-ready [#3699]" @captured)
          "text must reach Slack verbatim"))))

(deftest react-tolerates-already-reacted
  ;; already_reacted is Slack's response to re-reacting the same message —
  ;; benign/idempotent, must not System/exit the task (which would kill the
  ;; whole process, including any batching caller).
  (with-redefs [slack/keychain-token (fn [] "dummy-token")
                slack/add-reaction   (fn [_channel _ts _token _emoji]
                                       {:error :api :detail "already_reacted"})]
    (task/react ":channel" "C123" ":ts" "1718000000.000123")
    (is true "task/react returned normally instead of exiting")))
