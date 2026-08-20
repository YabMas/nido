(ns tasks.nido-workstream-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.workstream :as ws]
   [nido.work]
   [tasks.nido-workstream :as task]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try (with-redefs [cstate/nido-root (constantly (str tmp))]
           (cstate/ensure-dirs!) (f tmp))
         (finally (fs/delete-tree tmp)))))

(deftest stage-advance-routes-through-set-stage
  (let [calls (atom [])]
    (with-redefs [nido.work/set-stage! (fn [p w t] (swap! calls conj [p w t]) {:decision :advanced})]
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
        (task/stage-advance* {:project "brian" :ref "BR-3" :stage "investigating"})
        (is (= :investigating (:stage (ws/read-ws :brian (:id w)))))
        (task/close* {:project "brian" :ref "BR-3" :outcome "done"})
        (is (= :done (-> (ws/read-ws :brian (:id w)) :closed :outcome)))))))

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
