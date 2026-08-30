(ns tasks.nido-workstream-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [nido.platform.core :as core]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.record.workstream :as ws]
   [nido.coordinator.work]
   [tasks.nido-workstream :as task]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try (with-redefs [core/nido-root (constantly (str tmp))]
           (cstate/ensure-dirs!) (f tmp))
         (finally (fs/delete-tree tmp)))))

(deftest stage-advance-routes-through-set-stage
  (let [calls (atom [])]
    (with-redefs [nido.coordinator.work/set-stage! (fn [p w t] (swap! calls conj [p w t]) {:decision :advanced})]
      (#'tasks.nido-workstream/stage-advance*
        {:project "brian" :ws-id "ws-1" :stage "in-progress"})
      (is (= [[:brian "ws-1" :in-progress]] @calls)
          "delegates to work/set-stage! so :in-progress provisions the planning leg"))))

(deftest entry-add-stage-advance-close
  (with-tmp
    (fn [_]
      (let [w (ws/create! :brian {:stage :triaging
                                  :external-refs [{:adapter :notion :id "BR-3"}]})]
        (task/entry-add* {:project "brian" :ref "BR-3" :kind "note" :content "found a bug"})
        (let [w2 (ws/read-ws :brian (:id w))]
          (is (= 1 (count (:entries w2))))
          (is (= :note (-> w2 :entries first :kind))))
        (task/stage-advance* {:project "brian" :ref "BR-3" :stage "ready"})
        (is (= :ready (:stage (ws/read-ws :brian (:id w)))))
        (task/close* {:project "brian" :ref "BR-3" :outcome "done"})
        (is (= :done (-> (ws/read-ws :brian (:id w)) :closed :outcome)))))))

(deftest stage-advance-refuses-a-stage-outside-the-vocabulary
  (with-tmp
    (fn [_]
      (ws/create! :brian {:stage :triaging :external-refs [{:adapter :notion :id "BR-3"}]})
      ;; :implementing is a ticket status. The CLI keywordizes whatever you type,
      ;; so this is the surface a stage nothing projects arrived through.
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo #"Unknown workstream stage"
            (task/stage-advance* {:project "brian" :ref "BR-3" :stage "implementing"})))
      (is (= :triaging (:stage (ws/read-ws :brian (:id (ws/find-by-ref :brian :notion "BR-3")))))
          "the refused stage is not written"))))

(deftest ref-add-stamps-github-ref
  (with-tmp
    (fn [_]
      (let [w (ws/create! :brian {:stage :in-progress
                                  :external-refs [{:adapter :notion :id "BR-9"}]})]
        (task/ref-add* {:project "brian" :ref "BR-9"
                        :adapter "github" :id "brian-study/brian#412"
                        :url "https://github.com/brian-study/brian/pull/412"
                        :title "Fix X"})
        (let [refs (:external-refs (ws/read-ws :brian (:id w)))]
          (is (= 2 (count refs)))
          (is (some #(and (= :github (:adapter %))
                          (= "brian-study/brian#412" (:id %))
                          (= "https://github.com/brian-study/brian/pull/412" (:url %)))
                    refs)))
        ;; idempotent: same id ⇒ no duplicate
        (task/ref-add* {:project "brian" :ref "BR-9"
                        :adapter "github" :id "brian-study/brian#412"})
        (is (= 2 (count (:external-refs (ws/read-ws :brian (:id w))))))))))

(deftest ref-add-files-the-pr-opened-event
  (with-tmp
    (fn [_]
      (let [w (ws/create! :brian {:stage :in-progress
                                  :external-refs [{:adapter :notion :id "BR-9"}]})]
        (task/ref-add* {:project "brian" :ref "BR-9"
                        :adapter "github" :id "brian-study/brian#412"
                        :url "https://gh/412" :title "Fix X"
                        :summary "collapses the two writers into one"})
        (is (= {:format :pr-opened :url "https://gh/412" :title "Fix X"
                :summary "collapses the two writers into one"}
               (dissoc (ws/latest-entry :brian (:id w) :pr-opened) :seq :at)))
        ;; the stack case: later layers stamp refs and stay silent
        (task/ref-add* {:project "brian" :ref "BR-9"
                        :adapter "github" :id "brian-study/brian#413"
                        :url "https://gh/413" :title "layer 2"})
        (is (= 1 (count (filter #(= :pr-opened (:kind %))
                                (:entries (ws/read-ws :brian (:id w))))))
            "one event per shipment, not one per layer")))))

(deftest ref-add-without-url-still-stamps-the-ref
  ;; PrOpened needs a :url and :title; correlation is what the merge poller
  ;; needs, so a malformed event must never cost the ref.
  (with-tmp
    (fn [_]
      (let [w (ws/create! :brian {:stage :in-progress
                                  :external-refs [{:adapter :notion :id "BR-9"}]})]
        (task/ref-add* {:project "brian" :ref "BR-9"
                        :adapter "github" :id "brian-study/brian#414"})
        (let [w' (ws/read-ws :brian (:id w))]
          (is (some #(= "brian-study/brian#414" (:id %)) (:external-refs w')))
          (is (empty? (filter #(= :pr-opened (:kind %)) (:entries w')))))))))

(deftest ref-add-notion-files-nothing
  (with-tmp
    (fn [_]
      (let [w (ws/create! :brian {:stage :triaging :external-refs []})]
        (task/ref-add* {:project "brian" :ws-id (:id w)
                        :adapter "notion" :id "BR-9" :url "https://notion/9" :title "t"})
        (is (empty? (:entries (ws/read-ws :brian (:id w)))))))))

;; The arg path, not the fn: every test above calls entry-add* with a map
;; already built, which is how a typed body could never survive `bb
;; nido:workstream:entry:add` without anyone noticing. split-args read the
;; leading brace as a map and handed entry-payload something it could not parse,
;; so the one documented route for a ledger-only workstream (/design §7) could
;; append notes and nothing else.
(def ^:private a-typed-event
  "Any registered kind proves the claim — that an EDN body survives the arg path.
   :blocker on purpose: the design schema is the one most likely to be tightened
   by later work, and a test down here has no business depending on that."
  {:format :blocker
   :summary "the importer bypasses the aggregate"
   :needs   "a decision on whether it should"})

(deftest entry-add-keeps-a-typed-body-intact-through-the-arg-path
  (with-tmp
    (fn [_]
      (let [w (ws/create! :brian {:stage :in-progress :external-refs []})]
        (task/entry-add ":project" "brian" ":ws-id" (:id w)
                        ":kind" "blocker" ":content" (pr-str a-typed-event))
        (is (= a-typed-event (dissoc (ws/latest-entry :brian (:id w) :blocker) :seq :at))
            "the EDN body round-trips: parsed, validated, and readable back")))))

(deftest entry-add-reads-a-typed-body-from-file
  (with-tmp
    (fn [tmp]
      (let [w    (ws/create! :brian {:stage :in-progress :external-refs []})
            path (str (fs/path tmp "blocker.edn"))]
        (spit path (pr-str a-typed-event))
        (task/entry-add ":project" "brian" ":ws-id" (:id w)
                        ":kind" "blocker" ":file" path)
        (is (= a-typed-event (dissoc (ws/latest-entry :brian (:id w) :blocker) :seq :at))
            ":file is the route a report of any real size has to take")))))

(deftest entry-add-still-appends-a-freeform-note
  (with-tmp
    (fn [_]
      (let [w (ws/create! :brian {:stage :in-progress :external-refs []})]
        (task/entry-add ":project" "brian" ":ws-id" (:id w)
                        ":kind" "note" ":content" "found a bug in the parser")
        (let [e (first (:entries (ws/read-ws :brian (:id w))))]
          (is (= :note (:kind e)))
          (is (= "found a bug in the parser"
                 (slurp (str (fs/path (cstate/workstream-dir :brian (:id w)) (:file e)))))
              "an untyped body is still stored verbatim, not EDN-mangled"))))))

(deftest ref-add-keeps-a-stack-pr-title-verbatim
  (with-tmp
    (fn [_]
      (let [w (ws/create! :brian {:stage :in-progress
                                  :external-refs [{:adapter :notion :id "BR-77"}]})]
        ;; Through the CLI arg path, not ref-add* — the bug was in parsing.
        ;; "[2/2] …" read as EDN is a vector holding the ratio 2/2, i.e. [1],
        ;; and the Workstream schema then rejects the whole record. Every stack
        ;; PR above the bottom carries this title shape (/prepare-draft-pr §3).
        (task/ref-add ":project" "brian" ":ref" "BR-77"
                      ":adapter" "github" ":id" "brian-study/brian#4696"
                      ":url" "https://github.com/brian-study/brian/pull/4696"
                      ":title" "[2/2] feat(course): let a course be taught in the language it teaches")
        (let [r (->> (:external-refs (ws/read-ws :brian (:id w)))
                     (filter #(= :github (:adapter %)))
                     first)]
          (is (= "[2/2] feat(course): let a course be taught in the language it teaches"
                 (:title r)))
          (is (= "https://github.com/brian-study/brian/pull/4696" (:url r))))))))

(deftest ref-add-keeps-a-summary-that-opens-with-a-number
  (with-tmp
    (fn [_]
      (let [w (ws/create! :brian {:stage :in-progress
                                  :external-refs [{:adapter :notion :id "BR-78"}]})]
        ;; The symbol carve-out rescues prose starting with a letter; a summary
        ;; opening on a digit is read as a number and everything after it lost.
        (task/ref-add ":project" "brian" ":ref" "BR-78"
                      ":adapter" "github" ":id" "brian-study/brian#1"
                      ":url" "https://github.com/brian-study/brian/pull/1"
                      ":title" "Some PR"
                      ":summary" "2 layers: copy, then behaviour. Refs BR-78")
        (let [e (last (:entries (ws/read-ws :brian (:id w))))]
          (is (= :pr-opened (:kind e)))
          (is (str/includes?
                (slurp (str (fs/path (cstate/workstream-dir :brian (:id w)) (:file e))))
                "2 layers: copy, then behaviour")
              "the whole sentence survives, not the leading 2"))))))
