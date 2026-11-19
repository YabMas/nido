(ns nido.platform.desk-test
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [nido.platform.core :as core]
   [nido.platform.desk :as desk]
   [nido.platform.desktop :as desktop]))

(defmacro with-home [& body]
  `(let [home# (str (fs/create-temp-dir))]
     (try
       (with-redefs [core/nido-root (constantly home#)]
         ~@body)
       (finally (fs/delete-tree home#)))))

(defn- sh-eval
  "What `script` prints when a POSIX shell runs it."
  [script]
  (:out (p/shell {:out :string} "sh" "-c" script)))

(defn- performed
  "Run `f` with the desktop stubbed, returning what reached it."
  [f]
  (let [calls (atom [])]
    (with-redefs [desktop/open-terminal! (fn [e] (swap! calls conj [:terminal e]) true)
                  desktop/open-url!      (fn [u] (swap! calls conj [:url u]) true)
                  desktop/notify!        (fn [n] (swap! calls conj [:notify n]) true)]
      (f))
    @calls))

(deftest only-a-closed-effect-of-a-known-kind-parses
  (is (desk/parse {:kind :terminal :dir "/w" :command "ls"}))
  (is (desk/parse {:kind :terminal :dir "/w"}))
  (is (desk/parse {:kind :url :url "http://localhost:8800/x"}))
  (is (desk/parse {:kind :notify :message "m" :title "t" :subtitle nil :sound "Glass"}))
  (testing "rejected"
    (is (nil? (desk/parse {:kind :terminal :dir "relative"})))
    (is (nil? (desk/parse {:kind :terminal :dir "/w" :extra 1})))
    (is (nil? (desk/parse {:kind :url :url "file:///etc/passwd"})))
    (is (nil? (desk/parse {:kind :url :url "javascript:alert(1)"})))
    (is (nil? (desk/parse {:kind :shell :command "rm -rf /"})))
    (is (nil? (desk/parse "not a map")))))

(deftest with-the-person-here-an-effect-is-performed-in-place
  (with-home
    (is (= [[:url "https://example.com"]]
           (performed #(desk/request! {:kind :url :url "https://example.com"}))))
    (is (= [[:notify {:message "m" :title "t"}]]
           (performed #(desk/request! {:kind :notify :message "m" :title "t"}))))
    (testing "nothing is recorded"
      (is (= {:effects [] :cursor 0} (desk/requests-since 0))))))

(deftest with-the-person-elsewhere-an-effect-is-recorded-not-performed
  (with-home
    (desk/set-person! :elsewhere)
    (let [start (:cursor (desk/requests-since nil))]
      (is (= [] (performed #(desk/request! {:kind :url :url "https://example.com"}))))
      (desk/request! {:kind :terminal :dir "/w" :command "claude"})
      (let [{:keys [effects cursor]} (desk/requests-since start)]
        (is (= [{:kind :url :url "https://example.com"}
                {:kind :terminal :dir "/w" :command "claude"}]
               (map :effect effects)))
        (testing "reading from the returned cursor answers only what came after"
          (desk/request! {:kind :notify :message "later"})
          (is (= [{:kind :notify :message "later"}]
                 (map :effect (:effects (desk/requests-since cursor))))))))))

(deftest a-request-that-is-not-an-effect-is-dropped
  (with-home
    (desk/set-person! :elsewhere)
    (is (nil? (desk/request! {:kind :url :url "file:///etc/passwd"})))
    (is (= [] (:effects (desk/requests-since 0)))))
  (with-home
    (is (= [] (performed #(desk/request! {:kind :shell :command "x"}))))))

(deftest a-desk-that-connects-reads-nothing-asked-before-it
  (with-home
    (desk/set-person! :elsewhere)
    (desk/request! {:kind :url :url "https://old.example"})
    (let [{:keys [effects cursor]} (desk/requests-since nil)]
      (is (= [] effects))
      (is (= [] (:effects (desk/requests-since cursor)))))))

(deftest the-log-keeps-a-bounded-tail
  (with-home
    (desk/set-person! :elsewhere)
    (dotimes [i 250] (desk/request! {:kind :notify :message (str i)}))
    (let [{:keys [effects cursor]} (desk/requests-since 0)]
      (is (= 250 cursor))
      (is (= 200 (count effects)))
      (is (= "249" (-> effects last :effect :message))))))

(def ^:private fake-ssh
  "A shell function standing in for ssh: prints the one remote-command argument it got."
  "ssh() { shift 2; printf %s \"$1\"; }; ")

(deftest a-remote-terminal-effect-runs-its-command-on-nidos-host
  (let [e     (desk/localize "devbox" {:kind :terminal :dir "/home/me/w it's" :command "claude --resume x"})
        ;; Unwrap it as the two shells would: the local one hands ssh one argument, and the
        ;; remote login shell gets the command inside `exec $SHELL -lc`.
        sent  (sh-eval (str fake-ssh (:command e)))
        inner (sh-eval (str/replace-first sent "exec $SHELL -lc " "printf %s "))
        cd    (first (str/split inner #" && "))]
    (is (= :terminal (:kind e)))
    (is (= (str (fs/home)) (:dir e)))
    (is (str/starts-with? (:command e) "ssh -t 'devbox' "))
    (is (str/starts-with? sent "exec $SHELL -lc "))
    (is (= "cd '/home/me/w it'\\''s' && claude --resume x" inner))
    (testing "the directory is one word to the remote cd"
      (is (= "/home/me/w it's" (sh-eval (str/replace-first cd "cd " "printf %s "))))))
  (testing "without a command the remote side gets a login shell"
    (let [cmd (:command (desk/localize "devbox" {:kind :terminal :dir "/w"}))]
      (is (= "exec $SHELL -lc 'cd '\\''/w'\\'' && exec $SHELL -l'"
             (sh-eval (str fake-ssh cmd))))))
  (testing "other kinds pass through"
    (is (= {:kind :url :url "http://localhost:8800"}
           (desk/localize "devbox" {:kind :url :url "http://localhost:8800"})))))

(deftest the-desk-performs-only-lines-that-parse
  (let [read-line-effect @#'desk/read-line-effect]
    (is (= {:kind :url :url "https://a.example"} (read-line-effect "{:kind :url :url \"https://a.example\"}")))
    (is (nil? (read-line-effect ";; beat")))
    (is (nil? (read-line-effect "")))
    (is (nil? (read-line-effect "#=(eval (System/exit 1))")))
    (is (nil? (read-line-effect "{:kind :shell :command \"rm -rf ~\"}")))
    (is (nil? (read-line-effect "not edn {")))))
