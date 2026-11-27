(ns nido.core.provision-test
  (:require [clojure.test :refer [deftest is testing]]
            [nido.core.provision :as provision]))

(deftest the-session-verb-is-told-the-units-profile
  (let [argv (fn [a] (provision/verb-argv {:nido-dir "/n"} (merge {:unit "u" :want :up :project "brian"} a)))]
    (is (= ["bb" "--config" "/n/bb.edn" "nido:session:provision" ":project" "brian" ":want" "up" "u"]
           (argv {})))
    (is (= [":profile" "lite"] (take-last 2 (argv {:profile :lite}))))))

(def ^:private refusal-out
  (str "Refusing to destroy session nido: /src/nido is not its own to remove\n"
       (pr-str {:error "Refusing to destroy session nido: /src/nido is not its own to remove"
                :refused :not-owned :path "/src/nido"})))

(deftest a-destroy-the-verb-refused-is-recorded-refused
  (let [a {:ws "nido/ws-1" :unit "u" :attempt "a1"}
        cmd #(:command (provision/outcome-command (assoc a :want %) {:exit 1 :out refusal-out}))]
    (is (= :refused (:outcome (cmd :destroyed))))
    (is (re-find #"/src/nido is not its own" (:detail (cmd :destroyed))) "carrying the refusal's detail")
    (testing "any other attempt that meets the refusal is an ordinary failure, with its detail"
      (doseq [w [:up :stopped]]
        (is (= :failed (:outcome (cmd w))))
        (is (re-find #"not its own" (:detail (cmd w))))))
    (testing "an untyped failure of a destroy stays :failed"
      (is (= :failed (:outcome (:command (provision/outcome-command (assoc a :want :destroyed)
                                                                    {:exit 1 :out "{:error \"jj lock\"}"}))))))))

(deftest a-units-session-is-named-by-its-unit-unless-it-was-adopted
  (is (= "u" (provision/session-name {:unit "u"})) "no cwd yet")
  (is (= "u" (provision/session-name {:unit "u" :cwd "/wt/u"})))
  (is (= "feat/x" (provision/session-name {:unit "feat/x" :cwd "/wt/feat/x"}))
      "a slash-namespaced unit keeps its whole id, whose worktree-path the cwd is")
  (is (= "nido" (provision/session-name {:unit "ws-1-unit" :cwd "/home/me/Code/nido"}))
      "an adopted cwd keeps its own name")
  (is (= "barfoo" (provision/session-name {:unit "foo" :cwd "/wt/barfoo"}))
      "a cwd ending in the id's characters but not its segment is not the unit's"))

(deftest an-up-carries-the-dirs-the-verb-named-writable
  (let [a   {:ws "nido/ws-1" :unit "u" :attempt "a1" :want :up}
        cmd #(:command (provision/outcome-command a {:exit 0 :out (pr-str %)}))]
    (is (= ["/w/canvas"] (:writable (cmd {:cwd "/w" :writable ["/w/canvas"]})))
        "as opaque paths, the way the verb gave them")
    (is (not (contains? (cmd {:cwd "/w"}) :writable)) "a project with no declaration names none")))
