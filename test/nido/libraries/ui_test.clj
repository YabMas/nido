(ns nido.libraries.ui-test
  "nido.libraries.ui through its public functions alone — what an app sees of it."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [nido.libraries.ui.action :as action]
            [nido.libraries.ui.block :as block]
            [nido.libraries.ui.catalog-page :as catalog]
            [nido.libraries.ui.names :as names]
            [nido.libraries.ui.style :as style]
            [nido.libraries.ui.wire :as wire]))

;; ---------------------------------------------------------------------------
;; names

(deftest names-derive-from-one-instance-id
  (is (= "card:ws-1:title" (names/part-id :card "ws-1" :title)))
  (is (= {:id "card:ws-1:title" "data-scope" "card" "data-part" "title"}
         (names/part-attrs :card "ws-1" :title)))
  (is (= "#card\\:ws-1\\:title" (names/selector :card "ws-1" :title)))
  (is (= "card.ws_1.step" (names/signal :card "ws-1" :step)))
  (is (= "card.ws_1.answers.q0" (names/signal :card "ws-1" [:answers :q0])))
  (is (= "ask_bar.ws_1.reply" (names/signal :ask-bar "ws-1" :reply)) "a scope's - is no subtraction")
  (testing "two instances share no name"
    (is (not= (names/part-id :card "a" :root) (names/part-id :card "b" :root)))
    (is (not= (names/signal :card "a" :x) (names/signal :card "b" :x)))))

;; ---------------------------------------------------------------------------
;; actions

(def ^:private reply
  [:post :reply {:text [:signal :reply [:string {:min 1}]]
                 :value [:event :target.value :string]}])

(defn- router [k params] (str "/x/" (name k) (when-let [ws (:ws params)] (str "/" ws))))

(deftest an-action-is-spelled-by-the-library
  (let [e (action/expression reply router {:scope :card :id "ws-1" :params {:ws "w1"}
                                           :query {:entry 3}})]
    (is (str/includes? e "@post('/x/reply/w1?entry=3'"))
    (is (str/includes? e "$card.ws_1.ev.target_value = evt.target.value"))
    (is (str/includes? e "filterSignals: {include: /^card\\.ws_1(\\.|$)/}"))))

(deftest a-body-is-decoded-only-against-the-declaration
  (let [body (fn [m] (json/generate-string {:card {:ws_1 m} :other {:secret 1}}))
        opts {:scope :card :id "ws-1"}]
    (is (= {:ok {:text "hi" :value "v"}}
           (action/decode reply (body {:reply "hi" :ev {:target_value "v"} :extra 1}) opts)))
    (is (= {:failure :unparseable} (action/decode reply "not json" opts)))
    (is (= {:failure :missing :key :text} (action/decode reply nil opts)) "an absent body carries no field")
    (is (= {:ok {}} (action/decode [:post :go {}] nil opts)) "no fields, nothing to read")
    (is (= {:ok {:value "v"}}
           (action/decode [:post :reply {:text [:signal :reply :string {:optional true}]
                                         :value [:event :target.value :string]}]
                          (body {:ev {:target_value "v"}}) opts))
        "an optional field left out is absent")
    (is (= :missing (:failure (action/decode reply (body {:ev {:target_value "v"}}) opts))))
    (is (= :text (:key (action/decode reply (body {:ev {:target_value "v"}}) opts))))
    (let [bad (action/decode reply (body {:reply "" :ev {:target_value "v"}}) opts)]
      (is (= :invalid (:failure bad)))
      (is (= :text (:key bad)))
      (is (some? (:why bad))))))

(deftest every-decoded-answer-carries-what-its-outcome-owes
  (let [body (fn [m] (json/generate-string {:card {:ws_1 m}}))
        opts {:scope :card :id "ws-1"}]
    (testing "each answer the decoder gives is an arm of Decoded"
      (doseq [b [(body {:reply "hi" :ev {:target_value "v"}}) "not json" nil
                 (body {:ev {:target_value "v"}}) (body {:reply "" :ev {:target_value "v"}})]]
        (is (m/validate action/Decoded (action/decode reply b opts)) (pr-str b))))
    (testing "an arm missing what it owes is not Decoded"
      (is (not (m/validate action/Decoded {:failure :missing})) ":missing without its key")
      (is (not (m/validate action/Decoded {:failure :invalid :key :text})) ":invalid without its why")
      (is (not (m/validate action/Decoded {:failure :invalid :why "x"})) ":invalid without its key")
      (is (not (m/validate action/Decoded {:failure :unparseable :key :text})) ":unparseable carries nothing")
      (is (not (m/validate action/Decoded {:ok {} :failure :missing :key :text})) "fields and a failure at once")
      (is (not (m/validate action/Decoded {:failure :elsewhere}))))))

(deftest an-action-is-a-method-a-name-and-placeholders
  (is (m/validate action/Action reply))
  (is (m/validate action/Action [:get :open {}]))
  (is (m/validate action/Action [:post :pick {:at [:signal [:pick :at] :int {:optional true}]}]))
  (is (not (m/validate action/Action [:post :reply {:text [:signal :reply]}])) "a placeholder without its schema")
  (is (not (m/validate action/Action [:post :reply {:text [:query :reply :string]}])) "a source that is neither")
  (is (not (m/validate action/Action [:post :reply {:text [:event [:target :value] :string]}]))
      "an event field is one property path")
  (is (not (m/validate action/Action [:put :reply {}]))))

;; ---------------------------------------------------------------------------
;; blocks

(def ^:private panel
  {:id      :panel
   :render  (fn [{:keys [part action signal props]}]
              [:div (part :root)
               [:h2 (part :title) (:title props)]
               [:button {"data-on:click" (action :reply)} "go"]
               [:span {"data-text" (str "$" (signal :count))}]
               [:div (part :body) "b"]])
   :props   [:map [:title :string]]
   :css     ".panel { display:block; }"
   :actions {:reply reply}
   :morph   {:signals {:count 0} :preserve {:root [:open]} :ignore #{:body}}
   :scenes  [{:title "default" :props {:title "T"}}]})

(deftest a-block-is-checked-when-it-is-built
  (is (= :panel (:id (block/block panel))))
  (is (thrown? clojure.lang.ExceptionInfo (block/block (dissoc panel :morph))))
  (is (thrown? clojure.lang.ExceptionInfo (block/block (assoc panel :scenes []))))
  (is (thrown? clojure.lang.ExceptionInfo
               (block/block (assoc-in panel [:actions :reply] [:post :reply {:text [:signal :reply]}])))
      "an action whose placeholder carries no schema")
  (is (thrown? clojure.lang.ExceptionInfo
               (block/block (assoc panel :scenes [{:title "bad" :props {:title 1}}])))))

(deftest a-block-renders-its-morph-contract
  (let [b      (block/block panel)
        [seed [_ root & kids]] (block/render b "p-1" {:title "Hi"} {} router)]
    (is (= "panel:p-1:root" (:id root)))
    (is (= {"panel" {"p_1" {"count" 0}}} (json/parse-string (get (second seed) "data-signals__ifmissing")))
        "the signals are seeded before the root, so an expression on the root can read them")
    (is (true? (:hidden (second seed))))
    (is (nil? (get root "data-signals__ifmissing")))
    (is (= "open" (get root "data-preserve-attr")))
    (is (true? (get (second (last kids)) "data-ignore-morph")))
    (is (str/includes? (get (second (nth kids 1)) "data-on:click") "@post('/x/reply'"))
    (is (= "$panel.p_1.count" (get (second (nth kids 2)) "data-text"))))
  (testing "signals shaped by the props"
    (let [b    (block/block (assoc-in panel [:morph :signals] (fn [p] {:n (count (:title p))})))
          seed (first (block/render b "p" {:title "abc"} {} router))]
      (is (= {"panel" {"p" {"n" 3}}} (json/parse-string (get (second seed) "data-signals__ifmissing"))))))
  (testing "the same props, context and router render the same markup"
    (let [b (block/block panel)]
      (is (= (block/render b "p" {:title "x"} {} router) (block/render b "p" {:title "x"} {} router)))))
  (testing "props its schema refuses are refused"
    (is (thrown? clojure.lang.ExceptionInfo (block/render (block/block panel) "p" {:title 1} {} router)))))

(deftest a-kit-names-a-part-as-the-whole-render-does
  (let [b   (block/block panel)
        kit (block/kit b "p-1" {:title "Hi"} {} router)
        [_ [_ root & kids]] (block/render b "p-1" {:title "Hi"} {} router)]
    (is (= (:id (second (first kids))) (:id ((:part kit) :title)))
        "a part rendered alone lands where the whole render put it")
    (is (= "panel.p_1.count" ((:signal kit) :count)))
    (is (str/includes? ((:action kit) :reply) "@post('/x/reply'"))
    (is (= "panel:p-1:root" (:id root)))))

(deftest a-block-decodes-its-own-actions
  (let [b (block/block panel)]
    (is (= {:ok {:text "x" :value "y"}}
           (block/decode-action b :reply (json/generate-string {:panel {:p_1 {:reply "x" :ev {:target_value "y"}}}}) "p-1")))))

;; ---------------------------------------------------------------------------
;; style

(def ^:private tokens
  {:colour {:blue "#00f" :accent :blue}
   :type   {:fs-sm "13px"}
   :space  {:space-2 "8px"}
   :radius {:radius "4px"}
   :shadow {:lift "0 1px 2px var(--blue)"}
   :motion {:quick "120ms"}})

(deftest the-stylesheet-is-assembled-in-layers
  (let [css (style/stylesheet {:tokens tokens :blocks [(block/block panel)] :rules ".app { color: var(--accent); }"})]
    (is (str/starts-with? css "@layer tokens, layout, blocks, app;"))
    (is (= 1 (count (re-seq #":root \{" css))))
    (is (str/includes? css "--accent: var(--blue);"))
    (is (< (str/index-of css ".panel {") (str/index-of css ".app {")))))

(deftest the-lint-holds-rules-to-their-tokens
  (let [ok ".a { color: var(--accent); font-size: var(--fs-sm); padding: var(--space-2) 0;
                 border-radius: var(--radius); box-shadow: var(--lift); transition: color var(--quick); }"]
    (is (= [] (style/lint (str (style/root-block tokens) ok) tokens))))
  (let [kinds (fn [css] (set (map :kind (style/lint css tokens))))]
    (is (contains? (kinds ".a { color: #fff; }") :literal-colour))
    (is (contains? (kinds ".a { color: rgb(1,2,3); }") :literal-colour))
    (is (contains? (kinds ".a { font-size: 12px; }") :literal-size))
    (is (contains? (kinds ".a { margin: 4px 0; }") :literal-spacing))
    (is (not (contains? (kinds ".a { margin: 0 auto; }") :literal-spacing)))
    (is (contains? (kinds ".a { border-radius: 3px; }") :literal-radius))
    (is (contains? (kinds ".a { box-shadow: 0 1px 2px black; }") :literal-shadow))
    (is (contains? (kinds ".a { transition: color 0.2s; }") :literal-duration))
    (is (contains? (kinds ".a { color: var(--nope); }") :undeclared-token))
    (is (not (contains? (kinds ".a { height: var(--set-by-script, 0px); }") :undeclared-token))
        "a var() with a fallback reads a runtime property, not a token")
    (is (contains? (kinds "") :unread-token))
    (is (not (contains? (kinds "a:hover { color: var(--accent); }") :literal-colour)))))

;; ---------------------------------------------------------------------------
;; wire

(deftest the-wire-is-spelled-once
  (is (= :script (first (wire/script))))
  (is (= "event: datastar-patch-elements\ndata: selector #x\ndata: elements <a>\ndata: elements </a>\n\n"
         (wire/patch-elements-event "<a>\n</a>" {:selector "#x"})))
  (is (= "event: datastar-patch-signals\ndata: signals {\"a\":1}\n\n" (wire/patch-signals-event "{\"a\":1}"))))

;; ---------------------------------------------------------------------------
;; catalog

(def ^:private registry
  {:title    "test app"
   :surfaces [{:id "page" :label "page" :background "var(--bg)"}]
   :groups   [{:id "panels" :title "Panels"
               :blocks [{:name "panel" :scenes [{:title "default"
                                                 :render #(block/render (block/block panel) "s" {:title "T"} {} router)}]}]}]})

(deftest the-catalog-renders-the-registry-with-relative-links
  (let [page (catalog/catalog-page registry ".x{}" nil)]
    (is (str/includes? page "datastar.js"))
    (is (str/includes? page "panel:s:root"))
    (is (str/includes? page "background:var(--bg)"))
    (is (empty? (re-seq #"href=\"/" page)) "every link is relative")
    (is (not (str/includes? page "nido-picked")) "the picker loads only when embedded")
    (is (str/includes? (catalog/catalog-page registry "" "embed=1") "nido-picked"))))
